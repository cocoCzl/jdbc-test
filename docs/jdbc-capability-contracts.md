# JDBC decision reference

English | [简体中文](jdbc-capability-contracts.zh-CN.md)

The reference is **Java SE 21 / JDBC 4.3** API Javadoc and the corresponding JDK `java.sql` source.
It records reviewed decisions, not a complete audit of every catalog assertion or a product certification.
The catalog's JDBC baseline still matters: for example, the SQLType overload requires JDBC4.2.
See [Usage](usage.md#results) for reading results and [known limitations](development.md#known-limitations)
for unresolved preparation, environment and driver questions.

## Separate execution, contract and evidence

PASSED/FAILED/SKIPPED/ABORTED are execution states. A JDBC conclusion, invocation evidence and a
SUITE-only requirement are separate dimensions. Preparation failure is not a driver defect; a terminal
case does not prove the contract ran. Metadata booleans, adapter declarations and observed operations
are independent evidence; false never disables the standard operation or creates an exception exemption.

| Evidence | Decision boundary |
|---|---|
| Successful reviewed operation and assertions | CONFORMS within the actual invocation scope |
| Reviewed normative assertion violated | CONTRACT_VIOLATION; preserve observed values and exception |
| Exact reviewed method permits SQLFeatureNotSupportedException, and that call throws it | ABORTED / UNSUPPORTED_ALLOWED, not a feature pass; no claim about later APIs |
| Reviewed required method rejects that exception | UNSUPPORTED_REQUIRED; e.g. Connection.getAutoCommit |
| Missing SQL, dependency, Provider or permission prerequisite | Preparation/environment evidence, not legal unsupported |
| Ordinary SQLException, unscoped unsupported, unknown execution/preparation error | Retain error, SQLState/vendor code/cause and NOT_EVALUATED unless specific evidence establishes attribution |
| Extra suite feature assertion unmet | FAILED / NOT_EVALUATED, requirementScope=SUITE; not a universal JDBC violation |

Do not infer legal unsupported from SQLState0A000, a vendor exception name/message, capability flags
or failure of a prerequisite. `contractInvoked` is case-level evidence, not a count of all downstream
JDBC methods. Container/setup failures retain affected descendants without fabricating child invocations.
Historical reports are not retroactively reclassified.

## Reviewed metadata and optional operations

| API | Preconditions and assertions | Official method source |
|---|---|---|
| supportsTransactions | Open connection/metadata; true or false. If false, default isolation must be TRANSACTION_NONE. | [supportsTransactions](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsTransactions()), [default isolation](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getDefaultTransactionIsolation()) |
| supportsBatchUpdates, supportsSavepoints | Preserve true/false declaration; access SQLException remains an error, not false. Successful query does not prove the feature works. | [batch](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsBatchUpdates()), [savepoints](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsSavepoints()) |
| Connection.setSavepoint() | Non-XA connection, auto-commit disabled, preparation complete; non-null savepoint and rollback, or optional exception at setSavepoint only. Metadata, setAutoCommit, rollback and cleanup are outside that wrapper. | [setSavepoint](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html#setSavepoint()) |
| Connection.createBlob/createClob/createSQLXML | Only the specific factory invocation is optional; later object operations retain their assertions. | [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html) |
| SQLXML.setString | Writing may make the object unreadable. Bind it with setSQLXML, then obtain a fresh database SQLXML value and assert exact content; do not require getString on the written object or omit content verification. | [SQLXML lifecycle](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLXML.html) |
| JdbcRowSet.updateRow | Assert the exact stored value by primary key through a separate connection and restore the same row even on failure. Do not require an insensitive cursor to reflect its own updates when ownUpdatesAreVisible is false. | [ResultSet.updateRow](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html#updateRow()), [ownUpdatesAreVisible](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#ownUpdatesAreVisible(int)) |
| CommonDataSource.getParentLogger | Reviewed optional logger call; not a blanket exemption for log-writer methods. | [getParentLogger](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/CommonDataSource.html#getParentLogger()) |
| Clob.setCharacterStream(long) | Live Clob/valid one-based position; Writer overwrites or the specific call throws optional exception. Stream write/close, commit, access and cleanup errors are outside the wrapper. | [setCharacterStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html#setCharacterStream(long)) |
| Blob.position(byte[],long) | Live Blob, valid pattern/start; exact position/-1 or optional exception at that call. Earlier partial-write/length/byte assertions remain. | [position](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html#position(byte%5B%5D,long)) |

Only reviewed call sites use optional wrappers. Other batch/transaction/savepoint operations retain
independent contracts; an exception at one method never establishes support for an entire interface.
A false declaration plus actual success is retained as `declaration_mismatch`, without hiding later failures.

## Extra suite requirements

[DatabaseMetaData.getTypeInfo](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getTypeInfo())
describes supported database types; it does not require every database to support ARRAY/REF/ROWID/STRUCT.
The type-presence checks still invoke metadata and fail if the suite-required type is absent, while
recording the required code/presence and SUITE scope. Access errors remain errors.

The suite also retains these requirements without claiming universal JDBC obligations:

- Automatic LOB persistence: exact local mutation, commit and exact new-connection read. JDBC's
  default locator description does not guarantee materialized-object write-through. Do not add an
  explicit UPDATE to hide failure. See [Blob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html)
  and [Clob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html).
- Invalid LOB call recovery: the specified SQLException is normative; later original-content/state
  recovery is separately checked. A wrong exception still fails the normative assertion.
- Procedure internal update-count visibility: traverse exposed results/counts to termination, close
  preceding results and verify values/side effects; JDBC does not promise every internal statement's
  count. See [getMoreResults](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getMoreResults(int))
  and [getUpdateCount](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getUpdateCount()).

`SuiteRequirementAssertionError` retains FAILED/NOT_EVALUATED and `requirementScope: SUITE`
(CLI `requirement_scope`). `SUITE_REQUIREMENT_UNMET` is a labelled subset of NOT_EVALUATED, not an
additional conclusion to add again. Worker, Web, persistence, downloads and CLI reports preserve it.
Unknown errors do not acquire this marker; old unmarked reports are not inferred.

## Preparation must preserve the tested semantics

| Area | Required preparation / boundary |
|---|---|
| LOB locator | PostgreSQL getBlob/getClob needs OID columns, not BYTEA/TEXT. Allocate/journal exact transaction-bound OIDs; free releases the locator, not the database object. Unlink only recorded OIDs. Kingbase Oracle-mode uses native EsBlob/EsClob over its authored BYTEA/TEXT fixture; use actual column evidence, not shared dialect assumptions. Stream/rollback resources remain independent. |
| LOB cleanup | Journal before commit, retain uncertainty and suppressed cleanup errors. Implicit dependencies stay parent-managed. MySQL FK-dependent indexes are confirmed removed only after successful owned-parent DROP, not by disabling constraints. |
| SQLXML | Real tables, columns and XML seed data are required; comment-only resources do not count. MySQL LONGTEXT/utf8mb4 exercises getSQLXML/setSQLXML, exact structure/Chinese text/escaping; native XML storage is not mandatory. Do not replace JDBC XML binding with string binding. |
| ARRAY | SQL NULL getArray and createArrayOf are independent initial observations. Accepted NULL does not prove Array methods/storage. Use real SQL arrays and original order/value/empty-vs-NULL assertions, not JSON. Oracle uses owned collection types/native constructors/nested-table storage; numeric arrays may be primitive/boxed/BigDecimal with exact integer values. Base type names are vendor-specific. |
| ROWID | SQL NULL getRowId observation does not prove binding/update/delete/batch support. Ordinary integer keys are not native ROWID substitutes. |
| REF_CURSOR | Native round trip retains registerOutParameter(int,Types.REF_CURSOR), without additionally requiring optional SQLType overload. A scalar MySQL OUT fixture can observe the SQLType overload only; it is not executed as a cursor substitute and says nothing about the integer overload or cursor reads. |
| PostgreSQL procedures | Use native IN/OUT/INOUT and cursor resources with escapeSyntaxCallMode=callIfNoReturn; consume cursors inside the transaction. Native implicit result-sequence preparation remains missing; client multi-statements or cursor names do not replace it. |
| Oracle implicit results | Prepare SYS_REFCURSOR and DBMS_SQL.RETURN_RESULT; real PDB validation remains required for new resources. Unit tests/other databases do not establish Oracle support. |
| XA | Check max_prepared_transactions only for prepare-dependent PostgreSQL branches. Zero means environment prerequisite; never modify globals or block unrelated one-phase/rollback cases. |

MySQL cancellation uses `SELECT 1 WHERE SLEEP(n) = 0`: interrupted standalone SLEEP may return1
without error; interrupted SLEEP inside a query raises an error. Retain timing, exception, cancel-thread
completion and connection-recovery assertions. See [SLEEP](https://dev.mysql.com/doc/refman/8.0/en/miscellaneous-functions.html#function_sleep).
MySQL [CALL](https://dev.mysql.com/doc/refman/8.0/en/call.html) need not expose each internal UPDATE
as its own count. Neither rule authorizes arbitrary successful cancellation or arbitrary SQL exceptions.

## Evidence, plan requirements and comparison

Suite records `jdbc.capability.metadata.*` and `jdbc.capability.behavior.*` as independent JUnit entries;
Worker retains observations. CLI records `capability_evidence` and reviewed `contract_basis` including
source, JDBC version, preconditions, outcomes and rationale. Adapter booleans remain declarations.

```json
{"plan_requirements":{"required_capabilities":["transactions","batch_updates","savepoint"]}}
```

A successful representative operation meets this narrow plan requirement; legal unsupported leaves it
unmet, no reliable invocation leaves it not_evaluated. Select representative operations as well as
metadata. Metadata true alone is insufficient. Requirements do not create generic JDBC obligations.
Legacy target_outcome is a suite assessment, not certification or a combined SQL/product/vendor score.

Internal comparison separates completed `suite_requirements` from JDBC new/fixed/unresolved failures.
A newly unmet extra requirement still returns1; an unchanged completed requirement failure may form
a baseline with exit0 and all_selected_passed=false. Preparation/unknown errors, incomplete execution
or unevaluated requirements block comparison with exit2. A previously passing API becoming legally
unsupported is a support loss, never a feature pass. See [driver regression](internal-regression.md).
