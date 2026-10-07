# Authorized execution scope and evidence

Connection probes remain read-only and do not require a write scope. New test runs require explicit `executionDatabase` and/or `executionSchema` on the Connection plus destructive consent. Missing scope returns `422 EXECUTION_SCOPE_REQUIRED` before a Worker is queued.

- MySQL: `executionDatabase` is required; every checkout selects and verifies that catalog. A URL without a default database is permitted only with this explicit selection.
- PostgreSQL, SQL Server and GaussDB: database and schema are required; database identity is checked and every checkout selects/verifies the schema.
- Oracle: an explicitly authorized existing schema is required. Ordinary schema identifiers use Oracle uppercase semantics. This does not provision an isolated Oracle user or grant privileges. No Oracle live safety verification is implied by PostgreSQL/MySQL regressions.
- Identifiers currently support ordinary ASCII names beginning with a letter, followed by letters, digits or underscores, up to 63 characters. Unrepresentable scopes are rejected rather than silently substituted.

The run freezes URL, username, database/schema, consent, object prefix and complete adapter capabilities as nonsecret execution input. Passwords retain their separate encrypted/transient path. Editing a Connection after enqueue does not change an existing Run. Old clients may omit scope fields when editing metadata without clearing an existing scope; sending an empty string explicitly clears the field. Existing probe-only Connections remain usable for probes but cannot create destructive Runs until configured.

## Fixture ownership

Each run has a random prefix. Explicit authored SQL placeholders map logical fixture names to quoted, schema/catalog-qualified physical names of at most 28 characters. Oracle physical identifiers use uppercase. Local index declarations, function bodies, prepared SQL and metadata lookup names share the mapping. This is not replacement of arbitrary SQL words, and is not a sandbox for arbitrary malicious SQL or drivers.

Every DataSource checkout initializes the scope, including DriverManager mode, pools and JdbcRowSet's standard JNDI DataSource path. JDBC Connection objects and their `setSchema`/`setCatalog` methods retain actual driver behavior. Suite SQL qualifies fixtures independently of the connection default. Metadata patterns escape the generated name's underscore, and exact metadata calls receive the exact name.

The ownership journal is persisted before CREATE and after its confirmed outcome. Only `CREATED` objects may be cleaned. Name conflicts are not pre-dropped. An uncertain CREATE or DROP is recorded as `UNKNOWN` and retained. Cleanup does not use `CASCADE`, `DROP IF EXISTS`, namespace-wide deletion or prefix enumeration. Implicit indexes, constraints, identity sequences and LOB segments are recorded where metadata permits, with explicit UNKNOWN dependency discovery when unavailable; these remain parent-managed and are never independently deleted from metadata alone.

A hard stop can leave `CREATING`, `CREATED`, `DROPPING` or `UNKNOWN` entries. These are a residual/uncertainty inventory, not proof cleanup ran. The Server exposes the saved journal with terminal JSON evidence. No automatic broad recovery cleanup is performed. CLI-created namespaces are retained after owned-object cleanup; operators must inspect them before separately removing an empty namespace. Existing CLI scope requires explicit `namespace.name`; a username is not implicit authorization.

## Capabilities

CLI and Worker consume the same official `adapters/<id>/adapter.json` declarations. All 25 managed capabilities must be present and boolean. Missing or invalid declarations are configuration errors; explicit false produces a sourced skip through the Suite guard. A true declaration enables the test and is not a passing compatibility result. Driver metadata does not replace the manifest or bypass its tests. FULL selection remains 202 scenarios.

## Execution evidence v2

New Worker runs use NDJSON `protocolVersion: 2` and JSON `report_schema_version: 2.0.0`. Server and Worker must be deployed together. V5 is additive; old migrations are unchanged. Legacy runs have no evidence version and are not retroactively assigned inferred starts or new outcome conclusions.

