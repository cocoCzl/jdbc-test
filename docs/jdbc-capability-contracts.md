# JDBC decision reference

English | [简体中文](jdbc-capability-contracts.zh-CN.md)

[Documentation](README.en.md) · [English home](../README.en.md)

Contents: [Separate execution, contract and evidence](#dimensions) · [Reviewed metadata and optional operations](#methods) · [Extra suite requirements](#suite-requirements) · [Preparation must preserve the tested semantics](#preparation) · [Evidence, plan requirements and comparison](#comparison)

The reference is **Java SE 21 / JDBC 4.3** API Javadoc and the corresponding JDK `java.sql` source.
It records reviewed decisions, not a complete audit of every catalog assertion or a product certification.
The catalog's JDBC baseline still matters: for example, the SQLType overload requires JDBC4.2.
See [Usage](usage.md#results) for reading results and [known limitations](development.md#known-limitations)
for unresolved preparation, environment and driver questions.

<a id="dimensions"></a>
## Separate execution, contract and evidence

Read five dimensions before turning execution states into JDBC conclusions:

| Dimension | Interpretation |
|---|---|
| Task execution status | `SUCCEEDED` means execution/report processing completed, not every case passed; case states are separate |
| JDBC contract conclusion | Review the exact method, JDBC version prerequisites, actual invocation and normative assertion |
| Extra suite requirements | `requirementScope=SUITE` is separate from JDBC violations; unmet requirements remain failures |
| Preparation/environment problems | Missing SQL, privileges, dependencies or Providers do not establish legal unsupported |
| Cleanup evidence | Independently inspect `cleanupEvidence`; UNKNOWN/absent evidence is not success |

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
| Contract applicability or conditional precondition unverified | Keep the call/assertion; failure remains FAILED / NOT_EVALUATED with reason and original assertion, neither passing nor legal unsupported |
| Extra suite feature assertion unmet | FAILED / NOT_EVALUATED, requirementScope=SUITE; not a universal JDBC violation |

Do not infer legal unsupported from SQLState0A000, a vendor exception name/message, capability flags
or failure of a prerequisite. `contractInvoked` is case-level evidence, not a count of all downstream
JDBC methods. Container/setup failures retain affected descendants without fabricating child invocations.
Historical reports are not retroactively reclassified.
`ContractApplicabilityAssertionError` marks only reviewed failed assertions, without skipping or
claiming unsupported: a null getParentLogger with declared JDBC below 4.1 (or unreadable baseline),
and DM SLEEP completing normally despite cancel. A null Logger with JDBC 4.1+ remains a normative
assertion failure; cancellation-thread errors are not absorbed by this marker.

<a id="methods"></a>
## Reviewed metadata and optional operations

| API | Preconditions and assertions | Official method source |
|---|---|---|
| supportsTransactions | Open connection/metadata; true or false. If false, default isolation must be TRANSACTION_NONE. | [supportsTransactions](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsTransactions()), [default isolation](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getDefaultTransactionIsolation()) |
| supportsBatchUpdates, supportsSavepoints | Preserve true/false declaration; access SQLException remains an error, not false. Successful query does not prove the feature works. | [batch](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsBatchUpdates()), [savepoints](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsSavepoints()) |
| Connection.setSavepoint() | Non-XA connection, auto-commit disabled, preparation complete; non-null savepoint and rollback, or optional exception at setSavepoint only. Metadata, setAutoCommit, rollback and cleanup are outside that wrapper. | [setSavepoint](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html#setSavepoint()) |
| Connection.createBlob/createClob/createSQLXML | Only the specific factory invocation is optional; later object operations retain their assertions. | [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html) |
| SQLXML.setString | Writing may make the object unreadable. Bind it with setSQLXML, then obtain a fresh database SQLXML value. Check XML structure, attributes and text first; exact serialization differences remain failed SUITE requirements, not JDBC violations. Do not require getString on the written object or omit content verification. | [SQLXML lifecycle](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLXML.html) |
| JdbcRowSet.updateRow | Assert the exact stored value by primary key through a separate connection and restore the same row even on failure. Do not require an insensitive cursor to reflect its own updates when ownUpdatesAreVisible is false. | [ResultSet.updateRow](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html#updateRow()), [ownUpdatesAreVisible](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#ownUpdatesAreVisible(int)) |
| CommonDataSource.getParentLogger | Introduced in Java 7 / JDBC 4.1. Reviewed optional logger call, not a log-writer exemption. A driver advertising only 4.0 needs applicability review; no version-based skip. | [getParentLogger](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/CommonDataSource.html#getParentLogger()) |
| Clob.setCharacterStream(long) | Live Clob/valid one-based position; Writer overwrites or the specific call throws optional exception. Stream write/close, commit, access and cleanup errors are outside the wrapper. | [setCharacterStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html#setCharacterStream(long)) |
| Blob.position(byte[],long) | Live Blob, valid pattern/start; exact position/-1 or optional exception at that call. Earlier partial-write/length/byte assertions remain. | [position](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html#position(byte%5B%5D,long)) |

Only reviewed call sites use optional wrappers. Other batch/transaction/savepoint operations retain
independent contracts; an exception at one method never establishes support for an entire interface.
A false declaration plus actual success is retained as `declaration_mismatch`, without hiding later failures.

<a id="suite-requirements"></a>
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

<a id="preparation"></a>
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

<a id="comparison"></a>
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

### Result and diagnostic attribution boundaries

Oracle implicit results begin with `getMoreResults(CLOSE_CURRENT_RESULT)` after an execution that returns no initial result/count, as documented in [Oracle JDBC implicit results](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/JDBC-getting-started.html#GUID-FA6FA05D-CE5C-4A03-B70E-8DD9F8F5DD92). This advance is Oracle-specific; other adapters retain the standard first-result protocol. The suite still verifies both values, result closure and stored effects. Requiring visibility of an internal procedure update count remains a failed SUITE requirement.

`DatabaseMetaData.getProcedureColumns` defines input/output parameter `ORDINAL_POSITION` from 1; 0 denotes a procedure return value. The 1/2/3 assertion for the three-parameter procedure remains unchanged. See the [JDK contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getProcedureColumns(java.lang.String,java.lang.String,java.lang.String,java.lang.String)).

An unclassified runtime exception during execution remains a failed result with `NOT_EVALUATED`; it does not alone prove a platform defect, JDBC violation or support. Worker preparation/infrastructure failures remain separate. Legacy CLI JUnit error records likewise lack reliable origin evidence. Existing persisted reports are not reclassified.

Worker diagnostics reuse `stackTrace` through events, reports, persistence and case details. The trace preserves actual frames for the root and collected cause/nextException/suppressed branches, with at most 10 distinct throwables (and therefore at most 9 edges of depth), 16 queued branches and 32 frames per throwable. A shared 12,000-character budget reserves each collected branch space for its first frames. Messages and frames are redacted before truncation; `[TRUNCATED]` markers identify omitted text/frames, graph limits or repeated references. These bounds do not change result classifications. Old reports without collected stacks remain readable and do not acquire fabricated frames.
