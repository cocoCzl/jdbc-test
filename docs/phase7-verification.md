# Phase 7 Deterministic Orchestration Verification

Date: 2026-10-01. Verdict: **PASS**. Phase 8 is not started.

The previously accepted Controller audit and HTTP 500 investigation are closed and are not repeated here.

## 1. Summary

- 55 deterministic tests exercise real scheduler/executor processes, real JPA persistence, REST and SSE without Docker.
- All seven requested validation commands complete successfully. Maven totals are 96, 105 and 98 passing tests respectively; Python has 30 passing tests.
- Real PostgreSQL Connection and Statement module runs select and pass 17 and 23 scenarios respectively.
- Docker-available integration tests execute; a sandbox socket-denial simulation proves the unavailable branch skips and builds successfully.

## 2. Additional bugs found and fixed

- Cancel previously terminated the Worker before committing CANCELLED, permitting unexpected-exit/completion handling to win incorrectly. Cancel now commits the terminal state first; competing state/result writes lock the Run row.
- Completion previously could publish success even when a transition was rejected, and failure paths could overwrite the original reason. Terminal transitions are immutable and publish only when accepted.
- Report checks previously verified only file existence and result count. They now compare selected/persisted counts, RUN_FINISHED count/status and report summary/result status/module identities.
- A PASSED-looking Worker exit with FAILED/ABORTED outcomes cannot succeed. Consistent failed reports remain available as FAILED / WORKER_EXECUTION_FAILED.
- Result persistence dropped stack traces. Sanitized stack traces and messages are now persisted.
- Byte-chunk diagnostics sanitization could split secret labels/values. Diagnostics are sanitized by line, before bounded output.
- Raw-text report sanitization could consume JSON delimiters. JSON is sanitized structurally, preserving arrays, scalar types and nulls; a dedicated regression checks validity and idempotence.
- Failed emitter sends could leave stale registrations until a servlet callback. Send failures remove emitters immediately; registration/removal updates are atomic per Run. Terminal subscriptions complete after their snapshot, with subscription serialized against terminal updates.
- Startup recovery missed the legacy PROBING state. It is now interrupted along with other nonterminal states.
- Standalone Worker PostgreSQL E2E used an obsolete request without adapterId, resolving SQL assets under `worker` and reporting unexecuted scenarios. Its request now supplies `postgresql`; the JDBC selection and assertions are not weakened. The report additionally asserts both selected scenarios PASSED.

## 3. Files added

- `jdbc-test-server/src/test/java/com/jdbctest/server/run/DeterministicOrchestrationTest.java`
- `jdbc-test-server/src/test/java/com/jdbctest/server/run/ExternalIdentityPostgresDriver.java`
- `jdbc-test-server/src/test/java/com/jdbctest/server/run/RunPostgresIT.java`
- `docs/phase7-verification.md`

## 4. Files changed

- `jdbc-test-server/pom.xml`: test-scoped H2.
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunExecutor.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunScheduler.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunRepository.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunService.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunController.java`: SSE/report handling only; no repeated parameter audit.
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/TestRunStartupRecovery.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/run/RunEventPublisher.java`
- `jdbc-test-server/src/main/java/com/jdbctest/server/security/JdbcUrlSanitizer.java`
- `jdbc-test-server/src/test/java/com/jdbctest/server/security/JdbcUrlSanitizerTest.java`
- `jdbc-test-server/src/test/java/com/jdbctest/server/run/FakeWorkerMain.java`
- `jdbc-test-server/src/test/java/com/jdbctest/server/run/FakeWorkerJar.java`
- `jdbc-test-worker/src/test/java/com/jdbctest/worker/WorkerPostgresIT.java`
- `docs/test-run-management.md`
- `docs/worker-protocol.md`
- `docs/migration-notes.md`
- `REFACTOR_PLAN_OPTIMIZED.md`: Phase 7 completion/evidence only; Phase 8 remains unchecked.

## 5. Deterministic fake Worker

