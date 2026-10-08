# Development

English | [简体中文](development.zh-CN.md)

This guide covers architecture, local development, persistence, REST APIs and the Worker protocol. For operations see [Deployment](deployment.md); for scope, cleanup and result accounting see [Execution safety](execution-safety.md).

## Architecture

```text
React / Vite → REST + SSE → Spring Boot Server
                                ├→ Platform PostgreSQL
                                ├→ Driver storage / Run workspaces
                                └→ Worker JVM → external JDBC driver → Target database
```

| Module / path | Responsibility |
|---|---|
| `jdbc-test-core` | Catalog, selection, request/report models, shared utilities |
| `jdbc-test-suite` | JDBC scenarios, JUnit extensions, packaged SQL resources |
| `jdbc-test-worker` | Driver isolation, execution, validation, connection probes, NDJSON |
| `jdbc-test-server` | REST, persistence, scheduling, SSE, production static hosting |
| `jdbc-test-web` | Independent React 19 / strict TypeScript / Vite 7 npm project |
| `scripts` | Python CLI, reports, regressions and release packaging |
| `adapters`, `compatibility/v1`, `ddl`, `dml`, `pool` | Adapter capabilities, scenario IDs and SQL/pool assets |

Server depends on Core and does not load Suite or uploaded driver classes. The Worker fat JAR bundles Core, Suite, JUnit and SQL resources, with target drivers supplied externally. CLI retains its Maven/JUnit execution path; Web uses Server-scheduled Workers. Both share scenarios and execution safety rules. Platform PostgreSQL is separate from the target database.

## Local startup

Requirements: JDK 21, Maven >=3.9 (Wrapper 3.9.15), Node >=22.12.0 and npm; Python >=3.9 for CLI tools. Prepare Platform PostgreSQL, a persistent 32-byte Base64 master key and a writable data directory.

From the repository root:

```sh
./mvnw -DskipTests install
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/jdbc_platform'
export SPRING_DATASOURCE_USERNAME='jdbc_platform'
export SPRING_DATASOURCE_PASSWORD='your-platform-password'
export JDBC_TEST_MASTER_KEY='your-32-byte-base64-master-key'
export JDBC_TEST_DATA_DIRECTORY="$PWD/data"
export JDBC_TEST_WORKER_EXECUTABLE="$PWD/jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar"
./mvnw -pl jdbc-test-server spring-boot:run
```

Replace placeholders; keep secrets out of Git. In another terminal:

