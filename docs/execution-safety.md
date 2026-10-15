# Execution safety

English | [简体中文](execution-safety.zh-CN.md)

Tests execute DDL/DML. Use a dedicated, explicitly authorized database/schema and review the frozen scope before consenting. Connection probes do not execute suite SQL and need no write scope. Worker isolation does not sandbox malicious SQL or drivers.

## Authorized scope

New Web runs require Connection `executionDatabase` and/or `executionSchema` plus destructive consent. Missing scope returns `422 EXECUTION_SCOPE_REQUIRED` before enqueue.

| Adapter | Required scope |
|---|---|
| MySQL | Database; each checkout selects/verifies the catalog |
| PostgreSQL, SQL Server, GaussDB | Database and schema; identity and schema are verified |
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

New runs use `protocolVersion: 2` and `report_schema_version: 2.0.0`; deploy Server/Worker together. Flyway V5 is additive. Legacy runs keep original outcomes without invented starts or v2 evidence.

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