Required modes: NORMAL, BLOCK, FAILED_RUN, INVALID_JSON, WRONG_RUN_ID, EXIT_NON_ZERO, REPORT_MISMATCH, DELAY_FINISH.

Additional modes: BLOCK_STUBBORN, UNKNOWN_EVENT, ERROR_SECRET, MIXED, FINISH_MISMATCH, CASE_COUNT_MISMATCH, REPORT_INVALID.

The fixture is packaged from test classes into a standalone test JAR. Its mode uses a test request username; production Worker code has no fake behavior. The fixture catalog is derived from the versioned inventory and suite source metadata.

File signals control started/proceed/release/finish-ready/finish/finish-emitted/exit boundaries. WatchService synchronization has bounded test-side deadlines. CountDownLatch coordinates cancel/completion contenders and real SSE subscription before execution. A process-lifetime file lock detects overlapping fake Run Workers. BLOCK_STUBBORN installs a blocked shutdown hook to require forced termination. No Thread.sleep is added.

## 6. Queue verification

- With maxConcurrentRuns=1: A RUNNING/B QUEUED; B has no started marker until A finishes.
- FIFO is checked via start/exit markers; A's PID is dead before B starts. The shared process lock detects overlap throughout execution.
- Slot/credential matrix: success, failed outcomes, cancel, timeout, invalid JSON, wrong runId, unexpected exit, unknown event, report summary mismatch, finish summary mismatch, case-count mismatch and invalid report JSON.
- Every matrix entry starts and completes a following NORMAL Run. Worker startup failure also releases its slot.

## 7. Cancel verification

- Queued B becomes CANCELLED, loses its credential, sends run.cancelled and never starts; A remains RUNNING and later C succeeds.
- Running BLOCK_STUBBORN is genuinely terminated, including forced termination; finishedAt and credential cleanup are asserted. Following B succeeds and late Worker exit cannot replace CANCELLED or emit failed/finished.
- Twelve barrier repetitions alternate cancellation competing with emission of RUN_FINISHED, and cancellation competing with process exit after RUN_FINISHED was emitted. Only SUCCEEDED/CANCELLED is allowed, with exactly one terminal SSE event and no later state reversal. Late cancel against SUCCEEDED is also rejected.

## 8. Timeout verification

Test timeout is five seconds, versus the production default of thirty minutes. BLOCK_STUBBORN is killed; the Run is FAILED / RUN_TIMEOUT, not CANCELLED. finishedAt, credential deletion, process death and automatic following-Run execution are asserted. Normal test execution uses a longer timeout to avoid file-watcher latency affecting the successor.

## 9. Protocol / exit verification

- `not-json`, wrong runId and unknown type produce WORKER_PROTOCOL_ERROR and do not crash the scheduler.
- Wrong-run events cannot write results or module events into the current Run.
- Exit 7 without RUN_FINISHED produces WORKER_EXITED_UNEXPECTEDLY.
- All paths clean credentials/processes, release their slot and allow the next Run to succeed.

## 10. Report consistency

Report summary, finish summary, persisted case count and report entries must agree. Missing/malformed JSON, wrong counts or result identities cannot produce SUCCEEDED. Regressions assert FAILED / REPORT_INCONSISTENT.

## 11. Result persistence

PASSED, FAILED, SKIPPED and ABORTED each persist scenarioId, moduleId, displayName, durationMs=42, sanitized message and sanitized stackTrace. Aggregate assertions are completedCases=4, passed=1, failed=1, skipped=1.

ABORTED remains ABORTED, not SKIPPED or FAILED. There is no abortedCount column: aborted count is `completedCases - passed - failed - skipped`. Both FAILED and ABORTED make a completed Run fail. This rule is fixed by regression and documented.

## 12. SSE verification

- Immediate snapshot, run.status, module.started, case.started, case.finished, run.progress and terminal event.
- Terminal completion, including a new subscription to an already terminal Run.
- Two subscribers receive events; servlet error/disconnect removes one without affecting the survivor.
- Injected send IOException removes only the broken emitter and does not alter execution.
- No subscribers: execution still succeeds and no emitter registration remains.
- Payloads have no credential/storage/workspace fields or workspace paths; secret diagnostic values are redacted.
- Real HTTP SSE streams for both PostgreSQL module runs receive snapshot/status/case/progress/terminal events and reach EOF on completion.