- PASSED / FAILED: actual scenario completion events.
- SKIPPED: explicit method or container skip with `skipSource` and reason. Container skips map only to selected descendants.
- ABORTED: an actual scenario abort outcome.
- PENDING / RUNNING: selected but not started / started without completion while a Run is active.
- NOT_RUN / INCOMPLETE: those respective states once the Run is terminal.

`completed` counts recorded terminal scenario outcomes, including explicit skips. `started` counts real starts; a pre-execution skip does not increment it. Setup/container failures retain their level, throwable and affected selected descendants. They do not generate child JDBC assertion failures. All-skipped runs are not successful compatibility runs.

REST run detail, SSE progress/terminal events, DB selected-scenario evidence, raw JSON and UI expose this accounting. Raw JSON can be produced from persisted Worker events after a crash even when the Worker never produced its own report; `source` and `workerReportValidated` distinguish this case. Original Worker reports remain separate artifacts. Missing results are accepted for normal terminal reports only with matching container failure evidence covering the missing descendants. Wrong run IDs, unselected/duplicate results, invalid order, mismatched scope/capabilities, status/count/result discrepancies remain errors.

## Repeatable checks

Default tests do not connect to user networks. Explicit disposable-container regressions:

```sh
./mvnw -Pworker-e2e,server-integration verify
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run test
npm --prefix jdbc-test-web run build
python3 scripts/run_contract_tests.py
```

These profiles create their own PostgreSQL/MySQL fixtures. Test-only MySQL authentication settings belong exclusively to the disposable container. They are not target connection advice. Sanitized integration evidence is written under module `target/acceptance-evidence`; preserve it before `clean` when needed. Never substitute these results for acceptance on a user's actual databases.

## Accepted real-target JAR checkpoint (2026-10-07)

Acceptance used the production Web → REST → scheduler → external-driver Worker → persistence/report chain, with separately authorized test scopes. Database addresses, credentials and raw QA evidence are intentionally excluded from source control.

| Database / Driver | Connection | Statement | Core | Full |
|---|---:|---:|---:|---|
| MySQL 8.0.30 / Connector/J 8.3.0 | 17 passed | 23 passed | 136 passed | 172 passed, 30 skipped |
| PostgreSQL 15.2 / pgjdbc 42.7.2 | 17 passed | 23 passed | 136 passed | 198 passed, 4 skipped |
| Oracle 19.3 / ojdbc11 21.13 | 17 passed | 23 passed | 136 passed | 189 passed, 13 skipped |

Selections overlap. Completed includes explicit skips; a disabled adapter capability is a configuration reason, not proof that the database lacks that capability. Per-scenario reasons remain in Results evidence and Report → View raw JSON. These results cover local production JAR deployment, not standard Docker image acceptance or release authorization.

Real PostgreSQL Full exposed two fixture-accounting defects: leading comments bypassed CREATE classification, and consecutive dollar-quoted function definitions were merged. Initialization now classifies after leading comments and the splitter consumes complete matching dollar delimiters. Function bodies and literals are preserved. The original Run remains unchanged; its five residual functions were matched by owned schema identity, Run names and exact fixture definitions, then precisely removed. A new Full Run verified all five functions were journaled and the schema was empty after cleanup.

Existing regression coverage: `TestObjectsTest.commentedFunctionUsesOwnershipJournalAndExactCleanup`, `SqlSplitterTest.adjacentDollarQuotedFunctionsRemainSeparateOwnedStatements`, `WorkerCapabilityIT` (five functions plus empty-schema check), and `ObjectIsolationIT` (pre-existing protection objects and exact-name collisions survive cleanup). No real targets participate in default Maven/CI tests.

For a later PostgreSQL Run, an operator must provision a new uniquely named, authorized schema first. In Connections, save the target database in **Authorized test database** and the new schema in **Authorized test schema**, then review that scope in New Run before consent. The platform freezes it when creating the Run; it does not provision the schema. Historical snapshots remain readable after their test schemas have been removed. Never reuse a deleted scope blindly or substitute a business schema.
