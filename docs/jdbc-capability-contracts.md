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
