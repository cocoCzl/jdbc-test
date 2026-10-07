# Phase 10 Verification — October 1–2, 2026

## Verdict

**PASS** — the confirmed Run status filtering bug is fixed, regression-tested,
documentation is aligned with the current implementation, and compatibility
checks completed. This is a maintenance and handoff checkpoint. It does not
authorize version changes, release publication, image publication, tags, or pushes.

## Run status filtering

The Phase 9 observation was reproduced from the persisted data shape: successful
runs had non-zero `completed_cases` and `passed` values, while the old
`Example.of(statusExample(status))` probe also treated primitive zero-valued
entity fields as probe properties. The resulting QBE predicate could therefore
require `total_cases = 0`, `completed_cases = 0`, `passed = 0`, `failed = 0`,
and `skipped = 0` in addition to `status = 'SUCCEEDED'`. The count query used the same unintended
probe, which produced `totalElements = 0` for matching successful runs.

The fix is deliberately local:

```java
@Query("select run from TestRunEntity run where run.status = :status")
Page<TestRunEntity> findByStatus(TestRunStatus status, Pageable pageable);
```

`TestRunService.list` uses this method when `status` is present and retains
`findAll(pageable)` for the unfiltered case. Hibernate produces the equivalent
content query:

```sql
select ... from test_run where status = ? order by created_at asc limit ? offset ?
```

and the matching count query:

```sql
select count(*) from test_run where status = ?
```

No in-memory filtering or DTO total override was added.

The real PostgreSQL regression fixture creates three `SUCCEEDED` rows with
non-zero counters, two `FAILED` rows with different counters, and one each of
`RUNNING`, `QUEUED`, `CANCELLED`, and `INTERRUPTED`.

- Unfiltered total equals the pre-fixture database count plus 9.
- `status=SUCCEEDED` adds exactly three matches to an independently SQL-counted
  pre-fixture baseline; `size=2` verifies first, middle/last and beyond-last pages.
- `status=FAILED` adds exactly two matches to its independent SQL baseline.
- `GENERATING_REPORT` returns its unchanged SQL baseline, including a zero-result
  page on the fresh focused invocation.
- Invalid status, page, size, and sort each return HTTP 400.

The regression is in `RunPostgresIT.statusFilteringUsesOnlyStatusAndKeepsPaginationTotals`.
The frontend regression verifies the request status parameter, page reset to zero,
and server-provided total display. The production Playwright flow also switches
the real Run History page to `SUCCEEDED` and verifies the filtered response.

## Compatibility and documentation

Driver, Connection, Run, Worker, SSE, Report, Docker same-origin hosting, and
the CLI report pipeline remain compatible. The repository still has:

- 202 catalog scenarios
- 136 Core scenarios
- 202 Full scenarios
- 17 modules
- Connection 17
- Statement 23

The current implementation does not provide a Dashboard aggregate endpoint,
per-module report aggregate endpoint, or safe diagnostics REST endpoint. The
production image does not include user Target Drivers. `Dockerfile.cli` remains
the legacy CLI image; `Dockerfile` and `docker-compose.yml` are the production Web
deployment. No Spring Boot starter module was added.

## Validation boundary

The PostgreSQL regression uses Testcontainers-created Platform and Target
databases. Docker Compose acceptance uses its own QA project, configuration,
secrets, ports, and named volumes. No existing user deployment or persistent
volume was used or deleted. Temporary QA artifacts and secrets remain outside the
repository under `/tmp` during validation and were removed after acceptance.

Historical Phase 7, Phase 8, and Phase 9 records retain the facts and test counts
from those phases. This document records the Phase 10 fix and final checks; it
does not claim that every database vendor has passed all 202 scenarios.

## Final validation

- `./mvnw clean verify`: 114 passed, 0 failed, 0 errors, 0 skipped.
- `./mvnw -Pserver-integration verify`: 124 passed in total; the PostgreSQL
  integration set is 10 passed, 0 failed, 0 errors, 0 skipped. Real external
  Driver Runs remained Connection 17/17 and Statement 23/23.
- `./mvnw -Pworker-e2e verify`: 116 passed, 0 failed, 0 errors, 0 skipped;
  both Worker PostgreSQL integration tests executed.
- Python unittest discovery: 30 passed. Adapter listing returned five adapters;
  coverage returned Core 136 and Full 202.
- Frontend: ESLint passed; Vitest/RTL 76 passed across seven files; Vite build
  passed; mocked Playwright 2 passed; production Docker Playwright 1 passed.
- Production Docker smoke, same-origin SSE, persistence after app restart, and
  persistence after full Compose down/up passed in project `jdbc-phase10-final`.

The final Phase 10 Compose project used host port 18081, its own Platform and Target
PostgreSQL containers, separate secrets, network, and named volumes. Cleanup
removed only resources with that verified QA ownership. Existing Phase 8/9 and
user deployment resources were not modified or deleted.

## Packaging and toolchain notes

The Worker Shade configuration now merges dependency `LICENSE`/`NOTICE` resources
instead of discarding them and includes `META-INF/THIRD-PARTY-NOTICES.txt` as an
offline index. The resulting Worker remains source-tree independent and contains
suite classes/resources, but no target JDBC Driver or test fixture.

The compatibility audit also found that the floating `Dockerfile.cli` base had
advanced to Ubuntu Resolute and distro Maven installation pulled OpenJDK 25.
The CLI image now pins `eclipse-temurin:21-jdk-noble`, installs only Python and
locale support, and uses the repository's Maven Wrapper 3.9.15. Its arm64 build
and container checks passed with Temurin 21.0.12.1, Python 3.12.3, Maven Wrapper
3.9.15, no distro Maven executable, and all five adapter entries.

Driver and Connection lists use direct repository pagination without implicit
probes. Result queries use explicit run/status/module derived methods, and each
page count shares its content predicate. The only intentionally unpaged result
read is report generation for one Run, whose selected catalog is bounded at 202
scenarios. No second Query-by-Example or post-page filtering defect was found.

`npm ci` reports that locked `eslint@9.39.5` is no longer supported by the
publisher. Its declared Node engines include the project baseline and lint passes.
No broad dependency upgrade was made during handoff. Playwright's CDN stalled
while fetching its locked headless shell; browser regression was completed with
the locally installed Google Chrome channel. This affected only local browser
tool provisioning, not the application build or tests.
