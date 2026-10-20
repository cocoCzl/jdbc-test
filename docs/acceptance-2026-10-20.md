# Development checkpoint acceptance summary (2026-10-20)

English | [简体中文](acceptance-2026-10-20.zh-CN.md)

This summary separates real system validation, fixture tests and unverified scope across the first
three implementation phases. It is not JDBC certification or proof of product compatibility.
The custom PostgreSQL adapter validates the self-service integration mechanism, not a proprietary
database or private driver.

## Source and isolated environment

Acceptance used base `2eb69675bbff347b243ad5a758637fce2407fba0` plus the then-uncommitted phase changes.
The final per-file source manifest SHA-256 was
`055e744bd3213cc1e0f15492f768ffa6c15513391e2eeff9b38ec232421336b1`.
Checkpoint preparation subsequently changed documentation only; executable code matches that tested
state. Git history preserves the implementation and documentation. This manifest identifies the
local acceptance evidence; it is not the checkpoint's Git tree hash.

| Component | Verified scope |
| --- | --- |
| Platform persistence | Dedicated disposable PostgreSQL 16.15, separate platform and target databases |
| Targets | Session-owned disposable PostgreSQL 16.15 and MySQL 8.4.11; scoped schema/database accounts |
| Runtime | Zulu JDK 21.0.9, Node 24.11.1, real Chrome, frontend, HTTP Server and isolated Worker |
| Drivers | pgjdbc 42.7.11 for Web/SQL, 42.7.2 → 42.7.11 for JDBC A/B; Connector/J 8.3.0 for MySQL SQL |
| Adapter | `vendor-int-20261020`, 1.0.0/revision 1 → 1.1.0/revision 2, postgresql dialect, IMPORTED/LOCAL |
| SQL packages | `vendor-sql-int-20261020` 1.0.0 → 1.1.0; `mysql-basics` 1.0.0 |

No business, shared, remote or historical acceptance database was accessed. Owned containers,
temporary volumes, services, Workers, test browsers, credentials and uploaded driver copies were
removed; pre-existing resources and shared base images were preserved. PostgreSQL remains the
platform database; H2 is used only in some automated tests.

### Key SHA-256 fingerprints

| Content | Fingerprint |
| --- | --- |
| pgjdbc 42.7.2 JAR | `0c244ac7d02cf89d8e29852eace6595d75bc4d78581b85b2768460081646a57b` |
| pgjdbc 42.7.11 JAR | `1981b31d3993c58702783c1cddf10a34e48c1f413d70ff1cb6def0a143484647` |
| Connector/J 8.3.0 JAR | `94e7fa815370cdcefed915db7f53f88445fac110f8c3818392b992ec9ee6d295` |
| Custom adapter 1.0.0 | `9582db7a313f2089c95cc45c7b5fc4c4ccc0186bfd8bbbe1a5db82a1fc78b7a8` |
| Custom adapter 1.1.0 | `a9b4a21e4590843e39f05b86dd13f0cc1522a8d63164cd19baa9ae71b75dbb49` |
| Custom SQL package 1.0.0 | `eb7384f13488c98c89b3e7e501bfb2727eb580ec8efb5602a6dda4ec73a5f6ca` |
| Custom SQL package 1.1.0 | `d3f18f8ee0b3c0e4c028546bdb16a4a708832792f2042891b52858f99249c332` |
| MySQL example 1.0.0 | `76cd0e86ae4e3be59895c4282b770018ebe530df96534d89bb684d2c09e79a76` |

## Real system workflows

Browser acceptance used neither mock APIs nor fixture Workers:

1. Upload/validate pgjdbc, import the independent local adapter, create/probe a connection, import
   the SQL package and select its version and cases through the page.
2. Holder task `73e597da-88a5-4587-a5e0-ee53e7767b05` occupied the Worker. While the main task was
   QUEUED, both adapter and SQL package libraries were updated to 1.1.0.
3. Original task `ad15e3d2-b6b9-43c0-b734-90f4eb99b022` still executed 1.0.0 definitions:
   `basic.query`, `null.parameter`, `join.aggregate`, `expected.error` passed; an intentional
   negative case expected 1 and received 999. It was reported as SQL_ASSERTION_FAILED with cleanup
   SUCCESS. This verifies platform judgment, not a database defect.
4. The page displayed real SSE progress, step SQL, parameters, expected/actual values, cleanup and
   frozen fingerprints. After normal Server shutdown/restart, the same task/history/report remained
   readable with unchanged definitions, fingerprints, results and ordered arrays. JSON object field
   order may vary across JVMs; raw byte equality is not claimed.
5. New task `bafb4fd4-a176-49f6-8009-3258ad59f521` used 1.1.0 fingerprints and SELECT 2: 2/2 passed,
   cleanup succeeded. The final main browser workflow had no browser errors or HTTP >=400 errors.

CLI and Web agreed on the same adapter's execution declarations/fingerprint and SQL package
fingerprint/templates. CLI_LOCAL/LOCAL and IMPORTED/LOCAL provenance remained distinct; no official
status was granted.

