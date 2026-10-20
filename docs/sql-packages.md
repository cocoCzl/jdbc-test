# Declarative SQL feature packages (format 1)

SQL packages describe SQL functionality, not JDBC conformance. Database adapters still supply
identity, dialect and namespace/lifecycle rules. A SQL assertion failure is not automatically a
product defect: check the definition, product/mode prerequisites and execution evidence first.
CORE/FULL and JDBC internal baselines retain their existing meaning. SQL packages are not supported
by `baseline`/`compare` in this release.

## Complete example and validator

[postgresql-basics.json](../examples/sql/postgresql-basics.json) is a complete importable package:
four semantic cases cover ordered queries, typed binding, SQL NULL versus text `"NULL"`, exact
Decimal scale, JOIN, aggregation, duplicate-preserving multisets, update counts and a designated
SQLState `22012` error followed by successful transaction recovery. It declares PostgreSQL 12–18;
only the disposable PostgreSQL 16 execution described below was verified.

[mysql-basics.json](../examples/sql/mysql-basics.json) supplies the same four test intentions with
explicit MySQL variants: SIGNED/CHAR parameter casts, InnoDB fixtures and duplicate-primary-key
error SQLState `23000`, vendor code `1062`, `SQLIntegrityConstraintViolationException`. MySQL SELECT
division by zero can yield NULL/warnings, so it is not reused as a PostgreSQL `22012` assertion.
The error predicate follows [ER_DUP_ENTRY](https://dev.mysql.com/doc/mysql-errors/8.4/en/server-error-reference.html#error_er_dup_entry);
casts follow the [MySQL 8.4 conversion functions](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html).
All four cases passed through a real Worker on this acceptance session's disposable MySQL 8.4 with
Connector/J 8.3.0; both owned tables were confirmed removed. This is limited SQL feature evidence,
not full MySQL/JDBC compatibility certification.

JSON is strict UTF-8, with duplicate/unknown fields rejected. `formatVersion` must be integer `1`.
JSON may be uploaded directly, or ZIP with the sole file `sql-package.json` at its root. ZIP uses
the same portable-path, entry-count and expanded-size boundary as adapter imports: maximum 512
entries, 1 MiB per file, 8 MiB total; compressed uploads also have an 8 MiB limit. No external SQL
resources, JARs, executable extensions or arbitrary archive extraction are supported.

Build once with JDK 21 (the Server/Worker must be deployed together):

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
python3 -B scripts/runner.py sql-validate --package examples/sql/postgresql-basics.json
```

Validation runs the same Core validator used by Web; no driver or database is used. Exit `0` means
valid definition, `2` means invalid/unavailable validation. It is not a connection or test result.

## Format

| Field | Required semantics |
| --- | --- |
| `formatVersion` | `1`; incompatible versions fail closed |
| `id`, `version`, `description` | Package ID (portable lowercase, max 64), user version label, description |
| `cases` | 1–200 cases; unique stable IDs within the package |
| Case `id`, `name`, `tags` | Stable ID (max 64), display name, string list |
| `appliesTo` | Exact `products` from JDBC metadata, explicit `dialects`, inclusive `minMajor`/`maxMajor`, `modeDescription` |
| `timeoutSeconds` | Integer 1–300, per case including preparation; cleanup remains bounded by the task budget |
| `prerequisites` | Explicit SELECT/ROWS steps, before preparation; use for finer version checks, compatibility mode and other observable requirements |
| `variants` | One explicit variant per declared dialect; no fallback |
| Variant `prepare`, `execute`, `cleanup` | Ordered steps, nonempty execution steps, logical table names to remove in reverse order |
| Step `id`, `sql`, `parameters`, `expect` | Unique step ID per phase; single SQL, typed parameter list, assertion |

The existing `postgresql`, `mysql`, `oracle`, `sqlserver` dialect names are recognized; a selected
case outside its exact product/dialect/major-version range fails as `PREREQUISITE_UNMET`. An adapter
claim cannot make the package applicable. `modeDescription` is explanatory, not proof: add a
prerequisite query and assertion for a required mode or finer version restriction. Each connection
is initialized and checked against the authorized database/schema before SQL. PostgreSQL 16 and MySQL 8.4
example syntax have the explicitly scoped real execution evidence documented here; other dialects require authored variants
and validation on their own disposable targets.

Each SQL starts with uppercase `SELECT `, `INSERT INTO `, `UPDATE `, `DELETE FROM `, or (preparation
only) `CREATE TABLE {{logical}} (`. No comments or semicolon characters are accepted, even inside
literals; this is a deliberately restrictive first format, not a general SQL parser. There is no
semicolon splitting. Procedures, session/transaction control SQL, arbitrary DDL and arbitrary cleanup
scripts are outside v1. All DML targets use an owned table placeholder. Placeholders must reference
a table explicitly created in that variant; `cleanup` lists every created table exactly once.

`{{items}}` is explicit fixture notation: it becomes the quoted, qualified run-and-case-specific
physical table name. Other SQL characters are unchanged. Reports include both the original template
and actual SQL, variant, bound values, expected/actual values and failure reason. Do not put secrets
in SQL or parameters: test inputs and results are report evidence. Known connection credentials are
redacted through the existing Worker/Server evidence projection.

### Parameters and assertions

Parameters are `{ "type": "INTEGER|DECIMAL|STRING|BOOLEAN", "value": ... }` in binding order.
The mappings are JDBC `INTEGER`/`setInt` (signed 32-bit), `DECIMAL`/`setBigDecimal`,
`VARCHAR`/`setString`, `BOOLEAN`/`setBoolean`. An explicit JSON `null` uses `setNull` with that JDBC
type. Integer and Decimal values are plain decimal **JSON strings**, never JSON floating numbers;
BOOLEAN uses JSON booleans. No implicit conversion or arbitrary `setObject` is used.

* `ROWS`: mandatory `order: ORDERED|MULTISET`, `columns` type list, `rows` exact expected rows,
  `decimalMode: NUMERIC|SCALE`. Column count equals `columns.length`, row count equals `rows.length`.
  `MULTISET` compares row counts per distinct typed row, preserving duplicates. `NUMERIC` treats
  `1.20` and `1.2` as equal; `SCALE` requires both value and scale. JSON `null` differs from `"NULL"`.
  Result `INTEGER` accepts JDBC exact integer types including BIGINT and compares BigInteger values;
  result DECIMAL requires NUMERIC/DECIMAL. STRING only accepts JDBC character types, BOOLEAN accepts
  BOOLEAN/BIT. Declaring STRING does not enable stringification of unsupported types.
* `UPDATE`: `{ "kind": "UPDATE", "count": 1 }`; exact nonnegative update count, no result set.
* `ERROR`: `{ "kind": "ERROR", "sqlState": "22012", "exceptionType": "java.sql.SQLException" }`.
  Supply an exact SQLState and/or integer `vendorCode`; optional qualified exception class is checked
  against the actual exception hierarchy without loading user-specified classes. All supplied
  conditions must match. Message text is not a predicate. The assertion covers only that step's
  `PreparedStatement.execute`, not opening the connection, creating a savepoint, preparing/binding,
  closing, rollback or cleanup. Connection/authentication/permission/cancellation/timeout errors
  remain execution issues, including reviewed vendor permission codes. Permission classification
  across all private drivers is not complete; do not use a generic vendor-only predicate for such
  errors. Explicit product-specific codes and exact SQLState are preferable.

Expected errors need a real savepoint, attempted independently of metadata capability declarations;
if it cannot be established, the prerequisite is unmet. The savepoint is rolled back/released after
the step. Recovery errors are recorded separately and cannot erase the original failure. Preparation
cannot declare an expected error. Unsupported float/time/binary/LOB/array/complex assertions and
multiple result sequences are rejected; result sets are capped at 1000 rows/step and 100 columns.

## CLI execution

Reuse a local JSON connection config and adapter, with a local driver/JAR dependency directory or
fixed Maven driver source. Use `db.connection_mode: "driver_manager"`; only direct connections are
supported by this SQL entry point. Require an **existing**, dedicated authorized namespace and consent:

```json
{
  "adapter": "postgresql",
  "db": {
    "url": "jdbc:postgresql://localhost:5432/disposable_sql",
    "username": "sql_test_user",
    "password_env": "SQL_TEST_PASSWORD",
    "connection_mode": "driver_manager",
    "driver": {"kind": "local", "path": "/private/local-jdbc-jars"}
  },
  "namespace": {
    "mode": "existing", "database": "disposable_sql", "name": "sql_tests",
    "destructive_consent": true
  }
}
```

Set the password environment variable locally; never put it in argv or commit the config. A private
adapter path can replace `postgresql`; the existing packer freezes its manifest/resources without
copying driver JARs into the SQL package.

```sh
python3 -B scripts/runner.py sql-run --config /private/sql-config.json \
  --package examples/sql/postgresql-basics.json \
  --case basic.query --case null.parameter --case join.aggregate --case expected.error \
  --output report/sql-feature-run --budget-seconds 300
```

The output directory must be new. Report and ownership ledger are retained there; temporary
connection configuration, extracted resources and execution workspace are removed. Passwords go to
Worker stdin. Driver dependencies stay in the isolated loader, never the Server. SQL runs do not
require the JDBC comparison key and do not produce signed JDBC comparison evidence.

Exit `0`: all selected SQL assertions completed successfully and cleanup is confirmed.
Exit `1`: completed execution with assertion/preparation/execution/cleanup failures.
Exit `2`: invalid input, unavailable Worker, interrupted/timed-out task without complete evidence,
or missing results. These are **execution**, not regression exit codes. An expected-error assertion
passing does not certify general error/exception behavior. Intentional negative fixtures are platform
validation, not database defects.

## Web workflow and persistence

1. Upload/validate a JDBC driver through existing driver management. Import a custom database adapter
   if needed, then create a connection with its explicit authorized database/schema.
2. Open **SQL test packages**, upload JSON/ZIP, review field errors or the saved definition. Package
   format validation, driver validation, connection success and test results are separate states.
3. Explicitly select a package revision, case IDs and existing connection/default driver. Review
   scope, adapter and SQL, supply a one-time password if needed, then consent and start.
4. The normal task page, result dialogs and report show SQL domain, frozen definition/fingerprint,
   adapter snapshot and driver snapshot. Result dialogs expose each step's expected/actual values,
   SQL and failures, plus independent cleanup evidence. JDBC contract statistics are omitted for
   SQL tasks; ordinary execution counts remain.

V10 adds immutable `sql_test_package(fingerprint, snapshot_json)` in the platform PostgreSQL. SHA-256
covers the exact UTF-8 manifest bytes; whitespace changes change the fingerprint. Version labels are
user declarations, not content identity. Identical imports are idempotent; same ID/version with
changed content becomes another explicit revision. The v1 library is append-only (no deletion UI).
At queue time the complete SQL package, exact selected case metadata/IDs and fingerprint are copied
to execution input alongside the independent adapter/resources and driver snapshots. Updates,
reimports and restarts do not resolve historical tasks against the current library. No authentication
or namespace authorization bypass is introduced; this retains the deployment's existing access model.

## Isolation, timeout and cancellation limits

Only reviewable, trusted packages and drivers on dedicated disposable targets are supported. SQL
keyword restrictions and object-prefix ownership are **not** a security sandbox: SELECT functions
can have side effects. Database permissions must independently prevent access outside the dedicated
scope; do not grant broader rights to get a package to run.

Cases use separate scoped connections and unique table placeholders. Preparation uses autocommit
so created tables have explicit ledger evidence rather than relying on DDL rollback. Execution uses
a transaction and always attempts rollback/close before reverse owned-table cleanup. Every subsequent
case gets its own names/connection. Cleanup failures are separate from the primary assertion and
make an otherwise successful case fail. Unknown CREATE/DROP outcomes are retained; cleanup never
pre-drops conflicts, discovers arbitrary names to delete, or uses CASCADE.

A per-case deadline and JDBC query timeout share the existing overall task/process budget. SQL Web
cancellation first signals the owned workspace; only `Statement.cancel` crosses threads. Once the
call unwinds, rollback/drop/close run on the execution thread, with cancellation and cleanup evidence
persisted while the task stays CANCELLED. The existing termination grace bounds this attempt; an
unresponsive driver is still forcibly terminated. A process kill/crash/ignored timeout cannot promise
cleanup: retain UNKNOWN/incomplete evidence and the ownership ledger, inspect exact owned objects
manually. Never report these runs as passing or start cleanup concurrently with the live SQL call.

## Verification in this phase

Unit/mock tests cover exact values, row/column mismatch, ordered/multiset behavior and duplicate
multiplicity, NULL/Decimal scale, unsupported result types, invalid definitions/archives, expected
error scope, preparation versus execution errors, separate recovery/cleanup failures, cancellation
and timeout checks. REST/JPA tests cover immutable revisions, exact selection and queue snapshots,
and isolation from the JDBC catalog. Initial phase-3 browser tests were fixture-backed;
the subsequent real browser integration acceptance is described at the end of this section.

Owned temporary PostgreSQL 16 verifies: full empty migrations; populated synthetic V8 → V9 → V10;
old driver/connection/task/result/report readability; adapter resources and SQL/task snapshots after
closing/reopening application contexts. Real uploaded pgjdbc and Worker exercise REST import →
connection → selected SQL task → persisted report; four example cases, a deliberate assertion
failure, statement timeout, cooperative cancellation and zero remaining owned tables. CLI uses the
same temporary target and checks exits 0/1; invalid input exit 2 is tested without a DB. This does
not establish cross-product compatibility, other PostgreSQL versions, malicious-driver containment,
or cleanup after an unresponsive process kill. No business/shared/historical database is accessed.

Reproducible checks (run the PostgreSQL tests only with a newly owned disposable PostgreSQL):

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
./mvnw -pl jdbc-test-core,jdbc-test-worker,jdbc-test-server -am test \
  -Dtest=SqlTestPackageTest,SqlFeatureExecutorTest,SqlPackageFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# Set JDBC_TEST_DISPOSABLE_PG_URL to that temporary container's loopback /postgres URL only.
./mvnw -pl jdbc-test-server -am test \
  -Dtest=PostgresPackageMigrationTest,PostgresSqlFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 -B scripts/run_contract_tests.py
npm --prefix jdbc-test-web test
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run build
```

The actual target was PostgreSQL 16.15 with pgjdbc 42.7.11. The full Web run passed 219 tests,
with 101 related tests repeated after final display changes; the fixture browser workflow passed
in English desktop and Chinese mobile. Python contracts passed 78 tests. Java syntax checker tests
passed 23; the syntax scan has no new/stale findings (224 retained findings). IDEA native formatting
is BLOCKED by the existing instance-lock access restriction; the IDE and lock were left untouched.
`git diff --check` passed. These checks do not constitute complete semantic/Alibaba inspection.
The runtime Web distribution includes the JSON example and these guides; CLI commands require the
source/CLI distribution plus a built Worker (use `--worker` for a non-default JAR path).

Integration acceptance also checks browser imports, queued revision updates and normal Server restart
against a real disposable PostgreSQL platform/target and Worker. Reports compare structurally across
restart (JSON object field order may vary); frozen manifests, fingerprints, steps and values remain
identical. A completed, validated SQL assertion failure is identified by `SQL_ASSERTION_FAILED`,
separately from `WORKER_EXECUTION_FAILED`; the existing FAILED run status is retained.
Known connection passwords are masked in Worker SQL/template/parameter/row/snapshot output as well
as diagnostics. Arbitrary author-supplied test secrets are not identifiable: do not embed secrets
in package definitions. Client disconnects do not trigger JSON writes into closed SSE streams;
other REST/platform errors retain their error responses.
