# Development

English | [简体中文](development.zh-CN.md)

For source edits use the unified development command below. To run the website without developing source, choose [Docker or a non-Docker deployment bundle](deployment.md). Native is a deployment bundle, not IDEA source startup.

<a id="local-startup"></a>
<a id="local-source-startup"></a>
## Local source startup

### First-time preparation: three steps

Install **JDK21 (including javac)** and **Node >=22.12/npm**. The committed Maven Wrapper supplies Maven3.9.15; first downloads need network access. dev requires no Python. Prepare a dedicated empty development **platform PG** database/login with schema/migration permissions; Server applies Flyway migrations at formal startup. This stores application state and encrypted credentials, while target databases are separate UI configurations and unnecessary for page/Catalog startup. Do not use QA or historical copies.

1. In the **source root**, copy **`.env.example` to `.env` only if absent**. Existing `.env`, Key and data are never replaced. There is one source-development template; no template selection is needed.
2. In a local editor open that **actual source-root `.env`**. Fill `SPRING_DATASOURCE_URL` (full URL including database name, no credentials), `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` (existing PG password). For local PG the example URL is `jdbc:postgresql://localhost:5432/jdbc_test`; replace its actual host/port/database and username. New platform: leave `JDBC_TEST_MASTER_KEY` empty. Existing platform: preserve its original Key and data path. Do not put secrets into terminal history, shared IDEA configs or Git.
3. Run **`./jdbc-test dev`** in the source root. It protects the owner's local `.env` before reading, authenticates PG and checks state before generating a Key. Confirm `NEW` only for a genuinely new empty platform, then open the printed ready address. Missing `.env` exits immediately before Java/build, tells you where to copy, and creates no configuration.

**Terminal commands — Linux/macOS, source root (edit after copy):**

```sh
umask 077
cp -n .env.example .env
# Open this actual .env in your editor and fill the three datasource fields.
./jdbc-test dev
```

**PowerShell terminal alternative — source root:**

```powershell
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
# Edit this actual .env locally before starting.
./jdbc-test.ps1 dev
```

**Interactive input — actual new-platform confirmation:**

```text
For a confirmed NEW empty platform type NEW; for existing data/recovery press Enter: NEW
```

The independent 32-byte Base64 Master Key is saved in the same `.env`, never printed. It encrypts stored target credentials, not PG authentication. Normal starts reuse it; missing Key with existing schema/data/legacy config stops and requires the original Key. Canceling does not generate a replacement. No account password changes, DROP or target tests occur.

