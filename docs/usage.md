# Usage

English | [简体中文](usage.zh-CN.md)

Start the website using [Deployment](deployment.md) or [local development](development.md#local-source-startup).
The workflow is **upload driver → configure connection → select standard JDBC tests → run → review results/report**.

## Upload a driver

In **Drivers → Add a driver**, choose an optional vendor preset and upload the driver plus all required
dependency JARs as one package. Preset vendor, class and URL prefix are editable suggestions.
Validation loads the selected class in the isolated Worker; it does not connect to a database.
Driver API major/minor, manifest release strings, JAR SHA-256 and connected metadata are separate
version sources. Neither a filename nor the editable package version proves the actual release.

Artifacts are immutable; upload a new package to change binaries or dependencies. Class edits reset
validation. The Server stores packages under `<data-dir>/drivers/`; it does not load uploaded classes.
Private drivers are not bundled, committed or silently downloaded. Only upload trusted drivers:
Worker isolation is not a sandbox for malicious code.

## Configure a connection

In **Connections → Add a connection**, select the database and a validated driver, enter the actual
JDBC URL and separate username/password, configure the authorized scope, then **Test connection**
and **Save**. A test is independent of saving; save edits before testing persisted settings.
Probes open/close a connection and read metadata/configuration where available; they do not run test DDL/DML.
A successful probe does not establish write permissions or scenario compatibility.

Saved passwords are encrypted with the platform Master Key. An omitted password reuses a saved one;
explicit empty-password selection sends an empty string. Spaces remain literal. Credentials embedded
in URLs are rejected. Do not put secrets in URLs, connection properties, task names or reports.
Review diagnostics before sharing; text redaction cannot recognize every arbitrary secret.

<a id="database-configuration"></a>
## Database configuration

Use actual endpoints and separate credential fields. The database/catalog and schema have different
meanings; the following scope requirements apply before a writable run, not merely a probe.

| Database | Driver class / URL example | Required authorized scope |
|---|---|---|
| PostgreSQL | `org.postgresql.Driver` / `jdbc:postgresql://host:5432/test_db` | Database and existing schema |
| MySQL | `com.mysql.cj.jdbc.Driver` / `jdbc:mysql://host:3306/test_db` | Database (JDBC catalog) |
| Oracle | `oracle.jdbc.OracleDriver` / `jdbc:oracle:thin:@//host:1521/service` | Existing dedicated schema; URL selects a service, not a schema |
| SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerDriver` / `jdbc:sqlserver://host:1433;databaseName=test_db` | Database and existing schema |
| GaussDB | Select the class/URL required by the supplied driver | Database and existing schema; built-in PostgreSQL infrastructure |
| KingbaseES | `com.kingbase8.Driver` / `jdbc:kingbase8://host:4321/test_db` | Database and existing schema |
| Dameng / DM | `dm.jdbc.driver.DmDriver` / `jdbc:dm://host:5236?schema=TEST_SCHEMA` | Exact existing schema; no database/catalog required |

Built-in support is adaptation, not complete JDBC compatibility. Version/mode boundaries and current
unresolved issues are maintained under [known limitations](development.md#known-limitations).
For private drivers, explicit compatible-dialect selection reuses execution rules without claiming
another product's identity. It requires source-maintained built-in assets; package/JSON imports are unavailable.

### PostgreSQL and KingbaseES

PostgreSQL native procedure tests need the connection property `escapeSyntaxCallMode=callIfNoReturn`
(in the URL or properties); REF_CURSOR results must be consumed inside the open transaction.
Two-phase XA branches require prepared transactions enabled by the DBA in the dedicated environment.
The test never changes global `max_prepared_transactions`.

Kingbase's verified adaptation is limited to server V009R002C016, oracle mode, JDBC product version
12.1 and driver V008R006B0001. Its identity is KingbaseES; its SQL infrastructure and quoted names
use the PostgreSQL dialect. Confirm the build/mode yourself; empty numeric version bounds do not mean
all-version support. See the [maintained combination evidence](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json).
The owner must grant CONNECT and schema USAGE/CREATE; selected features may need additional permissions.

### Oracle

Use a reachable PDB service and a dedicated local test account/schema with tablespace quota and
CREATE SESSION/TABLE/PROCEDURE/TYPE as needed. Implicit-result preparation also needs access to
`DBMS_SQL.RETURN_RESULT`. Do not reuse the historical Oracle write scope; see the unresolved
LOB inventory difference under [known limitations](development.md#known-limitations).

Ordinary identifiers follow Oracle uppercase semantics. Upload compatible `orai18n` for required
charsets, and `xdb` plus `xmlparserv2` for SQLXML, alongside `ojdbc`. Isolated validation warnings
`ORACLE_I18N_MISSING` / `ORACLE_XML_MISSING` are dependency hints, not full dependency resolution.
Execution checks charsets before writes; missing XML dependencies block affected cases, not independent tests.

### Dameng DM8

Choose **Dameng**, not Oracle. The editable driver preset supplies `dm.jdbc.driver.DmDriver` and
`jdbc:dm:`. Use `jdbc:dm://host:5236?schema=TEST_SCHEMA`; a DM URL path also denotes a schema,
not a MySQL-style database. Do not fill a dummy database/catalog. Enter the exact stored schema
case: an unquoted `develop` normally exists as `DEVELOP`; a quoted mixed-case schema retains its
stored case. Enter the name without quote delimiters. Current scope validation accepts ASCII letters,
digits and underscores, starts with a letter, and allows at most 63 characters; other names are not supported.
Every execution checkout sets and verifies the schema; DM does not apply Oracle's uppercase folding.

The account needs access to the existing dedicated schema and privileges for selected tables,
procedures and types. Cancellation/timeout preparation expects an accessible `DBMS_LOCK.SLEEP`;
the platform does not install system packages or change instance compatibility/global settings.
Optional provider classes are `dm.jdbc.driver.DmdbDataSource`, `dm.jdbc.driver.DmdbConnectionPoolDataSource`
and `dm.jdbc.driver.DmdbXADataSource`, with default bean properties `URL`, `user`, `password`.
Their presence does not establish that those tests pass.

Observed metadata: **DM DBMS 8.1.3.162**, connected driver **8.1.2.192**, Driver API **8.1**,
JDBC **4.0**, null catalog; instance `COMPATIBLE_MODE=0`, `CASE_SENSITIVE=1`.
The driver's `compatibleMode` was not set; its effective default was not verified and is distinct
from the instance setting. Real Web upload/loading, connection probe/save/edit, execution, result
filters, report download and persistence after backend restart have been verified in a dedicated schema.
The observed FULL completed all 270 selected cases: 249 passed, 21 failed, none skipped or aborted;
all 41 owned objects were removed. Separate retests verified five of the original failures: two
lock-error recognition cases, NULL-return preparation, SQLXML lifecycle and RowSet update verification.
The SQLXML 5/5 retest overlaps the original failures only at `sqlxml.set_string`; the RowSet 9/9
retest verifies the stored value independently of insensitive cursor visibility. These retests do
not change the original FULL statistics. Sixteen original cases remain unresolved: eleven
compatibility doubts with standalone JDBC reproductions and five extra suite requirements.
Observed limitations include arrays/empty collections, Blob `InputStream.readAllBytes` truncation
(length/getBytes/small-block reads remain complete), delayed commit when enabling auto-commit,
provider behavior, procedure metadata ordinals, batch keys by column index, `absolute(0)` and
interruption of `DBMS_LOCK.SLEEP`. Array type naming/support and cancellation support for that
procedure still need vendor confirmation; these observations do not establish universal JDBC
violations. Four type-metadata requirements and internal procedure update-count exposure remain
unmet; they are tracked separately from JDBC violations, including in regression comparison.
DM is a candidate adapter; only DM DBMS 8.1.3.162 + driver 8.1.2.192 was verified. Standalone
reproduction does not mean a vendor defect is confirmed. See the vendor's [DM8 JDBC guide](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html);
the live manual is not a retained manual for the exact patch release.

## Create and execute a task

In **New Run**, choose a saved connection, set an optional editable task name, review the current
catalog and select tests. FULL means all currently applicable cases, CORE is the maintained subset,
and CUSTOM narrows selection. Use module/individual selection and search; counts come from the
current catalog, not a fixed historical total. False capability declarations do not deselect standard tests.

Review the exact database/schema, exclusions, providers and time budget, then give explicit destructive
consent. A login username alone does not authorize a scope. SQL permissions, dependency JARs and
fixtures are checked during preparation; missing preparation is not a driver defect. Inputs freeze
when queued; later connection edits do not alter the task. Missing prerequisites in one feature do
not establish that unrelated features are unsupported.

For driver-owned DataSource, pooled and XA tests, configure the actual provider class names and public
bean setters in the connection. Missing providers remain unmet prerequisites, not successful tests.
Budget includes preparation, execution and cleanup. Cancellation or an unresponsive JDBC call may
leave incomplete work; inspect the final reason and cleanup evidence.

<a id="results"></a>
## Results and reports

Task completion describes execution/report processing. Ordinary failed cases should show
**Completed · with unsuccessful cases**; execution/report errors or cleanup failures are abnormal
and retain a concrete reason. Lists, details, filters and reports use the same execution outcome.
A fully processed progress bar is not a compatibility pass rate.

| Case state | Meaning |
|---|---|
| PASSED / FAILED | Recorded completed case; inspect the separate contract conclusion |
| SKIPPED / ABORTED | Explicit skip/abort, including precisely allowed unsupported behavior; not a feature pass |
| NOT_RUN / INCOMPLETE | Selected but never started / started without completion at task termination |

Filter results by status and module, open details for phase, SQLState/vendor code, observations and
requirements, and download the JSON report. `completed` includes explicit skips; `started` counts
actual starts. A terminal record alone does not prove the standard API was invoked. JDBC conclusions,
SUITE-only requirements and preparation/environment problems remain separate; see [JDBC reference](jdbc-capability-contracts.md).
Persisted report downloads identify reconstructed sources and `workerReportValidated`; historical
reports are not retroactively rewritten or supplemented into a new FULL pass rate.

<a id="cleanup"></a>
## Authorized scope and cleanup

Use only a dedicated, explicitly authorized database/schema. Scope identifiers start with an ASCII
letter, followed by letters/digits/underscores, up to 63 characters. The Web does not create test
schemas or Oracle users. Each checkout verifies scope; object names are quoted and qualified.
Each task uses a random prefix and an ownership journal. Only confirmed objects created by that
task may be deleted. There is no pre-drop of conflicts, namespace-wide sweep, prefix-only deletion
or forced CASCADE. Implicit dependencies remain parent-managed; metadata discovery alone does not authorize deletion.

Inspect `cleanupEvidence`: **SUCCESS, FAILED, UNKNOWN or NOT_EXECUTED** and retained/uncertain
objects. A hard stop may leave journal entries in intermediate states. Missing evidence is not
success, and task-owned cleanup success alone does not prove no impact on existing objects.
There is no automatic broad recovery deletion. Before manual removal, confirm exact ownership.
Existing authorized schemas remain; CLI-created namespaces also remain after owned-object cleanup
and require separate inspection before removal. Never change global modes or existing business objects.