The four [MySQL example](../examples/sql/mysql-basics.json) cases passed using the real driver and
Worker: table creation/seeding, typed binding, JOIN/aggregation, duplicate-preserving multisets,
NULL, Decimal scale, update counts and expected errors. Both owned tables were dropped; final table
count was zero. Explicit MySQL variants use SIGNED/CHAR, InnoDB and duplicate-primary-key error
SQLState 23000/code 1062/SQLIntegrityConstraintViolationException. PostgreSQL division-by-zero
semantics were not silently rewritten or weakened; see [SQL package references](sql-packages.md).

Real JDBC A/B selected exactly these six IDs, passing all six for each driver and excluding 264:

- `database_metadata.supports_transactions`
- `database_metadata.supports_batch_updates`
- `database_metadata.supports_savepoints`
- `savepoint.set_savepoint`
- `connection.rollback`
- `statement.execute_batch`

Run A, select internal baseline, run B and compare all returned 0. Local provenance and
`formal_eligible=false` remained. Individual assessments stayed `evaluation_inconclusive`; six
cases cannot establish full JDBC compatibility. Comparison 0 means no new regression and may retain
old failures; SQL execution 0 means every selected case passed with confirmed cleanup. Only compare
0 has real A/B evidence here; compare 1/2, old failures, coverage loss, missing evidence and changed
environments are synthetic tests. An actual attempt to create a JDBC baseline from a SQL report
returned 2 without creating a baseline. The comparison key was only used for configuration HMAC,
never included in reports or the Worker environment. The temporary key was destroyed; future runs
need their own persistent key and newly selected baseline.

## Migration and test evidence boundaries

| Check | Evidence and scope |
| --- | --- |
| PostgreSQL migrations | Reused real `PostgresPackageMigrationTest`, 1 passed, not skipped: empty V1–V10, populated synthetic V8→V9→V10, old drivers/connections/tasks/results/reports readable, new package resources/task snapshots restored after reopening. This session's real Web platform also migrated an empty V1–V10 database; the full upgrade matrix was not repeated |
| PostgreSQL SQL | Real `PostgresSqlFlowTest`, 1 passed, not skipped: examples, intentional failure, timeout, cooperative cancellation, subsequent execution and cleanup; CLI 0/1 real, invalid-input 2 offline |
| Real browser | Two tests passed separately: custom workflow and restart/new revision. [Opt-in test](../jdbc-test-web/e2e/custom-integration.real.spec.ts) requires an explicitly owned environment |
| Focused Java | Core SQL 5, credentials 8, SQL executor 7, WorkerMain 9, REST handler 1; related runs passed |
| Scheduling/forced termination | 3 boundary tests plus 1 UNKNOWN evidence test passed; forced termination used a fixture Worker, retaining CANCELLED, unvalidated report and cleanup UNKNOWN |
| Python | regression 17, SQL 4, adapter archive 3, distribution 12 passed |
| Web | Related 71 tests, lint and TypeScript/Vite build passed; existing chunk-size warning retained; earlier full phase matrices were not repeated |
| Java checker unit tests | 23 passed |
| Java syntax rules | 257 files, 224 retained findings, no new/stale baseline entries; not semantic or complete Alibaba verification |
| IDEA native formatting | **BLOCKED** by existing instance-lock access restriction; IDE/lock/config left untouched, other scans are not equivalent checks |
| Whitespace | `git diff --check` passed |

## Three issues fixed

1. **Secondary exception after SSE disconnect:** the generic REST handler attempted JSON output
   into a closed event stream. It now handles unusable async responses and Spring-recognized
   disconnects; genuine platform errors still return INTERNAL_ERROR. `RestErrorHandlerTest`
   verifies both paths.
2. **Incomplete known-password masking in SQL output:** the Worker projection now covers SQL,
   templates, parameters, rows and output snapshots without changing execution input/protocol
   identity. `CredentialOutputTest` adds synthetic-secret regression coverage; retained evidence
   had no matches for session credentials. Arbitrary author-embedded secrets cannot be identified.
3. **Completed SQL assertion failure mislabeled as Worker failure:** validated, consistent reports
   now retain SQL_ASSERTION_FAILED and bilingual explanation, with the existing FAILED state.
   Worker protocol exit 4 is distinct from SQL CLI exit 1. Real PostgreSQL and focused orchestration
   tests verified the correction.

## Delivery and limitations

Version control contains this sanitized summary, implementation, tests, examples and user guides.
Original acceptance records, per-file manifests, sanitized logs/screenshots/reports and cleanup
results remain in ignored `.local/integration-20261020/`. Raw logs, configurations, driver JARs and
screenshots are not committed. Understanding this summary does not require access to that folder.

Unverified: real third-party proprietary databases/private drivers, other versions/modes, the full
JDBC matrix, pool variants, cleanup after forcibly killing a genuinely unresponsive driver, and
production/public/multitenant security. Packages/drivers must be trusted and database permissions
must independently limit scope; keyword restrictions are not a sandbox. Internal JDBC baselines
are CLI-only; SQL baselines are unsupported. Earlier Oracle/Kingbase evidence is separate.

**Checkpoint conclusion:** self-service import, connection, SQL execution/step reports, queued
snapshots, updates and restart recovery have the real system evidence above. Driver regression has
six-case real CLI A/B evidence. This does not establish arbitrary JDBC database compatibility,
complete JDBC certification or complete product compatibility.
