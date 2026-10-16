# Execution safety

English | [简体中文](execution-safety.zh-CN.md)

Tests execute DDL/DML. Use a dedicated, explicitly authorized database/schema and review the frozen scope before consenting. Connection probes do not execute suite SQL and need no write scope. Worker isolation does not sandbox malicious SQL or drivers.

## Authorized scope

New Web runs require Connection `executionDatabase` and/or `executionSchema` plus destructive consent. Missing scope returns `422 EXECUTION_SCOPE_REQUIRED` before enqueue.

| Adapter | Required scope |
|---|---|
| MySQL | Database; each checkout selects/verifies the catalog |
| PostgreSQL, SQL Server, GaussDB, KingbaseES | Database and schema; identity and schema are verified |
| Oracle | Explicit existing authorized schema; ordinary identifiers use uppercase semantics |

Scope identifiers start with an ASCII letter, followed by letters, digits or underscores, up to 63 characters. Invalid names are rejected. The platform does not provision Oracle users or Web test schemas; provision an authorized scope before running and do not reuse a deleted schema blindly.

Run snapshots freeze URL, username, database/schema, consent, object prefix and adapter capabilities without passwords. Later Connection edits do not change queued runs. Omitted scope fields preserve existing scope during metadata edits; an explicit empty string clears it. Probe-only connections cannot create destructive runs until scope is configured.

CLI existing-namespace mode also requires explicit `namespace.name`; a login username does not authorize a scope. CLI-created namespaces remain after owned-object cleanup, so inspect them before separately removing an empty namespace.

## Object ownership and cleanup

Each run gets a random prefix. Explicit SQL placeholders map fixtures to quoted, schema/catalog-qualified physical names of at most 28 characters, including prepared SQL, local indexes, function bodies and metadata lookups. Oracle names use uppercase. This mapping does not rewrite arbitrary SQL words. All DataSource checkouts initialize scope, including pools, DriverManager mode and JdbcRowSet; fixture qualification is independent of connection defaults. Metadata patterns escape underscores.

The ownership journal records before CREATE and after confirmed outcomes. Cleanup deletes only confirmed `CREATED` objects. It does not pre-drop conflicts, use `CASCADE` or `DROP IF EXISTS`, enumerate a namespace, or delete everything with a matching prefix. Uncertain CREATE/DROP outcomes become `UNKNOWN` and are retained. Implicit indexes, constraints, sequences and LOB objects stay parent-managed; metadata alone never authorizes independent deletion. Unavailable dependency discovery is recorded as uncertain.

Hard stops can leave `CREATING`, `CREATED`, `DROPPING` or `UNKNOWN` entries. Terminal JSON exposes the journal as residual/uncertainty evidence, not proof of cleanup. There is no automatic broad recovery cleanup. Inspect exact owned objects before any manual removal.

## Capabilities and result accounting

CLI and Worker share official adapter declarations. All 34 managed capabilities must be boolean. Missing/invalid declarations fail configuration; `false` causes an explicit sourced skip, while `true` enables a test and does not prove compatibility. DataSource interface capabilities come from explicitly configured driver-owned providers; all other capabilities remain manifest-driven. FULL selects 270 scenarios.

New runs use `protocolVersion: 2` and `report_schema_version: 2.0.0`; deploy Server/Worker together. Flyway V5–V8 are additive; V7 stores cleanup/termination evidence and V8 stores observed metadata. Legacy runs keep original outcomes without invented starts or v2 evidence.

| Scenario state | Meaning |
|---|---|
| PASSED / FAILED | Recorded actual completion |
| SKIPPED | Explicit method/container skip with source and reason |
| ABORTED | Actual scenario abort |
| PENDING / RUNNING | Selected but not started / started without completion while active |
| NOT_RUN / INCOMPLETE | The respective states after Run termination |

`completed` counts recorded terminal outcomes, including explicit skips. `started` counts actual starts; pre-execution skips do not increase it. Container/setup failures retain their level, throwable and affected selected descendants, without fabricated child failures. All-skipped runs are not successful compatibility results.

