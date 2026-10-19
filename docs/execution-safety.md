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

CLI and Worker retain validated boolean declarations, but false no longer disables standard capability verification. Missing Providers, fixture preparation and companion JARs are separate execution prerequisites. FULL defaults to all current 270 standard cases; no real vendor extension exists yet. CORE explicitly maintains the legacy 153 IDs; CUSTOM supports exact selection. Execution status and contract conclusions are separate, permitted unsupported requires a specific API contract, and historical reports are unchanged. See [selection semantics](development.md#test-scope-and-execution-semantics-selection-version-2).

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

These historical selections overlap and used the previous static-gate semantics. Historical skips are not evidence of absent database support; inspect their original reasons in Results/raw JSON. These results cover local production JAR deployment, not Docker image acceptance, other driver versions or release authorization. Disposable-container regressions do not replace acceptance on actual target combinations. No real targets participate in default Maven/CI tests.

## KingbaseES platform validation

KingbaseES is official for the observed server V009R002C016, oracle mode, JDBC database version
12.1 and driver V008R006B0001 only. Official denotes platform adaptation and interpretable results;
PostgreSQL, MySQL, Oracle and Kingbase use the same standard, without requiring all JDBC cases
PASSED. No assertion, capability, terminal rule or historical result changes with this promotion.

The 2026-10-19 real FULL task has 270 terminal result records: 261 contracts invoked and
9 not invoked due to missing or blocked preparation (4 direct, 5 blocked). Recorded statuses are
252 PASSED, 9 FAILED and 9 ABORTED. Historical NOT_RUN/INCOMPLETE counts are zero; those
accounting fields do not establish that every contract ran. The Server/isolated Worker/platform
PostgreSQL report path and subsequent browser flows were exercised. Cleanup was SUCCESS,
follow-up table/routine counts were zero, the disposable schema was removed, and no prepared
transaction remained. The initial standalone CORE timeout is a separate historical observation;
it does not negate the later completed FULL run, nor establish the cause of the slow first getTypeInfo.

Nine historical FULL aborted cases identify unavailable/blocked procedure, REF cursor, ROWID and multiple-result
SQL fixtures: platform preparation limits, not driver failures or passes. Two XA prepare failures
occurred with max_prepared_transactions=0 and must be interpreted as an environment prerequisite.
The raw historical CONTRACT_VIOLATION labels remain unchanged. LOB bounds/partial-write and
DataSource logWriter observations remain for driver/contract review; REF metadata, indexed batch
keys and unimplemented holdability do not establish a blanket defect claim for optional JDBC APIs.
The historical FULL does not certify its unexecuted fixture paths or other builds/modes/drivers.
The targeted follow-up has nine invoked contracts, eight conforming and one update-count assertion
failure; four direct preparation gaps and five shared blockers are RESOLVED. The assertion remains
a separate OPEN follow-up. Owned objects and the unique schema were cleaned; historical results
and the two XA environment limits remain unchanged. See
[preparation follow-up](development.md#targeted-preparation-follow-up-9-cases).

The [sanitized combination record](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)
contains the actual counts, all 18 non-passing cases, reviewed attribution and preserved source
excerpts. Original ignored reports are no longer available; its excerpt hash is not an original
Worker report hash. At that earlier acceptance checkpoint Oracle only had probes.

The later Oracle FULL task `200dfecd-7606-4e82-b499-25fe978133a1` has 270 records but only 261
invoked contracts; preparation gaps and a two-entry pre-existing LOB inventory discrepancy remain.
It does not establish complete four-database acceptance. Read-only investigation found no confirmed
shared cleanup defect, but historical parent mapping and a causal operation remain unknown.
Status: **cause undetermined, awaiting additional historical evidence; unresolved**. There is no
absence-of-impact claim. Do not repeat without new evidence; future writes require an independent
dedicated test account/schema. See the [Oracle follow-up](development.md#oracle-full-follow-up-2026-10-19).
Fixture screenshots and unit tests are not additional real-target evidence.
Setup and narrow diagnostics: [Development](development.md#kingbasees-setup).

## Credentials, adapter preflight and cleanup evidence

Embedded credentials are rejected before driver use, including historical URLs at saved-probe/run/Worker entry points. Public projections redact Oracle credential segments, query/semicolon secrets (including SQL Server brace escapes), and explicit sensitive JSON keys recursively. Driver stdout/stderr is discarded from public channels; known execution passwords are also removed from diagnostic/metadata fields. Text recognition cannot identify every arbitrary secret. Original valid input goes to the driver; redacted values never become execution URLs. Existing records are not scanned or rewritten automatically: owners should inspect old records and rotate credentials if appropriate; this is not a claim that a leak occurred.

`adapterExecution` freezes manifest identity, version bounds, asset ID, dialect and quoting. Web and CLI use the same Java preflight before auto-namespace creation or fixture DDL/DML. Invalid regexes, unparseable constrained versions and mismatches reject preflight (`PREFLIGHT_FAILURE`) without invented failed cases. Reports retain declarations, observed product/driver versions and MATCHED/REJECTED. Optional absent endpoints stay unbounded. Older queued inputs without this field use the installed manifest; historical reports are not retroactively revalidated. Manifest acceptance is not proof of support for untested targets; the Oracle follow-up above records the actual tested scope and remaining acceptance limits.

`cleanupEvidence` is additive in protocol 2 / report schema 2.0.0: SUCCESS, FAILED, UNKNOWN or NOT_EXECUTED, phase, attempt/deletion counts, retained objects, namespace disposition and safe SQLState/vendorCode. Cleanup failure changes the run conclusion (`CLEANUP_FAILURE`), never the actual case counts. Test/container/preflight failures retain their primary reason and attached cleanup evidence; timeout/cancel/crash retain their existing primary terminal reason. Cleanup stays inside the Worker deadline on the execution thread; an interrupted live JDBC call is never raced by a second cleanup thread. Confirmed drops are not repeated. Intentionally retained namespaces are not failed object cleanup.

A normal run cannot succeed without successful cleanup evidence. REST/SSE (`run.cleanup`), persisted details and rebuilt JSON retain the same evidence, including after a fresh Server application context. Missing historical evidence is UNKNOWN / not recorded, not inferred success. These fields do not authorize broad recovery deletion.
