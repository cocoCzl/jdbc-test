# Capability and JDBC contract decisions

This phase reviews the three boolean metadata tests and the unnamed savepoint creation probe.
The target API documentation is Java SE 21 / JDBC 4.3. The official JDK 21 `java.sql` source Javadoc
in the installed `lib/src.zip` was also checked; the online Oracle documentation could not be
retrieved in this session. API links below identify the normative method descriptions, not vendor
support promises. This is not a complete review of every assertion in the 270-scenario suite.

| Scenario / API | Preconditions | Allowed outcome and assertion | Reason / official source |
|---|---|---|---|
| `database_metadata.supports_transactions` / `supportsTransactions()` | Open connection, successful metadata access | true or false; when false, `getDefaultTransactionIsolation()` must equal `Connection.TRANSACTION_NONE` | [supportsTransactions](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsTransactions()) and [default isolation](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getDefaultTransactionIsolation()) describe unsupported transactions and TRANSACTION_NONE |
| `database_metadata.supports_batch_updates` / `supportsBatchUpdates()` | Open connection, successful metadata access | true or false, retained verbatim | [supportsBatchUpdates](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsBatchUpdates()) explicitly returns false otherwise; it does not require universal batch support |
| `database_metadata.supports_savepoints` / `supportsSavepoints()` | Open connection, successful metadata access | true or false, retained verbatim | [supportsSavepoints](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsSavepoints()) explicitly returns false otherwise |
| `savepoint.set_savepoint` / `Connection.setSavepoint()` | Open non-XA connection, auto-commit disabled, preparation completed | Non-null savepoint and successful `rollback(savepoint)`, or `SQLFeatureNotSupportedException` specifically from `setSavepoint()` | [setSavepoint](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html#setSavepoint()) explicitly permits that exception. The optional wrapper does not cover metadata, setAutoCommit, rollback or cleanup |

For the boolean metadata APIs, an access `SQLException` remains an error, never false or a pass.
The original blanket `assertTrue` assertions conflated a metadata declaration with a required
feature. They are replaced with recorded declarations and the applicable transaction consistency
assertion. Successful boolean queries conform to the metadata query contract; they do not establish
that the declared feature works. Tests still invoke standard operations even when adapter or driver
metadata says false. No capability declaration creates an exception exemption.

Existing method-scoped optional policies for LOB/XML factories and CommonDataSource logger remain
unchanged. An unscoped `SQLFeatureNotSupportedException`, ordinary SQLException/SQLState/message,
missing fixture/permission/Provider or platform error does not establish legal unsupported behavior.
Batch and transactional operations retain their current assertions and conservative error handling;
this phase does not authorize unsupported responses for other methods or erase failures in other
savepoint scenarios. Broader per-method contract and vendor-error interpretation remains pending.

## Independent evidence and plan requirements

Suite publishes metadata and observed behavior separately through `jdbc.capability.metadata.*` and
`jdbc.capability.behavior.*` JUnit report entries. Worker retains these in its existing `observations`.
CLI consumes the equivalent boolean-only Surefire markers into per-case `capability_evidence` and
keeps reviewed `contract_basis` with source, JDBC version, preconditions, allowed results and reasons.
Existing adapter booleans remain adapter declarations; they are not driver metadata.

Representative existing rollback, executeBatch and unnamed-savepoint cases record actual successful
behavior. A false metadata declaration plus actual success is retained as `declaration_mismatch`;
an adapter/metadata mismatch is also independent evidence. Neither overwrites the method's execution
status or hides an exception. For savepoint creation, an explicitly permitted unsupported response
records observed false with `UNSUPPORTED_ALLOWED`, not PASSED. Failure after a successful API call
retains both the observation and the later failure.

A CLI plan can explicitly require the three reviewed capabilities:

```json
{"plan_requirements": {"required_capabilities": ["transactions", "batch_updates", "savepoint"]}}
```

`plan_assessment` keeps plan requirements, adapter declarations, driver metadata and observed calls
separate. A recorded successful representative call meets this narrow requirement; a recorded legal
unsupported response leaves it unmet. No actual call, a skipped case or an exception without support
evidence leaves it `not_evaluated`, even if metadata claims true. The plan requirement does not create
a generic JDBC contract obligation. The scope field limits support claims to recorded operations.
Select the representative operation cases as well as metadata cases when requiring these features.

CLI assessment exits nonzero for unmet/unevaluated requirements. Internal baselines may retain an
explicitly unmet requirement with complete legal-unsupported evidence; comparison reports it and
blocks newly unmet requirements. Unevaluated requirements prevent baseline selection/success.
Legacy aggregate `target_outcome` remains a suite assessment, not JDBC certification or one score
combining SQL, product and vendor compatibility. Inspect individual contract and plan conclusions.

## Validation limits

Mock tests cover false declarations, inconsistent transaction isolation, metadata access errors,
actual savepoint calls despite false declarations, method-scoped unsupported and ordinary errors.
Synthetic report/CLI tests cover plan evidence and driver-upgrade failure/coverage/environment gates.
A disposable local PostgreSQL 16 instance was used with the local PostgreSQL example adapter and
JDBC drivers 42.7.2 → 42.7.5. Exactly six scenarios ran in each A/B assessment (264 excluded):
`database_metadata.supports_transactions`, `database_metadata.supports_batch_updates`,
`database_metadata.supports_savepoints`, `savepoint.set_savepoint`, `connection.rollback`,
`statement.execute_batch`. Both selected runs and the internal comparison returned 0. This is
limited evidence for these selected calls and the CLI driver-upgrade workflow; it establishes
neither full JDBC conformance, all SQL/cleanup paths, other pools/modes/platforms nor validation of
MySQL, Oracle, Kingbase or proprietary drivers. A later control-character escaping fix in runtime
metadata output was verified with mock Suite tests, not another real-database run.
No historical evidence is edited or reclassified.

## Supported type metadata: suite requirements versus JDBC obligations

The real-system acceptance found that absent REF/ROWID entries were reported as generic JDBC
contract violations. Java SE 21
[DatabaseMetaData.getTypeInfo()](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getTypeInfo())
describes the types **supported by this database**; it does not mandate universal ARRAY, REF,
ROWID or STRUCT support. This wording was checked against the installed JDK 21 source Javadoc.

The four type-presence checks still call `getTypeInfo()` regardless of adapter declarations and
still fail if their required type is absent. They now record the required JDBC type code and
observed presence separately. A narrowly typed suite-requirement assertion remains FAILED, with
`NOT_EVALUATED` and no `contractConforms` claim in Worker/Web or `contract_conforms` claim in CLI.
This means the suite feature requirement was unmet and a universal JDBC violation was not
established; it is neither a pass nor an allowed-unsupported result. Metadata access errors still
propagate. Successful type checks and all remaining assertions retain their existing behavior.
Old reports remain unchanged. Other FULL failures require their own method/fixture/environment
review; this fix does not certify every assertion in the catalog.

中文：类型元数据只需描述数据库实际支持的类型，不要求所有数据库都有 REF、ROWID 等类型。
四个类型存在性断言继续实测，缺失仍为失败；独立保留要求的类型及实测值，契约结论为
`NOT_EVALUATED`，不声称 JDBC 违规或通过。读取元数据出错不会被当作类型缺失、合法不支持或成功。

The same acceptance observed a second attribution error: ordinary execution-phase SQLExceptions
(including an XA environment prerequisite failure) were universally classified as JDBC violations.
Worker now retains these as failed executions with `NOT_EVALUATED`, SQLState, vendor code, causes
and invocation evidence. A generic SQL error does not establish which of environment, preparation,
test definition or target behavior caused it. Only an explicitly reviewed required/optional wrapper
can establish those unsupported outcomes; SQLState and error-message matching grant no exemption.
Explicit assertions retain their existing classification, except the narrowly typed suite requirement
above. Remaining assertion/fixture reviews and remote FULL runs are not implied by this correction.

中文：普通 SQL 异常可能来自权限、XA 环境、准备 SQL 或被测行为，不能一律归为 JDBC 违规。
失败状态和原始错误证据仍保留；未知归因为 `NOT_EVALUATED`，不记为通过或合法不支持。


## PostgreSQL LOB locator fixture

The locator-update scenario uses a separate `lob_locator_ddl.sql` fixture. PostgreSQL
`BYTEA`/`TEXT` stream values are not large-object OIDs: pgjdbc `ResultSet.getBlob` and
`getClob` expect OID columns. The PostgreSQL fixture therefore uses OID columns and
creates two transaction-bound large objects with explicit ownership journal entries.
Native BLOB/CLOB fixtures remain explicit. The validated Kingbase Oracle mode uses
BYTEA/TEXT with its driver's native EsBlob/EsClob path, despite sharing a PostgreSQL
infrastructure dialect. Storage binding follows the authored fixture's actual column
types, not a product name or shared dialect assumption; mixed/missing type evidence
is rejected. The report records `jdbc.lob.storage` as OID or NATIVE.
The stream and rollback scenarios continue to use the original BYTEA/TEXT fixture.

The scenario still requires `Blob.setBytes`, `Clob.setCharacterStream`, commit, and
exact content visibility from a new connection. A failure at one operation does not
prove that subsequent operations ran. An unsupported Clob mutation was initially retained as a failed suite scenario pending
method review; the later method-level decision below scopes the documented exception
to setCharacterStream only. No SQLState-only exemption is introduced.
See the official Java SE 21 [Blob.setBytes](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html#setBytes(long,byte%5B%5D))
and [Clob.setCharacterStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html#setCharacterStream(long))
contracts. This fixture correction is not a claim that every driver must implement
mutable persistent LOB locators.

`free()` releases the JDBC locator, not the database large object. The ownership
journal records each allocated OID before commit; controlled cleanup unlinks only
those exact OIDs, independently of dropping the test row/table. Rolled-back OIDs may
already be absent. Uncertain creation, interrupted execution and cleanup failure
remain UNKNOWN/FAILED rather than being declared successful. Row cleanup failures
are retained as suppressed exceptions when there is an earlier execution failure.


Database-backed SQLXML cases validate the fixture shape before the JDBC invocation.
A comment-only DDL/DML resource does not establish a table or count as database
support. Missing fixture columns/tables are preparation failures; standalone SQLXML
factory cases retain their independent execution path.

MySQL indexes explicitly created on an owned test table are removed with that table.
An InnoDB foreign-key dependency may prevent a separate `DROP INDEX`; cleanup must
not disable constraints or modify server settings to force it. The journal confirms
these indexes as removed only after their owned parent table was successfully dropped.
A failed/uncertain parent drop retains the index evidence rather than claiming cleanup.

## Method-level LOB outcomes and suite-only postconditions

A later per-case review checked the Java SE 21 official API pages and the installed
JDK source, rather than inferring support from an exception name or adapter flag:

| Operation | Preconditions and permitted outcome | Classification boundary |
|---|---|---|
| `Clob.setCharacterStream(long)` | Live Clob, valid one-based position; returned Writer overwrites characters, or that call throws `SQLFeatureNotSupportedException` | Only this invocation is wrapped as optional. Stream write/close, access SQLExceptions, preparation, commit and cleanup remain errors. An allowed exception ends the scenario as ABORTED/UNSUPPORTED_ALLOWED, not PASSED or proof of later persistence. |
| `Blob.position(byte[], long)` | Live Blob, valid pattern and one-based search start; correct position/-1, or that call throws `SQLFeatureNotSupportedException` | Earlier partial-write, length and exact byte assertions still execute. Only the search call has the reviewed optional outcome. |
| Automatic LOB write-through | Successful initial read and exact local mutation, followed by commit and exact new-connection read | JDBC describes locators as the default representation, not a universal guarantee for materialized objects. The suite still requires exact automatic persistence and fails if unmet, retaining the underlying assertion and observed local/stored values; it does not label that feature requirement a universal JDBC violation. No explicit UPDATE is silently added. |
| Invalid LOB calls and subsequent state | Invalid positions/negative lengths must produce the specified SQLException. Original data preservation after rejected operations is checked separately. | Wrong exception classes still fail the normative assertion. A later recovery/content failure retains FAILED as an unmet suite requirement: the invalid-call method text does not specify recovery of object state. |
| Stored-procedure result sequence | Traverse every exposed result/update count to JDBC termination, close preceding results and verify actual stored side effects | JDBC traversal does not promise exposure of every internal statement's update count. The one-row visibility requirement remains a failing suite requirement when absent, with the observed count sequence retained. Incorrect result values or stored side effects retain their ordinary assertions. |

Official method sources:
[Clob.setCharacterStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html#setCharacterStream(long)),
[Blob.position](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html#position(byte%5B%5D,long)),
[Blob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html),
[Clob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html),
[Statement.getMoreResults](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getMoreResults(int)),
[Statement.getUpdateCount](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getUpdateCount()).
These decisions supersede the earlier conservative, unreviewed treatment of the two
specific unsupported LOB calls; they do not exempt other LOB methods or certify overall
BLOB/CLOB type support. These methods predate JDBC 4; the review uses the current JDBC
4.3 API documentation. No historical report is rewritten.

The MySQL cancellation fixture uses `SELECT 1 WHERE SLEEP(n) = 0` rather than standalone
`SELECT SLEEP(n)`. The [official SLEEP documentation](https://dev.mysql.com/doc/refman/8.0/en/miscellaneous-functions.html#function_sleep)
explicitly says standalone interrupted SLEEP may return 1 without an error, whereas an
interruption when it is part of a query raises an error. The cancellation and timeout
scenarios continue to require prompt interruption, the expected exception evidence,
completion of the cancellation thread and a usable connection afterward. This is a
fixture correction, not acceptance of arbitrary successful queries or SQL exceptions.
MySQL [CALL](https://dev.mysql.com/doc/refman/8.0/en/call.html) reports affected rows for
the final routine statement; a SELECT/UPDATE/SELECT procedure need not expose the
internal one-row UPDATE as its own result.

中文：仅在已核对的具体 LOB 方法调用处识别规范允许的不支持异常，不按适配声明或
SQLState 扩大豁免。物化对象修改、自动持久化及非法操作后的对象恢复分别判定；方案要求
未满足仍失败，不伪称通用 JDBC 违规。MySQL 的取消准备 SQL 已按官方 SLEEP 语义修正，
原有耗时、错误类型和连接恢复断言保留。过程内部更新计数的可见性作为独立方案要求保留。

## SQLXML preparation and consistent requirement reporting

The earlier MySQL SQLXML shape check detected an absent fixture; it did not finish
the preparation fix. Connector/J 8.3.0 exposes `SQLXML` over character data through
`ResultSet.getSQLXML` and `PreparedStatement.setSQLXML`. Its fixture now uses
`LONGTEXT` with `utf8mb4`, actual XML seed rows and a separate stream table. The
database-backed tests still invoke those JDBC APIs, and the stream tests retain
exact XML structure, Chinese text and escaping checks. A native SQL XML type is
not a prerequisite for exercising these driver APIs. No direct string binding is
substituted for `setSQLXML`, and missing fixture shapes remain preparation errors.

This reasoning does not permit JSON columns to stand in for SQL ARRAY, ordinary
integer keys to stand in for JDBC ROWID, or ordinary procedure results to stand in
for a REF_CURSOR output parameter. Deferred fixtures now allow the standard
`Connection.createArrayOf` factory and a `ResultSet.getRowId` SQL-NULL observation
to run before native storage preparation. An accepted NULL getter does not
establish RowId round-trip support: the original fixture and binding assertions
still have to execute. For MySQL, a real scalar OUT procedure permits observation
of `registerOutParameter(int, SQLType)` with `JDBCType.REF_CURSOR`; that procedure
is never executed as a cursor fixture. Accepting registration still requires the
native cursor fixture and complete result assertions. Only a precise
`SQLFeatureNotSupportedException` from a reviewed API is allowed unsupported;
other SQL errors retain their original diagnostics. The SQLType overload requires
JDBC 4.2. Optional catalog policy never by itself skips a scenario or creates a
feature pass.

The seven MySQL array-reading scenarios still need actual SQL ARRAY values. The
Oracle fixture now uses owned SQL collection types, native constructors and
nested-table storage instead of JSON. Array assertions accept primitive or boxed
numeric arrays, including Oracle NUMBER materialized as BigDecimal, while checking
exact integer values, order and empty-versus-NULL. Base type names are database
specific, not required to contain PostgreSQL's `int`. Oracle implicit-result
preparation uses SYS_REFCURSOR and DBMS_SQL.RETURN_RESULT; the extra internal
update-count requirement remains separately classified. These Oracle SQL changes
are automatically checked for managed preparation/cleanup and still require a
real authorized PDB run; no unit or PostgreSQL result constitutes Oracle validation.

For PostgreSQL, only the two XA prepare-dependent scenarios check
`max_prepared_transactions` before starting their branch. A value of zero records
an execution prerequisite, with no global configuration changes; one-phase commit,
rollback and all other selected scenarios continue.

`SuiteRequirementAssertionError` keeps `FAILED / NOT_EVALUATED`, with an explicit
`requirementScope: SUITE` in Worker evidence (`requirement_scope` in CLI). Other
unknown errors do not acquire this marker. Web details explain the requirement,
and the summary's `SUITE_REQUIREMENT_UNMET` is an explicitly labelled **subset**
of `NOT_EVALUATED`, never an additional contract conclusion or a JDBC violation.
The persisted evidence, JSON downloads and CLI HTML/Markdown retain both fields.
Legacy reports without the marker are not inferred or rewritten.

Internal comparison lists completed extra requirement failures separately under
`suite_requirements`; they are absent from JDBC `new_failures`, `fixed` and
`unresolved_failures`. A newly unmet extra requirement still returns 1; an
unchanged completed requirement failure can be a baseline with exit 0 and
`all_selected_passed=false`. Unknown execution or preparation errors continue to
block comparison with exit 2. A formerly passing API becoming legally unsupported
still records a support loss; it never counts as a feature pass. CLI target
assessment does not use an explicit extra requirement failure or an allowed
unsupported result as proof of generic JDBC incompatibility.

中文：本次补齐了 MySQL SQLXML 的实际准备，不能再把“提前识别缺表”说成修复已完成。
ARRAY、ROWID、REF_CURSOR 的语义前提仍未满足，原用例未到达的 API 不判作合法不支持。
额外方案要求仍失败；详情、统计、下载和内部比较均明确其范围，统计子集不重复相加。
只有新任务产生新证据，历史报告保持原样。

PostgreSQL 原生过程准备使用 `procedure_behavior_ddl.sql` 和 `ref_cursor_ddl.sql`。
调用原生过程时，测试连接 URL 需要显式配置 `escapeSyntaxCallMode=callIfNoReturn`；
这是驱动连接属性，不是数据库全局设置。带函数返回占位符的既有调用仍采用函数语义。
返回 REFCURSOR 时必须保持事务打开至结果读取完毕。现有已核实的 PostgreSQL 准备使用 OUT/REF_CURSOR；尚未提供与 Oracle
`DBMS_SQL.RETURN_RESULT` 或 MySQL 过程 SELECT 等价的隐式结果序列准备。
不得以返回游标名称或客户端多语句伪装该过程能力已通过。

ARRAY 的读取场景先在单行 SQL NULL 上观察 `ResultSet.getArray`，
只在该调用实际抛出 `SQLFeatureNotSupportedException` 时记录该方法允许不支持。
接受 SQL NULL 不能证明数组存储或 `Array` 对象 API 可用；必须继续执行原生准备和原有值断言。
该边界与 `Connection.createArrayOf` 工厂的独立观察互不替代。

REF_CURSOR 的原生完整往返保持原有 `registerOutParameter(int, Types.REF_CURSOR)`
契约，不为执行它额外要求可选的 SQLType 重载。缺少原生游标准备的 MySQL
独立观察 SQLType 重载时，只能说明该重载的结果，不能替代或推断整数重载及游标读取结果。

Current verified scope and open items are recorded in the sanitized
[2026-10-21 acceptance summary](acceptance-2026-10-21.md). Submitting these fixes does
not establish four-database acceptance. In particular, PostgreSQL result-sequence
preparation remains a platform gap, Kingbase ROWID NULL behavior remains a suspected
vendor defect, and the new Oracle resources have automated checks but no real Oracle
verification.