REST, SSE, persisted evidence and raw JSON follow these rules. Raw JSON reconstructed after a crash identifies its `source` and `workerReportValidated`; the original Worker report remains a separate artifact. Missing results in a normal terminal report require matching container-failure evidence. Wrong run IDs, unselected/duplicate results, invalid order and scope/capability/status/count discrepancies remain errors. Regression commands are in [Development](development.md#testing).

## Verified combinations

The 2026-10-07 checkpoint used production Web → REST → external-driver Worker → persistence/report with separately authorized scopes. Private addresses, credentials and raw QA evidence are excluded from Git.

| Database / driver | Connection | Statement | Core | Full |
|---|---:|---:|---:|---|
| MySQL 8.0.30 / Connector/J 8.3.0 | 17 passed | 23 passed | 136 passed | 172 passed, 30 skipped |
| PostgreSQL 15.2 / pgjdbc 42.7.2 | 17 passed | 23 passed | 136 passed | 198 passed, 4 skipped |
| Oracle 19.3 / ojdbc11 21.13 | 17 passed | 23 passed | 136 passed | 189 passed, 13 skipped |

Selections overlap. A disabled capability is a configuration reason, not evidence of absent database support; inspect per-scenario reasons in Results or raw JSON. These results cover local production JAR deployment, not Docker image acceptance, other driver versions or release authorization. Disposable-container regressions do not replace acceptance on actual target combinations. No real targets participate in default Maven/CI tests.

## KingbaseES candidate observations

All results here apply only to **server V009R002C016 / oracle mode / JDBC database version 12.1 /
driver declaration V008R006B0001**, with the artifact SHA-256 recorded in the partial result. They do not qualify other
Kingbase builds, modes or driver artifacts.

Both the 20-case trial and the earlier CORE exploration used a **standalone verification harness
invoking the normal isolated Worker protocol 2 / catalog / JUnit / ownership mechanisms**. They
were not Web → Server queue → persistence/SSE acceptance tests. The 20-case run resolved the bundled
`kingbase` adapter; the earlier CORE run supplied an explicit candidate execution snapshot to an
earlier Worker binary. Its exact original private request/binary digest was not retained. Do not
invent those hashes or describe either run as a full platform task acceptance.

The durable, sanitized [partial result](../adapters/kingbase/evidence/v9-oracle-partial-20261016.json)
contains each completed case's ID, source class/method, status, timestamps and duration, plus the
overlap list and original sanitized event-file hashes. It is an allowlisted projection, not a
reconstructed original Worker report. Original private inputs/full reports were removed during
cleanup; their contents cannot be inferred from these events. The table follows that projection:

| Scenario ID | Actual assertion scope | Result | ms | Also passed in CORE exploration |
| --- | --- | --- | ---: | --- |
| `connection.commit` | Commit deletion; query confirms matching test rows absent | PASSED | 505 | Yes |
| `connection.rollback` | Roll back deletion; test rows remain | PASSED | 490 | Yes |
| `connection.is_valid` | `isValid(5)` returns true | PASSED | 379 | Yes |
| `connection.get_meta_data` | Metadata, product/driver names and driver version are nonempty | PASSED | 352 | Yes |
| `connection.catalog_and_schema` | Read catalog/schema; setting their current nonempty values does not throw (not a switch to another schema) | PASSED | 460 | Yes |
| `database_metadata.get_database_product_name` | Product name is nonempty | PASSED | 344 | Yes |
| `database_metadata.get_driver_version` | Driver version is nonempty | PASSED | 347 | Yes |
| `parameter_metadata.get_parameter_count` | Prepared query reports three parameters | PASSED | 420 | No |
| `prepared_statement.set_boolean` | Bind true; query returns a row with active=true | PASSED | 376 | No |
| `prepared_statement.set_timestamp` | Bind timestamp in a range query; a row exists | PASSED | 387 | No |
| `prepared_statement.execute_update` | Bound update affects one row | PASSED | 398 | No |
| `prepared_statement.get_generated_keys` | Prepared insert returns a positive numeric key | PASSED | 395 | No |
| `savepoint.set_savepoint` | Create nonnull savepoint and roll back to it | PASSED | 461 | No |
| `savepoint.release_savepoint` | Create and release named savepoint | PASSED | 448 | No |
| `savepoint.rollback_to_savepoint` | Insert after savepoint is undone; count is zero | PASSED | 630 | No |
| `savepoint.write_after_savepoint_rollback` | Write after savepoint rollback; only later write visible, then whole rollback | PASSED | 557 | No |
| `statement.execute_insert` | INSERT has no ResultSet and update count is one | PASSED | 391 | No |
| `statement.execute_query` | ResultSet has a first row with expected fixture name | PASSED | 395 | No |
| `statement.get_generated_keys` | Statement insert returns a positive numeric key | PASSED | 403 | No |
| `statement.execute_update_ddl` | Owned CREATE returns zero; table then dropped by ownership mechanism | PASSED | 606 | No |

The 20 durations sum to 8,744 ms; Worker RUN_STARTED → RUN_FINISHED spans 22,094.530 ms.
`durationMs` is Worker monotonic time around JUnit case execution, including parameter resolution
and afterEach connection closure; beforeAll fixture setup and final cleanup are outside that case
duration. Event timestamps are UTC wall-clock measurements, not the same monotonic clock.

The CORE exploration selected 153 cases: 43 started, **42 explicitly PASSED**, zero completed
FAILED/ABORTED/SKIPPED, one incomplete (`database_metadata.get_type_info`), and 110 not started.
Those 42 have their own statuses and durations in the partial result. Seven IDs overlap the table;
do not add 20 and 42 or treat completion alone as success. The later single-case diagnostic below
is another separate observation, not a retroactive completion of that interrupted run.

Metadata preflight matched the strict identities. Worker cleanup reported SUCCESS for eight owned
objects and no retained objects. The outer verification harness then dropped its newly created,
unique test schema and verified absence. No existing schema was selected for writes.

The CORE exploration's overall result remains **TIMEOUT**, with Worker cleanup initially
UNKNOWN_PENDING_PROCESS_EXIT. After Worker exit, the harness removed and verified absence of that
trial's unique schema too. That outer cleanup receipt is not invented Worker cleanup SUCCESS.
The successful finite trial does not replace or hide this incomplete broader result.

Unit/API/frontend tests use synthetic driver fixtures and mocks, not this private JAR or database.
They check preset defaults and edit protection, SPI discovery, independent connection values,
adapter registration/assets/candidate state and identity rejection before writes. Browser fixture
screenshots are labelled as fixtures; they are not evidence of a full browser-to-server real run.

Further qualification needs an agreed test matrix of actual server builds/compatibility modes,
the intended JDBC artifact(s), dedicated schemas and minimum privileges. The supplied connection
was sufficient for this trial; no additional credentials are needed to reproduce its limited scope.
Run the remaining cases before declaring a validated combination. LOB/XML/CallableStatement, other
metadata methods, DataSource/pool/XA and other modes remain unverified. Reusing PostgreSQL behavior
means schema selection/verification, the existing non-Oracle identifier normalization, double-quote
qualification, standalone index-drop syntax and the selected SQL setup/cleanup paths. It does not
verify every PostgreSQL asset, SQL grammar, privilege model or JDBC capability on Kingbase.

### CORE timeout diagnosis

The [sanitized diagnostic record](../adapters/kingbase/evidence/v9-oracle-type-info-20261016.json)
records two targeted processes, phase timings, method-only stack samples and cleanup. No full CORE
rerun, timeout increase, skipped case or capability change was used to obtain a passing result.

`WorkerMain.execute()` waits on **one Future for the entire JUnit launcher plus
JdbcContext.shutdown()**, using `request.timeoutMs` (90,000 here). The deadline covers fixture
setup, all selected cases and cleanup. It is not a per-test or `getTypeInfo()` deadline, and starts
after driver/configuration setup. Worker report writing is after that Future. The public Server
also has an independent process wait limit in `TestRunExecutor`, but these direct-Worker trials
did not involve that Server. The original RUN_FAILED event identifies the Worker's own TIMEOUT;
it was not an outer-tool kill. Historical outer-harness input was not retained, so no exact outer
limit is asserted for that older run.

Original CORE event timeline (relative wall time from RUN_STARTED):

| Observation | Value |
| --- | ---: |
| Completed-case duration sum | 75,172 ms |
| Bulk rollback / commit cases alone | 27,505 / 26,667 ms (54,172 ms total) |
| `get_type_info` CASE_STARTED | 80,468.754 ms |
| Worker TIMEOUT event | 90,042.113 ms |
| Active case elapsed before TIMEOUT | 9,573.359 ms |

Thus most of the shared budget was already spent. The old events cannot locate the interrupted
call's internal phase. Minimal reproduction on the same observed combination found:

| Stage | Read-only diagnostic measurement |
| --- | ---: |
| First `DatabaseMetaData.getTypeInfo()` call | 35,835.853 ms |
| First `ResultSet.next()` | 0.027 ms |
| Remaining next-only traversal, 1,041 rows total | 0.276 ms |
| Count assertion | 0.003 ms |
| ResultSet close | 0.016 ms |
| Second call on the same connection (warm) | 66.265 ms |
| Warm traversal with TYPE_NAME / DATA_TYPE reads | 2.953 ms |
| Connection close / isolated loader close | 2.243 / 0.963 ms |

The second pass is warm and reads columns; it is not an independent cold-cache column-read
comparison. No type values/names were published. The diagnostic is read-only and creates no SQL
fixtures. Its process finished normally in 36.751 seconds under a 90-second outer safety limit.

A separate run selected **only the unchanged original** `database_metadata.get_type_info` through
the normal Worker with the same 90-second Worker budget. It PASSED in **35,024 ms**, returned a
normal schema-2.0.0 report, and exited 0 after **37.785 seconds**. CASE_FINISHED → cleanup event was
742.820 ms; metadata event → RUN_FINISHED, including report writing, was 8.775 ms. The difference
between process wall duration and RUN event span was about 440 ms combined for JVM startup/exit
and surrounding harness overhead; it does not show an exit hang. Worker cleanup removed its one
owned table successfully; the outer harness dropped its new unique schema and verified absence.

At approximately 5.38 and 14.31 seconds after process start, both execution-thread samples showed:

```text
DatabaseMetaDataTest.testGetTypeInfo
  -> KbDatabaseMetaData.getTypeInfo
  -> TypeInfoCache.getSQLType
  -> KbPreparedStatement.executeWithFlags
  -> QueryExecutorImpl.processResults / KBStream.receiveChar
  -> SocketInputStream.read / Net.poll
```

The supported conclusion is **cumulative task-budget exhaustion combined with a costly initial
driver type-information lookup** on this combination. The active method is not a failed assertion
or a report/close hang in the reproduction. No platform defect has been established. These samples
do not separate server execution, network latency and driver lookup-query volume, and do not prove
that every Kingbase instance has the same latency. No driver property or cache behavior was changed.

Reproduction and driver setup: [Development](development.md#kingbasees-candidate-setup).

## Credentials, adapter preflight and cleanup evidence

Embedded credentials are rejected before driver use, including historical URLs at saved-probe/run/Worker entry points. Public projections redact Oracle credential segments, query/semicolon secrets (including SQL Server brace escapes), and explicit sensitive JSON keys recursively. Driver stdout/stderr is discarded from public channels; known execution passwords are also removed from diagnostic/metadata fields. Text recognition cannot identify every arbitrary secret. Original valid input goes to the driver; redacted values never become execution URLs. Existing records are not scanned or rewritten automatically: owners should inspect old records and rotate credentials if appropriate; this is not a claim that a leak occurred.

`adapterExecution` freezes manifest identity, version bounds, asset ID, dialect and quoting. Web and CLI use the same Java preflight before auto-namespace creation or fixture DDL/DML. Invalid regexes, unparseable constrained versions and mismatches reject preflight (`PREFLIGHT_FAILURE`) without invented failed cases. Reports retain declarations, observed product/driver versions and MATCHED/REJECTED. Optional absent endpoints stay unbounded. Older queued inputs without this field use the installed manifest; historical reports are not retroactively revalidated. Manifest acceptance is not proof of support for untested targets; no new GaussDB/SQL Server/MySQL/Oracle real-target claims are made here.

`cleanupEvidence` is additive in protocol 2 / report schema 2.0.0: SUCCESS, FAILED, UNKNOWN or NOT_EXECUTED, phase, attempt/deletion counts, retained objects, namespace disposition and safe SQLState/vendorCode. Cleanup failure changes the run conclusion (`CLEANUP_FAILURE`), never the actual case counts. Test/container/preflight failures retain their primary reason and attached cleanup evidence; timeout/cancel/crash retain their existing primary terminal reason. Cleanup stays inside the Worker deadline on the execution thread; an interrupted live JDBC call is never raced by a second cleanup thread. Confirmed drops are not repeated. Intentionally retained namespaces are not failed object cleanup.

A normal run cannot succeed without successful cleanup evidence. REST/SSE (`run.cleanup`), persisted details and rebuilt JSON retain the same evidence, including after a fresh Server application context. Missing historical evidence is UNKNOWN / not recorded, not inferred success. These fields do not authorize broad recovery deletion.