Password rules are literal: everything after `=` in an unquoted value is retained (spaces, `$`, `#`, backslashes, `=`, `${NAME}`, valid UTF-8). No inline comments or Shell expansion. A value starting with double quotes is a JSON string; use JSON escapes, or enclose a quote-leading password in single quotes. Controls/newlines are rejected without trimming. Never source/eval `.env`. See [configuration rules](deployment.md#configuration-rules).

The same command builds the current Java/Suite/Worker, finds the **exact current POM-version Worker** (never first-glob/stale fallback), installs dependencies on first use/lockfile/toolchain change, launches the **source main via Maven spring-boot:run** and Vite. It waits for health/Catalog/page/proxied Catalog, then prints URLs; it does not build production frontend assets in dev.

Defaults: page **http://127.0.0.1:5173**, backend **http://127.0.0.1:8080**. Change `.env` `SERVER_PORT` or optional `JDBC_TEST_WEB_PORT` and rerun; proxy follows the actual backend port automatically. Occupied ports abort without killing other services. Only backend receives credentials/Key; Vite does not load `.env`. Data defaults to source-root **`data/`**, or `JDBC_TEST_DATA_DIRECTORY`; reuse the same path and back up config/Key/data together.

### Daily development

```sh
# Source root, dedicated platform PG already running:
./jdbc-test dev
```

PowerShell: `./jdbc-test.ps1 dev`. Reuse original `.env`, Key and data; no repeated `--new-install`. The Java build checks current modules each launch; dependencies are installed only when needed. While running, frontend edits use Vite HMR and require no Java rebuild. Server source changes need a restart; Worker/Suite changes are rebuilt on the next dev launch, so Server never silently uses an old external Worker. Ctrl+C stops both sides and only their owned descendants; a failed side also stops the other. PG/config/Key/data are retained. Port conflicts, failed builds and authentication errors abort without leaving a half-started environment.

### Advanced: IDEA Debug or manual source launch

The default does not require IDEA. For debugging with the **same `.env` and automatic Worker/proxy flow**, import root `pom.xml` in IDEA with JDK 21, run:

```sh
./jdbc-test dev --debug
```

Then create **Run → Edit Configurations → + → Remote JVM Debug**:

| Setting | Value |
|---|---|
| Project SDK / Maven importer | JDK 21 |
| Debugger mode | Attach to remote JVM |
| Host / port | `127.0.0.1` / `5005` (must be free) |
| Module sources | `jdbc-test-server` |
| Main source being debugged | `com.jdbctest.server.JdbcTestServerApplication` |

Click Debug to attach; do not start a second backend. The debug socket is loopback-only. IDEA attaching does not rebuild the external Worker; stop/relaunch dev after Worker/Suite changes. This runs a source main, not the packaged Server. **IDEA UI Run/Debug has not been exercised**; configuration is checked against code.

For a fully manual alternative, the old Maven/IDEA Application flow still works: build `./mvnw -DskipTests install`, supply Spring datasource/Key configuration **to that actual backend process**, set `JDBC_TEST_WORKER_EXECUTABLE` to the exact current `jdbc-test-worker/target/jdbc-test-worker-<POM-version>-all.jar`, and run `./mvnw -pl jdbc-test-server spring-boot:run`. IDEA Application uses JDK 21, main class above, `jdbc-test-server` runtime classpath and source-root working directory; no profile is required. Do not put secrets in Program arguments, VM options or shared run configurations. The unified command reads `.env`; **manual Maven/IDEA do not**. A terminal export does not update already-running IDEA. Choose one backend launch method, not both.

Manual frontend alternative: first `cd jdbc-test-web && npm ci`, then `npm run dev`; daily only `npm run dev`, reinstall after dependency/lockfile changes. Set nonsecret `JDBC_TEST_DEV_API_URL` in that Vite process if backend port differs (default proxy is localhost:8080). Stop each manual process in its own terminal. Files, Shell environment and IDEA environment are different sources; do not maintain duplicate secret files for the unified flow.

For literal dotenv rules, legacy properties/environment compatibility, build/runtime dependency split and validation limits see [Deployment](deployment.md#configuration-rules). No target probes/runs are part of startup acceptance.

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

## Java formatting

The unchanged source is [intellij-java-google-style.xml](../code-style/intellij-java-google-style.xml)
(SHA-256 `1f3578c03470362b9ad7c11cf9748308d3d362bcacbff4e04edd3b216565383f`).
[java.xml](../code-style/java.xml) projects its Java settings into an IDEA scheme; other languages
are excluded. It adds explicit reading rules: expand nonempty methods, blocks, lambdas and simple classes,
put declaration annotations on separate lines, use continuation indentation for record components,
wrap long resource lists/enum constants and local declaration annotations, and preserve
blank-line whitespace in text blocks.
Use 2-space indentation, 4-space continuation, no tabs and a 100-column target. Keep long
SQL/URL/literal exceptions intact. The source's forced control braces are retained. Imports use
static imports first, a blank line, then ordinary imports. Naming, exceptions, logging and security
continue to follow project conventions and the Alibaba guidelines.

In IDEA, import `code-style/java.xml` through Settings → Editor → Code Style → Java →
Scheme → Import Scheme → IntelliJ IDEA code style XML, then select `JdbcTestGoogleJava`.
Reformat only the four modules' Java source roots; use Optimize Imports separately with this scheme.
The Java-only `.editorconfig` mirrors indentation and margin settings.

Save edits and exit IDEA normally before using its native command-line formatter:

```sh
scripts/format_java.sh apply
scripts/format_java.sh check
# Optional: set IDEA_HOME to the installation containing bin/format.sh (macOS: app/Contents).
# Both commands also accept individual module/src/main/java/...java or src/test/java/...java paths.
```

Both commands use the same IDEA engine/XML; `check` is its non-writing `-d` mode.
Validated with IDEA 2026.2.3, build IU-262.10968.63. Its formatter does not optimize imports;
the current import blocks were separately reordered using Java compiler source positions without
adding/removing imports. Existing wildcard imports are retained. Do not substitute another engine,
bypass a running IDE's instance lock, or automatically rewrite sources during Maven builds.

## Web UI

Routes cover Dashboard, `/drivers`, `/connections`, `/runs`, creation forms and `/:id` details. The workflow is upload/validate driver → save/probe connection → review selection and authorized scope → consent/start → live results/report.

TanStack Query owns server state; forms and transport state remain local. `src/api/client.ts` handles fetch, JSON/multipart/204, cancellation and RFC Problem Details. Resource DTOs have no universal success envelope. TanStack Table handles tables; shared CPA-style components use Radix interactions, Sass visual tokens and Tailwind layout utilities; ECharts renders outcomes. There is no production mock API.

Run detail loads REST first, then opens native `EventSource` for active runs. SSE updates the same Query cache; terminal events close the stream and refresh final results. Reconnects do not change Run status; a 10-second REST fallback covers missed terminal events. Passwords stay in component memory, clear after requests, and never enter browser storage, URLs or query caches. Diagnostics render as text.

Dashboard outcomes describe only the eight loaded recent runs; catalog totals describe inventory. There are no global trend, module-aggregate or safe diagnostics endpoints. Run lists filter by status. Results support status or module filtering, with status taking precedence. The UI does not invent unavailable statistics or a Logs tab.

### Language and metric cards

The top-right menu switches between bundled English (`en`) and Simplified Chinese (`zh-CN`) without reloading. A valid `jdbc-test.locale` preference takes precedence over supported browser languages; otherwise English is the fallback. Blocked storage is tolerated. Dates retain browser-local timezone semantics. Stable IDs, API values, cached data and raw reports remain untranslated.

Metric cards share CSS tone tokens and pale gradients; outcome colors retain their existing meanings. Completed includes explicit skips and is not a compatibility pass rate. Results exposes skipped `message` details as original sanitized evidence; the current DTO has no separate structured skip-source/capability fields. Technical scenario names and unknown module names remain backend-provided.

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

For browser checks against the freshly built assets, use `JDBC_TEST_E2E_PREVIEW=true PLAYWRIGHT_CHANNEL=chrome npm --prefix jdbc-test-web run test:e2e` after building. This runs the mocked project only, captures explicitly labelled fixture screenshots, and verifies the loaded bundle SHA-256 against `dist`. It does not start QA or execute target database operations.


## Shared frontend UI

The white CPA Keeper UI foundation is pinned to upstream [`2c09d65d6a90b1dc9802fe7b26704a1d95d0c909`](https://github.com/Willxup/cpa-usage-keeper/tree/2c09d65d6a90b1dc9802fe7b26704a1d95d0c909), MIT © 2026 Will. The full notice and source/adaptation list are shipped in `jdbc-test-web/public/third-party-notices.txt`.

- Direct port: `web/src/styles/menu.scss` → `src/styles/upstream/_menu.scss`.
- Adaptations: upstream white-theme tokens, Button/Input/Card/Select/Modal/EmptyState presentation, header and DashboardToolbar geometry → `src/styles/foundation.scss` and existing shared components. Namespaced styles, blue primary actions, existing Radix interactions, semantic tables and React Router navigation replace upstream-specific interactions and business dependencies.
- Local additions: JDBC URL view/copy, stable-ID Run titles and grouped snapshots, Metric and execution semantics, Tooltip using Radix. No CPA account/token/cost/auth state, Chart.js or Zustand is imported.

Use `components/ui` for controls, Card and dialogs, `components/common` for metrics/loading/empty/errors, and `components/data-table` for tables/pagination. Select consumes option values but renders a portalled Radix listbox; do not introduce native selects or page-specific menu skins. Tailwind handles layout; `foundation.scss` is the authority for visual tokens/control surfaces, with `index.css` mapping those tokens to Tailwind and defining metric/selection semantics. Sass is pinned to 1.99.0. Both language resources remain bundled locally.

`npm run lint`, `npm run test`, `npm run build` and the mocked `npm run test:e2e` verify UI behavior. The explicitly selected `history-readonly` Playwright project only reads preserved history; it never creates runs or probes connections. It requires an already verified viewer backend, `JDBC_TEST_HISTORY_READONLY=true`, and the current built preview. Standard `real` E2E still requires a separate writable acceptance database and must not be run against historical QA.

The test-only nested-menu/tooltip/toast fixture runs with `PLAYWRIGHT_CHANNEL=chrome npx playwright test --project=primitives` (Vite dev, not preview); its HTML entry is outside the production router/build.