## 13. Startup recovery

Preseeded QUEUED, PREPARING, RUNNING and GENERATING_REPORT become INTERRUPTED / SERVER_RESTART with finishedAt and no credentials. Legacy PROBING is also covered. SUCCEEDED, FAILED, CANCELLED and INTERRUPTED retain status, reason and finishedAt, and are not rewritten.

## 14. Credential cleanup matrix

Asserted absent after: SUCCEEDED, FAILED, queued cancel, running cancel, timeout, protocol error, wrong runId, unexpected exit, report inconsistency, Worker startup failure and server restart/interruption. The parameterized matrix shares the verification logic rather than duplicating it per mode.

## 15. Selection snapshot

Actual SQL row counts in test_run_selected_scenario: CORE=136, FULL=202, CUSTOM connection=17, CUSTOM statement=23. Custom selections contain only their selected module. SQL counts and repository rows are both asserted; these are not catalog-only assertions.

The Docker-free cases use test-scoped H2 and real JPA transactions. Real PostgreSQL E2E separately asserts SQL row counts for Connection=17 and Statement=23. The full inventory contains 202 scenarios across 17 modules; CORE selects 136 across 10 of those modules.

## 16. Snapshot reproducibility

After mutating current Connection/Driver metadata, GET Run detail still returns Connection A, Driver D1, version V1 and fingerprint F1. The assertion goes through REST against persisted snapshots.

## 17. Sanitization

Fake messages/stacks/reasons contain `password=fake-secret`, `token=fake-token` and a JDBC URL containing the fake password. Persisted failure reason/results, REST detail/results/report/report.json, SSE and Worker diagnostics contain neither original secret. Error-secret SSE and REST detail are checked directly. Structured report redaction preserves valid JSON, numbers, arrays, nulls and idempotence.

## 18. Docker / Testcontainers status

Docker Desktop engine 29.8.0 is available. Real Server/Worker integrations execute with zero skips. The supplementary combined integration run passes 11/11.

A non-escalated sandbox run denies Docker sockets, simulating Docker unavailable without stopping the user's engine. The same combined profiles produce Tests run=10, Passed=0, Failed=0, Errors=0, Skipped=10 and BUILD SUCCESS. JUnit reports the disabled parameterized RunPostgresIT as one skipped method, versus two invocations when enabled; this explains 10 versus 11.

## 19. Real PostgreSQL Run E2E

Two separate containers isolate the platform PostgreSQL database from the target database. The actual path is REST POST → platform persistence → scheduler → Worker executable → uploaded external PostgreSQL JDBC Driver → URLClassLoader → target PostgreSQL → JUnit → NDJSON → result persistence → real HTTP SSE → report → GET detail 200.

| Module | Selected | Passed | Failed | Skipped | Run |
| --- | ---: | ---: | ---: | ---: | --- |
| connection | 17 | 17 | 0 | 0 | SUCCEEDED |
| statement | 23 | 23 | 0 | 0 | SUCCEEDED |

GET detail, results, report and report.json all return 200. No scenario semantics are changed to obtain these results.

The test-only identity driver delegates to org.postgresql.Driver and records evidence only on real connect. Assertions prove its URLClassLoader parent is the platform loader, its code source is the uploaded PostgreSQL artifact in DriverStorage rather than the Server's Maven artifact, its SHA-256 matches the stored upload, and its PID differs from the Server JVM. Identity evidence is private test storage, not SSE/REST payload.

The requested Server integration command produced Connection Run `98e09018-fd9e-488a-a1f9-ab933d91f46e` and Statement Run `0794b2d6-f509-4607-89ab-0f18df283e66`. The supplementary combined run again passed both modules.

## 20. Test commands and results