```sh
cd jdbc-test-web
npm ci
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to Server `:8080` without SSE timeouts. `VITE_API_BASE_URL` optionally sets the API base at build time. Production serves built assets from Server `:8080`; the ordinary Maven lifecycle does not run npm.

## Web UI

Routes cover Dashboard, `/drivers`, `/connections`, `/runs`, creation forms and `/:id` details. The workflow is upload/validate driver → save/probe connection → review selection and authorized scope → consent/start → live results/report.

TanStack Query owns server state; forms and transport state remain local. `src/api/client.ts` handles fetch, JSON/multipart/204, cancellation and RFC Problem Details. Resource DTOs have no universal success envelope. TanStack Table handles tables; Tailwind/shadcn/Radix handle UI; ECharts renders outcomes. There is no production mock API.

Run detail loads REST first, then opens native `EventSource` for active runs. SSE updates the same Query cache; terminal events close the stream and refresh final results. Reconnects do not change Run status; a 10-second REST fallback covers missed terminal events. Passwords stay in component memory, clear after requests, and never enter browser storage, URLs or query caches. Diagnostics render as text.

Dashboard outcomes describe only the eight loaded recent runs; catalog totals describe inventory. There are no global trend, module-aggregate or safe diagnostics endpoints. Run lists filter by status. Results support status or module filtering, with status taking precedence. The UI does not invent unavailable statistics or a Logs tab.

## Platform database

Flyway V1–V5 owns schema; Hibernate uses `ddl-auto=validate`. UUIDs identify records and UTC `Instant` values use `timestamp with time zone`. References protect historical runs from deletion of used drivers/connections.

| Migration | Purpose |
|---|---|
| V1 | Driver packages/artifacts, connection profiles, runs/modules/results |
| V2 | Unique driver package fingerprint |
| V3 | Explicit connection `adapter_id` |
| V4 | Run snapshots, selected scenarios, encrypted queued credentials |
| V5 | Authorized scope, frozen inputs, evidence version, starts/completions/issues |

Saved passwords use AES-256-GCM with a fresh 12-byte nonce and envelope `v1:<base64 nonce>:<base64 ciphertext>`. `JDBC_TEST_MASTER_KEY` supplies the persistent 32-byte key. Plaintext is absent from entities, snapshots and diagnostics. Queue credentials use a separate encrypted table and are deleted on Worker handoff and terminal/recovery paths. Legacy runs retain their original evidence; V5 does not infer starts.

## REST API

Lists use bounded `page`/`size` pagination (default 20, maximum 100), sort allowlists and `{items, page, size, totalElements, totalPages}` responses. Errors use RFC Problem Details with a stable `code`. Creation returns `201` and `Location`; deletion returns `204`. Historical references prevent deletion with `409`.

### Drivers

| Method | Endpoint | Purpose |
|---|---|---|
| POST / GET | `/api/drivers` | Upload / list packages |
| GET / PATCH / DELETE | `/api/drivers/{id}` | Read / edit metadata / delete |
| POST | `/api/drivers/{id}/validate` | Validate using Worker |

Upload multipart `metadata` JSON and repeated `files` parts. Defaults: 20 artifacts, 100 MiB each, 250 MiB total. Binaries are immutable; upload a new package to replace them. Server reads JAR metadata/SPI only; ambiguous or absent SPI requires explicit `driverClass`.

Files live at `<data-dir>/drivers/<driver-id>/<artifact-id>.jar`; user filenames are display metadata. Each artifact has size and SHA-256; the package fingerprint hashes sorted artifact digests joined by a newline. Duplicate packages return `DRIVER_PACKAGE_ALREADY_EXISTS`. Class edits reset validation. Worker validation loads the class but does not connect, with a default 30-second timeout. Used packages return `DRIVER_IN_USE` on deletion. Driver sort fields: `name`, `vendor`, `version`, `status`, `createdAt`.

### Connections

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/adapters` | Built-in adapter metadata |
| POST / GET | `/api/connections` | Create / list profiles |
| GET / PATCH / DELETE | `/api/connections/{id}` | Read / update / delete |
| POST | `/api/connections/test` | Probe an unsaved draft |
| POST | `/api/connections/{id}/test` | Probe saved settings |

`databaseType` is an open string; `adapterId` independently selects SQL/capabilities. A profile references a validated driver and records JDBC URL, username and authorized execution scope. Persisted URLs reject credential parameters (`password`, `passwd`, `pwd`, `secret`, `token`, `access_token`) with `CONNECTION_URL_CONTAINS_CREDENTIALS`.

Passwords are write-only; responses expose `hasSavedPassword`. Save/replace with `password` plus `savePassword: true`; clear with `clearSavedPassword: true`, never both actions together. A temporary probe password overrides the saved password for that request. Save and Probe are independent; save edits before probing persisted settings.

Probes use Worker `Driver.connect`, read metadata best-effort, close and exit without SQL or test execution. The default timeout is 30 seconds. Saved probes update last-test time/status; drafts persist nothing. A null connection gives `DRIVER_DOES_NOT_ACCEPT_URL`; used profiles cannot be deleted (`CONNECTION_IN_USE`). Sort fields: `name`, `databaseType`, `lastConnectionTestAt`, `createdAt`.

