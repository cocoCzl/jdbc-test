# Phase 9 Verification — October 1, 2026

## Verdict: PASS — Docker production acceptance complete

Phase 8 was formally accepted and Phase 9 authorized. Packaging, static hosting,
health, persistence and shutdown acceptance are complete. The runtime image uses
the pinned, arm64/amd64 Red Hat OpenJDK 21 runtime image
`registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24`; no unofficial mirror
or substitute runtime was used. Phase 10 has not started. No releases, git tags,
registry publishing or authentication were added.

Evidence below deliberately separates real tests from configurations and from
the explicitly non-Docker production-profile browser acceptance.

## Regression results (actual commands)

| Command | Passed | Failed/errors | Skipped |
|---|---:|---:|---:|
| `./mvnw clean verify` (final source, rerun) | 114 | 0 | 0 |
| `./mvnw -Pserver-integration verify` | 123 | 0 | 0 |
| `./mvnw -Pworker-e2e verify` | 116 | 0 | 0 |
| `npm run test` | 75 | 0 | 0 |
| Python contract suite | 30 | 0 | 0 |
| Real Playwright, :8080 executable Server, **not Docker app** | 1 | 0 | 0 |
| Mocked Playwright, development Vite flow | 2 | 0 | 0 |
| Production Docker Playwright, `:8080` | 1 | 0 | 0 |

`npm ci`, `npm run lint`, `npm run build` all succeeded; npm audit reported zero
vulnerabilities. The real browser test used installed Chrome (`PLAYWRIGHT_CHANNEL=chrome`),
trace off, no HAR, no API mocks. No real test was skipped or replaced with mocks.
The existing package's ESLint 9 support warning was emitted; dependencies were
not broadened/refactored during packaging.

The 18 new Java invocations are 16 static-hosting cases and two deterministic
shutdown cases. They are included in the final totals, not extra scenario cases.
Three red/green behaviors were proven: direct SPA route was initially 404;
active Worker shutdown initially yielded FAILED rather than INTERRUPTED; and a
completed run winning the shutdown race was overwritten as INTERRUPTED from a
stale managed entity. Shutdown queries only candidate IDs before locking/loading
current rows, preserving the completion winner. All three focused checks passed.

Binding Boot repackage to the main artifact initially broke Failsafe class
discovery (nested jars are not ordinary JVM classpath jars). The final build
retains the ordinary Server jar and attaches `-exec.jar`; subsequent Server IT
and Worker E2E verify both succeeded. Earlier expected-red/diagnostic failures
are not concealed as final passes.

## Docker evidence

| Check | Actual result |
|---|---|
| `docker compose config --quiet` | PASS with private required env file |
| `docker build --target web` | PASS, native arm64, `npm ci` + Vite build |
| `docker build --target java-build` | PASS, native arm64, Wrapper package, both jars |
| Complete `docker compose build` | PASS, native arm64 |
| `docker compose up -d` | PASS, app and PostgreSQL healthy |
| `docker compose ps` | PASS, both services healthy; PostgreSQL has no host port |
| `scripts/docker-smoke.sh` | PASS: health, catalog 202, SPA/API exclusions, hashed asset |
| Production Docker Playwright | PASS: Driver, probe, Statement23, Connection17, SSE, report, history, refresh |
| App `restart` persistence | PASS: Driver artifacts, saved password probe, Runs, Results, Reports |
| Full `down` then `up -d` persistence | PASS; no repeated Flyway migration |
| Fresh `down -v` install | PASS: Flyway V1 → V2 → V3 → V4, empty API state, healthy app |
| Active Worker container stop | PASS: RUNNING → INTERRUPTED/SERVER_SHUTDOWN; no child Worker remains |

The deployment uses only services `jdbc-test` and `postgres`, internal DB port,
loopback-bound Web port, named volumes, non-root UID/GID 10001, init, exec-form
Java entrypoint and healthchecks. Runtime inspection confirmed UID/GID 10001,
read-only application jars/assets, writable data directories, standard child-JVM
Java availability, and absence of Maven/Node in the runtime image.

Official JDK 21 and the pinned Red Hat OpenJDK 21 runtime downloaded successfully.
No Docker Desktop global proxy/daemon configuration was changed.

## Real Docker production acceptance

The complete Compose deployment was tested against a disposable PostgreSQL 16
Target container attached to the Compose network. The external uploaded
PostgreSQL JDBC Driver was 42.7.5; the Server's Platform PostgreSQL dependency
remained 42.7.11. The Worker loaded the uploaded artifact in its child JVM.

- `docker compose up -d --build` served React, REST and SSE from `:8080`; no Vite
  server was involved.