Maven totals count unit tests plus only the integration tests executed by that profile, not stale reports from another command. Failed below means test failures; Errors are zero throughout the final runs. The earlier Worker E2E failure due to its missing adapterId is fixed and rerun successfully.

| Exact command | Tests run | Passed | Failed | Skipped | Outcome |
| --- | ---: | ---: | ---: | ---: | --- |
| `./mvnw clean verify` | 96 | 96 | 0 | 0 | BUILD SUCCESS |
| `./mvnw -Pserver-integration verify` | 105 | 105 | 0 | 0 | BUILD SUCCESS; 96 unit + 9 integration |
| `./mvnw -Pworker-e2e verify` | 98 | 98 | 0 | 0 | BUILD SUCCESS; 96 unit + 2 integration |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | 30 | 30 | 0 | 0 | OK |
| `python3 scripts/runner.py adapters` | N/A | N/A | N/A | N/A | Success; five adapters |
| `python3 scripts/runner.py coverage --profile core --format json` | N/A | N/A | N/A | N/A | Success; 136 scenarios |
| `python3 scripts/runner.py coverage --profile full --format json` | N/A | N/A | N/A | N/A | Success; 202 scenarios, 17 modules, connection 17, statement 23 |
| `./mvnw -Pserver-integration,worker-e2e -Dtest=NoUnitTests -Dfailsafe.failIfNoSpecifiedTests=false verify` (Docker available) | 11 | 11 | 0 | 0 | BUILD SUCCESS |
| `./mvnw -Pserver-integration,worker-e2e -Dtest=NoUnitTests -Dfailsafe.failIfNoSpecifiedTests=false verify` (sandbox Docker unavailable simulation) | 10 | 0 | 0 | 10 | BUILD SUCCESS |

The final clean verify, after tightening the before/after RUN_FINISHED race and error-secret transport checks, again passes all 55 deterministic cases and all 96 unit tests.

Local logs: `/tmp/phase7-clean-verify.log`, `/tmp/phase7-server-integration.log`, `/tmp/phase7-worker-e2e.log`, `/tmp/phase7-combined-docker.log`, `/tmp/phase7-no-docker.log`, `/tmp/phase7-python-tests.log`, `/tmp/phase7-adapters.log`, `/tmp/phase7-core-coverage.json`, `/tmp/phase7-full-coverage.json`. Per-command positive totals are saved separately in `/tmp/phase7-*-test-counts.json`; the final clean deliberately rebuilds target directories.

## 21. Compatibility

No suite scenario semantics, production Worker fake support, REST routes, result status taxonomy or production schema changes are introduced. Existing Python adapter/report checks pass. ABORTED semantics and v1 report summary keys are retained. Standalone Worker Run callers must provide adapterId; examples and E2E now do so. H2 never replaces production PostgreSQL.

All nine existing Run statuses, including the extra legacy PROBING state, receive detail 200 even with nullable startedAt, finishedAt, currentModule, currentTestCase and failureReason. This is a new all-status regression, not a repeated Controller audit.

## 22. Security verification

Credentials are encrypted while queued, sent via stdin rather than argv, and deleted on every covered terminal/startup failure/recovery path. Persisted diagnostics are redacted. SSE contains no credential/storage/workspace fields or paths. Uploaded target drivers execute in independent Worker JVMs; code-source/hash/classloader/PID assertions prove this boundary. No association is changed to EAGER and no catch Exception is introduced.

## 23. Remaining known issues

No unresolved Phase 7 verification blocker remains. Independent JVM execution is not an OS/container sandbox; that is an existing MVP boundary, not a guarantee added by these tests. Direct Worker callers must supply the matching adapterId for SQL assets. No Phase 8 changes, branch, commit or unrelated migration cleanup is performed.

## 24. Phase 7 final verdict

**PASS.** The requested Phase 7 deterministic verification, actual PostgreSQL module/SSE/driver-identity E2E, cleanup/security checks and final commands are complete. The verification gate for entering Phase 8 is satisfied, but this work remains in Phase 7 and does not start Phase 8.