### Runs

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/test-catalog` | Profiles, modules and scenarios |
| POST / GET | `/api/runs` | Create / list runs (optional `status` filter) |
| GET | `/api/runs/{id}` | Snapshot and progress |
| POST | `/api/runs/{id}/cancel` | Cancel |
| GET | `/api/runs/{id}/events` | SSE, starting with `run.snapshot` |
| GET | `/api/runs/{id}/results` | Paginated scenario results |
| GET | `/api/runs/{id}/report` | Summary and failures |
| GET | `/api/runs/{id}/report.json` | Raw JSON evidence |

There are 17 modules; CORE selects 136 scenarios, FULL 202, and CUSTOM selects modules/scenarios. These are catalog counts, not proven compatibility results. New runs require explicit scope and `destructiveConsent: true`. Selection and nonsecret execution inputs are frozen at enqueue; a new validation/probe is not performed automatically.

Normal states: `QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED`. Failure, cancellation and recovery end in `FAILED`, `CANCELLED`, `INTERRUPTED`. A local FIFO scheduler limits concurrent Workers; workspaces are `<data-dir>/runs/<run-id>`. Run row locks protect terminal states against late results/cancel races. Timeout gives `FAILED / RUN_TIMEOUT`; a slot stays occupied until process cleanup finishes.

Server checks events, report entries and counts before success. Invalid/inconsistent reports fail with `REPORT_INCONSISTENT`; FAILED/ABORTED results yield `WORKER_EXECUTION_FAILED` even with exit code 0. The report DTO is `{summary, failures}`. Protocol v2 raw JSON can be reconstructed from persisted evidence, with `source` and `workerReportValidated` identifying that boundary. See [Execution safety](execution-safety.md) for accounting rules.

## Worker protocol

Deploy Server and Worker together. Each Worker reads one stdin JSON request and emits stdout NDJSON. Passwords go through stdin, never argv. User driver stdout cannot corrupt the protocol; stderr diagnostics are bounded and sanitized. A per-run `URLClassLoader` with platform parent loads the selected driver and calls `Driver.connect` directly without global `DriverManager` registration.

| Mode | Purpose |
|---|---|
| No flag | Execute a single run |
| `--describe-catalog` | Emit `CATALOG_DESCRIBED`, including `protocolVersion: 2`, without driver loading |
| `--catalog-smoke` | Check the packaged catalog independently of working directory |
| `--validate-driver` | Load/instantiate driver; return JDBC metadata without a connection |
| `--probe-connection` | Open/close a connection without suite SQL |

```sh
java -jar jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar --describe-catalog
```

Server checks protocol version at catalog load and again before every execution. Mismatch gives `WORKER_PROTOCOL_MISMATCH` before sending execution credentials. Validation/probe modes retain their separate request contracts. For execution fields see [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java): `protocolVersion`, `runId`, `driver`, `connection`, `selection`, `workspace`, `timeoutMs`, `scope`, `objectPrefix`, `capabilities`, `destructiveConsent`. Requests require version 2, explicit scope/consent, prefix `j` plus 20 lowercase hex digits, all 25 boolean capability declarations and the selected `connection.adapterId`.

Events include `RUN_STARTED`, `MODULE_STARTED`, `CASE_STARTED`, `CASE_FINISHED`, `MODULE_FINISHED`, `RUN_FINISHED`, `RUN_FAILED`. Malformed/unexpected events, wrong run IDs and unselected/duplicate results fail with `WORKER_PROTOCOL_ERROR`. Report output is `<workspace>/report/report.json`, schema `2.0.0`; temporary config is deleted on exit.

| Exit code | Meaning |
|---:|---|
| 0 | Run completed; Server still validates outcomes/report |
| 2 | Invalid request or selection |
| 3 | Driver/connection setup failure |
| 4 | Execution or timeout failure |
| 5 | Catalog, workspace, report or internal failure |

## Testing

```sh
./mvnw clean verify
./mvnw -Pworker-e2e,server-integration verify
npm --prefix jdbc-test-web ci
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run test
npm --prefix jdbc-test-web run build
python3 scripts/run_contract_tests.py
```

Default Maven tests do not require user databases. Docker profiles exercise disposable platform/target PostgreSQL and MySQL; Docker-unavailable integration tests skip. `DeterministicOrchestrationTest` uses test-only H2 and a controlled Worker executable to check scheduling, REST/SSE, cancellation and persistence. Production remains PostgreSQL with real Workers.

Browser checks use separate mocked and real projects:

```sh
cd jdbc-test-web
npx playwright install chromium
npm run test:e2e
export JDBC_TEST_E2E_DRIVER_JAR='/absolute/path/to/postgresql.jar'
export JDBC_TEST_E2E_DRIVER_VERSION='your-driver-version'
export JDBC_TEST_E2E_TARGET_URL='jdbc:postgresql://127.0.0.1:55433/jdbc_ui'
export JDBC_TEST_E2E_USERNAME='jdbc_ui'
export JDBC_TEST_E2E_PASSWORD='your-target-password'
npm run test:e2e:real
```

The real project requires running Server/Worker, a clean platform database/artifact store and a separate authorized target PostgreSQL. Missing inputs fail rather than fall back to mocks. It creates persistent test data; use fresh acceptance storage on rerun and never use shared/business databases. Trace collection is disabled. `PLAYWRIGHT_CHANNEL=chrome` can use installed Chrome. Production-mode checks are described in [Deployment](deployment.md).
