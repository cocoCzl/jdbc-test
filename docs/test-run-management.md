# Test Run Management

Current execution scope, ownership and protocol v2 accounting: [Execution safety](execution-safety.md). New runs require explicit scope; older protocol examples below describe the baseline.

## Lifecycle

`POST /api/runs` creates a `QUEUED` resource and returns `201 Created`. The local FIFO dispatcher runs no more than `jdbc-test.execution.max-concurrent-runs` Workers. Normal states are `QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED`; failures, cancellation and startup recovery end in `FAILED`, `CANCELLED` or `INTERRUPTED`.

`destructiveConsent` must be true. The Server does not perform a new driver validation or connection probe before a run.

## Selection and snapshots

The Worker describes the immutable catalog once with `--describe-catalog`; the Server caches it and never loads suite classes. `CORE` resolves 136 scenarios, `FULL` resolves 202, and `CUSTOM` resolves selected modules/scenarios from the full catalog. Every resolved scenario is stored in `test_run_selected_scenario`, together with connection, adapter, driver and sanitized URL snapshots.

## Credential boundary

The request password takes precedence over the Connection saved credential. The resolved credential is AES-256-GCM encrypted in `test_run_credential` only while queued, decrypted only for Worker stdin, then deleted. It is absent from run snapshots, events, reports, argv and diagnostics.

## Worker, results and reports

The Server starts the configured Worker executable with a `<data-dir>/runs/<run-id>` workspace, sends Server-generated driver artifact paths and configuration through stdin, and consumes stdout NDJSON line by line. Stderr is drained into a bounded, line-sanitized diagnostics file. Each `CASE_FINISHED` event commits a short transaction including sanitized message and stack trace. Unknown run IDs, unselected/duplicate scenarios and unknown events fail with `WORKER_PROTOCOL_ERROR`.

Before success, the Server compares persisted case counts/statuses, `RUN_FINISHED` scenario count/status and `report.json` summary/result entries. Missing, malformed or inconsistent reports fail with `REPORT_INCONSISTENT`. A consistent report with FAILED/ABORTED cases remains available, but the Run is FAILED with `WORKER_EXECUTION_FAILED`; a zero exit code alone does not imply success. JSON reports are sanitized structurally, never by replacing across JSON syntax.

`ABORTED` is persisted unchanged. `completedCases` counts all four statuses; `passed`, `failed` and `skipped` count only their corresponding statuses. With no `abortedCount` column, aborted count is `completedCases - passed - failed - skipped`. Both FAILED and ABORTED make the Run fail.

Cancel and Worker state/result updates lock the Run row. Cancel commits CANCELLED before terminating the process; late events cannot overwrite any terminal state. Processes receive graceful termination, then forced termination with an exit wait when needed. A timeout is FAILED / RUN_TIMEOUT, not CANCELLED. The scheduler retains its slot until process cleanup completes.

## REST API

`GET /api/test-catalog`, `POST /api/runs`, `GET /api/runs`, `GET /api/runs/{id}`, `POST /api/runs/{id}/cancel`, `GET /api/runs/{id}/events`, `GET /api/runs/{id}/results`, `GET /api/runs/{id}/report`, and `GET /api/runs/{id}/report.json` are available. Run listing supports the optional `status` filter; it is applied by the repository query `where run.status = :status`, so content and count pagination use the same condition. List and result endpoints use bounded pagination and sort allowlists. SSE sends an initial `run.snapshot`, then stable run/module/case/progress/terminal events.

Server Testcontainers integration classes use a JUnit execution condition. When Docker is unavailable they are reported as skipped rather than failing Spring context initialization from a `DynamicPropertySource` assumption.

## Phase 7 deterministic verification

`DeterministicOrchestrationTest` uses test-scoped H2 with real JPA transactions and database row counts; production remains PostgreSQL/Flyway. It exercises the production scheduler, executor, REST and SSE with a test-only executable JAR. No production Worker fake mode is added. File signals gate process start/continuation/completion/exit; a process-wide file lock detects overlap at concurrency=1, servlet callbacks simulate disconnects, and injected emitter IO failures verify isolation. Cancel/completion contenders use a CountDownLatch barrier.

Run `./mvnw clean verify` without Docker for deterministic verification. `./mvnw -Pserver-integration verify` additionally exercises real PostgreSQL platform and target containers, full Connection/Statement module selections and a real HTTP SSE client. A test-only identity driver delegates to the external PostgreSQL driver and asserts its URLClassLoader, code source, artifact SHA-256 and Worker PID. Its identity marker stays private to the test storage and is not published through REST/SSE. Docker-unavailable tests skip; Docker-available container startup errors remain test failures in the new Run E2E.