- Direct routes, refreshes, real hashed assets, `/api/unknown` 404 and missing
  asset 404 were verified. `/actuator/health` returned UP without target-DB
  dependence or secret/path details.
- Production Playwright passed one real test: upload/validate, saved-password
  probe, CUSTOM Statement 23/23, CUSTOM Connection 17/17, native EventSource
  events and terminal close, results, report JSON/UI, history and route refresh.
- App restart and full Compose down/up preserved Driver metadata/artifacts,
  saved credentials, Run history, Results and report hashes.
- Pausing the Target while a CUSTOM Connection run was RUNNING, then stopping
  the app container, produced `INTERRUPTED` / `SERVER_SHUTDOWN`; after restart
  no Worker child process remained. The dedicated Java shutdown tests also
  verify temporary credentials are purged.
- A dedicated `down -v` fresh install applied V1 through V4 and returned empty
  Driver/Run collections after startup.

## Real :8080 static-hosting supplemental acceptance

The executable `jdbc-test-server-1.2.1-SNAPSHOT-exec.jar` ran on the host with
`docker` profile and explicit host static/data/Worker path overrides. Disposable
Platform and Target PostgreSQL 16 containers supplied actual databases. This is
**real full-stack, same-origin production-profile testing, not production Docker
app/container testing**. No Vite server ran.

- Fresh Flyway V1 → V2 → V3 → V4 applied before Hibernate validate; restart
  validated migrations without rerunning destructive schema creation.
- Dashboard, Drivers/Connections/Runs/New Run direct navigation and refresh,
  a real Driver/Connection/Run UUID route refresh and static hashed JS worked.
  Unknown React paths returned the shell; `/api/unknown`, `/actuator/unknown`
  and missing asset returned backend 404, not React index.
- Browser uploaded the actual external PostgreSQL 42.7.5 JAR and validated it;
  draft probe succeeded and saved password was used for the runs.
- CUSTOM Statement **23/23**, CUSTOM Connection **17/17**, SUCCEEDED; real
  results, raw report JSON, rendered report and history verified.
- Actual EventSource named events were observed, responses were
  `text/event-stream`, sources closed at terminal state and no uncaught page
  errors occurred. Production API is same-origin, no localhost build constant.
- Saved credentials, Driver validation, case results and immutable report hashes
  survived actual Server SIGTERM/restarts using the same master key. Temporary
  data is host `/tmp/jdbc-phase9/data`, **not `jdbc_test_data` Docker volume**.
- Target DB stopped temporarily while app health remained UP, proving it is not
  a platform health dependency. Platform health exposes status/availability
  group names only, not secrets or paths. Actual Server logs were scanned for
  the generated Platform password, Target password and master key: no matches.
- Browser storage remained empty. Passwords were write-only request/local state;
  reports/response tests did not echo them.

Target probe reported **42.7.5**, different from actual Server runtime Platform
dependency **42.7.11**. Driver ID/fingerprint matched the uploaded package on
both runs. Worker is a separate JVM using `${java.home}/bin/java`; no Server
ClassLoader loading of uploaded Driver was introduced.

## Real shutdown supplemental evidence

A disposable Target DB event trigger delayed CREATE TABLE so the actual Worker
was confirmed RUNNING and blocked in Target DDL. A second Run was QUEUED.
SIGTERM was then sent to the host Server running the production profile:

- Running and queued rows became INTERRUPTED / SERVER_SHUTDOWN.
- Both Server and Worker JVMs exited; temporary credential row count was zero.
- Restart preserved INTERRUPTED with no auto-resume; old captured reports/results
  and saved-credential probe remained valid.
- Test trigger/function were removed; no suite scenarios or production SQL assets
  were altered. Docker PID 1 / container stop behavior remains a separate pending
  acceptance item, not implied by this host-process test.

## Existing unrelated limitation discovered

An unfiltered Run page returned six rows (four SUCCEEDED, two INTERRUPTED), while
`GET /api/runs?status=SUCCEEDED` returned total 0. Existing Query-by-Example
construction also includes primitive zero-valued counters. This remains the
historical Phase 9 observation. Phase 10 fixes it with an explicit status query
and PostgreSQL/React regressions; the original observation is retained unchanged
as historical evidence.

No production secrets or build artifacts were added to Git. Private acceptance
env/log/data/checkpoint files live under `/tmp/jdbc-phase9` with restricted env
permissions. Phase 8 stopped containers/data were not removed. Deployment,
backup/master-key recovery and unauthenticated/shared-container security limits
are documented in `deployment.md`.
Temporary host Servers and Phase 9 containers were stopped after verification;
the Compose project was taken down without `-v`, preserving its named volumes.

Catalog contract remains **202 total / 136 Core / 202 Full / 17 modules /
Connection17 / Statement23**, with no test inventory, schema or resource DTO change.
