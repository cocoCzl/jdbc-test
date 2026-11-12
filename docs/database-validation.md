# Database validation records and limits

English | [简体中文](database-validation.zh-CN.md)

[Documentation](README.en.md) · [English home](../README.en.md)

Contents: [Combinations and public evidence](#recorded-combinations) · [Current limitations and open questions](#known-limitations) · [Dameng history, current implementation and retests](#dameng)

This page maintains historical combinations, current limits and subsequent targeted retests together;
reports are not rewritten and new pages are not created per run or per database.
See [Usage](usage.md#database-configuration) for setup and [JDBC reference](jdbc-capability-contracts.md) for interpretation.

<a id="recorded-combinations"></a>
## Combinations and public evidence

The active index is [catalog.json](../adapters/catalog.json). These are scoped records, not proof that all current cases pass.

| Database | Status | Recorded scope / source |
|---|---|---|
| PostgreSQL | [official](../adapters/postgresql/adapter.json) | 15.2 / 42.7.2; [evidence](../adapters/postgresql/evidence/postgresql-15.2-driver-42.7.2.json) |
| MySQL | [official](../adapters/mysql/adapter.json) | 8.0.30 / 8.3.0; [evidence](../adapters/mysql/evidence/mysql-8.0.30-driver-8.3.0.json) |
| Oracle | [official](../adapters/oracle/adapter.json) | 19.3.0.0.0 / 21.13.0.0.0; [evidence](../adapters/oracle/evidence/oracle-19.3-driver-21.13.json) |
| KingbaseES | [official](../adapters/kingbase/adapter.json) | V009R002C016 / oracle / JDBC 12.1 / V008R006B0001; [evidence](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json) |
| GaussDB | [candidate](../adapters/gaussdb/adapter.json) | No validated_combinations; PostgreSQL infrastructure reuse does not cover all GaussDB/openGauss products |
| Dameng / DM | [official](../adapters/dm/adapter.json) | 8.1.3.162 / 8.1.3.62 / mode 0 / Java 21; [redacted evidence](../adapters/dm/evidence/platform-8.1.3.162-driver-8.1.3.62.json); [scope](#dm-platform-acceptance) |

`official` is internal adaptation status, not vendor certification; `supported_versions` limits admission and capabilities are declarations.
Kingbase server build, mode and JDBC product version are distinct fields. Unset numeric bounds do not mean all-version support; build/mode are not runtime-enforced.
Kingbase manifest `qualification.asset_policy` records a later nine-case targeted followup: eight conforming and one procedure update-count assertion failure.
The public FULL evidence still retains historical `preparation_followups` as `pending`; it is not the original report of that later retest, and later records do not rewrite FULL.
Legacy evidence `target_outcome` is not current-suite certification or a cross-database ranking; some original reports are unavailable, as noted below.

<a id="known-limitations"></a>
## Current limitations and open questions

These are active boundaries, not an exhaustive certification matrix. Preserve original assertions
and historical reports; a targeted follow-up never changes an earlier FULL pass rate.

| Area | Current boundary / next condition |
|---|---|
| PostgreSQL result sequence | Platform fixture gap: native implicit result sequence equivalent to the intended procedure contract is not prepared. Cursor-name lists or client multistatements are not substitutes. |
| PostgreSQL / Kingbase two-phase XA | Observed max_prepared_transactions=0 blocks prepare-dependent branches. DBA must provide an enabled dedicated environment; independent XA branches continue. |
| Kingbase metadata latency | An early getTypeInfo call exhausted a task budget; a later broader run completed. Cause remains undetermined. Reproduce only that call in a bounded isolated process before attributing a driver hang. |
| Kingbase ROWID | Suspected driver NULL mapping defect: SQL NULL returned a non-null KbRowId with wasNull=true and getBytes NPE. Binding/update/delete/batch branches not reached remain unverified. |
| PostgreSQL DataSource log writer | Suspected driver behavior; retain the assertion and minimal evidence pending method-contract/vendor review. |
| PostgreSQL holdability and generated keys by index | Evidence insufficient; NOT_EVALUATED. PSQLException/SQLState0A000 does not by itself authorize unsupported. |
| Kingbase / MySQL procedure update counts | Internal statement count visibility is an extra suite requirement, not a universal JDBC guarantee; unmet assertions remain failures with SUITE scope. |
| MySQL optional paths | Observed allowed-unsupported ARRAY/ROWID/SQLType registration calls do not validate downstream Array methods, binding or native cursor output. Preserve method-scoped evidence; no whole-interface support claim. |
| Oracle recorded execution failures | Parameter ordinals, cancellation/query timeout, batch generated keys and SQLXML stream text still require per-method attribution/revalidation; new preparation resources do not resolve these observations. |
| Oracle resources | Native array/multiple-result preparation and nested dependency cleanup have automated checks but no real Oracle revalidation. Batch-key-by-name NPE attribution and terminal-page/filter/download review remain open. A reachable authorized PDB with quota/permissions is required. |
| Oracle LOB inventory | Two pre-existing dictionary entries were absent after a historical run. Cause and historical parent mapping remain unknown; neither business-data loss nor absence of impact is established. Synthetic ownership tests do not explain/fix it. Keep that write scope closed; new writes need an independent dedicated account/schema. Attribution needs existing pre-run mapping/backups/audit/redo/capture identity, not another blind FULL. |
| Historical evidence | Some earlier MySQL/Kingbase original FULL reports are unavailable. Sanitized excerpts are not original reports; do not reconstruct or certify missing history. Maintained adapter evidence stays in adapters. |
| Dameng | Exact official scope is in the [acceptance record](#dm-platform-acceptance); historical DM DBMS 8.1.3.162 + driver 8.1.2.192 FULL remains 249/21 with separate followups. The independent 2026-11-04 driver 8.1.3.62 CORE is 151/2 and FULL 254/16: eight contract violations, three other JDBC outcomes not evaluated, five extra suite failures. Owned-object cleanup is 18/18 and 41/41; 912 existing object metadata records match before/after. Intermediate driver releases and other modes/patches are unvalidated. The [Dameng records](#dm-capabilities) contain the per-capability audit; standalone observations do not confirm vendor defects. |
| Platform launch validation | Windows host ACL/PowerShell process behavior and IDEA UI remain unverified. Standard Docker source build has no successful retained verification after a registry authentication timeout; alternate local-image execution is not source-build proof. |

Missing fixtures are not driver defects; legal unsupported is not a feature pass. REF/ROWID type
presence, automatic LOB persistence, invalid-call recovery and internal update-count requirements
remain separately scoped, as specified in [JDBC reference](jdbc-capability-contracts.md).

SQL Server new connections, probes and execution are retired; historical connections, runs, raw reports and conclusions remain readable.

<a id="dm-platform-acceptance"></a>
## DM platform adapter acceptance (2026-11-12)

**Decision: accept as official, registering only DM DBMS 8.1.3.162 + driver 8.1.3.62.**
The boundary is `COMPATIBLE_MODE=0`, `CASE_SENSITIVE=1`, Java 21 and declared JDBC 4.0. Driver SHA-256:
`327bade775f4f15b97618bc9736a4625def52701cf53f5ef5ad72580797e6e45`.
`official` means a project-maintained platform adapter, not vendor certification, all tests passing or
approval of other versions/modes. The [redacted evidence summary](../adapters/dm/evidence/platform-8.1.3.162-driver-8.1.3.62.json)
separates original statistics, source hashes, manual review and subsequent CUSTOM.

### Rules and evidence quality

The previous candidate status coexisted with real-database records but no registered formal combination
or public reviewable acceptance summary. The setter preparation was already repaired; contract
preconditions still needed accurate expression. This means neither never tested nor fix every failure.
The [development guide](development.md#adapter-acceptance) distinguishes existing rules, minimum code
validation and the maintenance standard made explicit here. Generic eligibility rules, capabilities,
selection, SQL and JDBC assertions were not relaxed.

All 79 files in the local integrity index were verified by SHA-256, with no missing/mismatched entries.
The persisted 8.1.3.62 CORE/FULL Worker reports, original set_array CUSTOM and journal are available.
The public file is a reviewed redacted summary, **not a raw report**. Private connection material stays
out of Git; source labels are provenance identifiers, not local download links. The older 8.1.2.192
249/21 FULL original is unavailable in the reviewed materials: retain its historical narrative without
reconstruction or formal registration. The 8.1.3.62 JAR is no longer locally present; its fingerprint
comes from preserved records, not a newly computed artifact hash.

### Platform chain acceptance

| Chain | Reviewable basis and outcome | Boundary |
|---|---|---|
| Upload, dependencies, isolation and identity | Prior Web operation record; CORE/FULL metadata MATCHED with independent product/driver identity; supplied older JAR validates/probes in the current isolated Worker | No new Web acceptance; isolation is not a hostile-code sandbox |
| Probe, save, edit and restart readback | Historical operation record and persisted reports; DM ConnectionService lifecycle fixtures preserve exact schema and encrypted credentials | No restart of the existing website this session |
| Database/schema and authorization | ExecutionScope/ScopedDataSource preserve exact names, require no catalog, retain the existing namespace | Current read-only probe finds DEVELOP; inputs are not automatically uppercased |
| Selection, preparation and standard calls | All 270 FULL cases started/completed; array fixture tests and separate CUSTOM verify setter insertion/exact readback | Setter success does not prove createArrayOf element-type semantics |
| Timeout, cancel, exceptions and terminal outcomes | Bounded Worker, observed cancel thread/original failures; failed/unknown cleanup cannot claim success; applicability expression fixed this review | DM SLEEP interruptibility unknown; calls/assertions still execute and failures do not become passes |
| UI, reports and persistence | Original FULL source=PERSISTED_WORKER_EVENTS, workerReportValidated=true; case/summary, pagination/download historical checks and presentation fixtures | New classification verified with fixtures, not a new real-database FULL |
| Ownership and cleanup | Separate CORE 18/18, FULL 41/41, CUSTOM 3/3 successful cleanup; exact successful journal CREATEs dropped in reverse order | No DROP SCHEMA, CASCADE or prefix scan; uncertain CREATEs remain visible |
| Version and declaration | Register only the 8.1.3.62 combination with available original reports; revision becomes 1.2.0 | Numeric admission range is not exhaustive validation |

Subsequent attribution and CUSTOM each retain a matching pair of 912 existing-object metadata entries;
these pairs were compared this review. They do not replace the original FULL baseline inventory or
prove equality of all row data. FULL 41/41 cleanup is supported by its own report/journal.
The supplied older JAR (SHA-256 `afc60f332c8b79d514c1c19d2b58164c3e42112fea455eae68c9d56716834a97`)
was live-probed read-only: DM DBMS 8.1.3.162, driver 8.1.2.192, JDBC 4.0, actual schema `DEVELOP`.
No new DDL/DML was performed.

### Open questions and acceptance impact

| Category / question | Evidence, affected scope and next step | Blocks acceptance? |
|---|---|---|
| Platform defect: unknown preconditions directly labelled violation | Original FULL logger/cancel labels unchanged; current explicit applicability failure retains original assertion as cause, FAILED / NOT_EVALUATED; focused Worker/CLI tests | Repaired; no block or historical reclassification |
| Preparation/contract: array factory typeName | INTEGER rejection and collection-name/base-type-name questions retained independently; separate setter CUSTOM 1/1 already completed | No block for limited setter acceptance; patch-matched factory semantics still unknown, no factory-conformance claim |
| Contract applicability: getParentLogger | Introduced in JDBC 4.1; observed declaration 4.0 and null result; patch commitment to higher JDBC unknown | No block: still invoked and failed/unknown, not allowed unsupported; JDBC 4.1+ null remains a normative assertion failure |
| Contract/environment: cancel and timeout | SLEEP(0) proves availability/permission only; cancel requires interruptibility evidence; normally completed timed work remains an execution-control question | No block: unknown precondition separated, no general claim about all SQL cancellation/timeouts |
| Driver behavior questions | Auto-commit, pooled handles, Blob, parameter ordinals, empty arrays, generated keys and absolute(0) retain original results/independent observations below | No block: unconfirmed vendor attribution, no compensation; unknown attribution stays unknown |
| Extra suite requirements | Four type-existence requirements and internal procedure counts; retained SUITE failures, five is a subset of NOT_EVALUATED=8 | No block: neither universal JDBC violations nor passes |
| Environment prerequisites | Public provider classes/constructors, scope privileges and system package; other modes/intermediate driver versions unverified | Outside the registered scope; new environments need authorization and preparation |
| Missing evidence | Older 249/21 FULL original unavailable; no new 8.1.3.62 live run this review; some patch documentation unknown | No block for the verified historical combination; do not register old version or claim new end-to-end live acceptance |

### Eligibility and followups

New CLI runs require official trust, formal run kind, runtime identity matching manifest regexes and complete registered version tokens
for formal_eligible. Only the 8.1.3.62 combination is registered; the supplied 8.1.2.192 may still run
ordinary tests but gains no formal-combination eligibility from adapter promotion. Version tokens may
appear in product descriptions, but extensions using digits, letters, dots, hyphens or plus signs do not match.

| Dimension | Project-maintained validation scope | Enforced checks |
|---|---|---|
| Database version | 8.1.3.162 | Java admission range; CLI matches complete registered version tokens |
| Driver version | 8.1.3.62 | Java admission still allows 8.1.2.192–8.1.3.62; CLI formal eligibility matches only registered 8.1.3.62 |
| Driver SHA-256 | Fingerprint above | Actual artifacts are fingerprinted, but formal_eligible does not compare against the registered fingerprint |
| COMPATIBLE_MODE | 0 | Not checked by formal_eligible; regression mode evidence is an operator attestation |
| CASE_SENSITIVE | 1 | Instance flag not checked by formal_eligible; exact schema validation is not instance-flag validation |
| Java | 21 | Maven requires JDK 21; formal_eligible does not compare the combination's Java/patch version |

Thus `formal_eligible=true` does not prove programmatic verification of the entire precise combination.
Reports retain combination declarations, runtime identity/version, Java information and artifact evidence;
declarations are not enforced observations. Operators must check unenforced boundaries; comparisons
separately need matching versioned environment evidence. Old reports, adapterSnapshot and eligibility stay
unchanged. Remaining work is targeted API-precondition/behavior review, patch documentation or new
combinations in a separately authorized dedicated environment, not another mechanical FULL or waiting
for every failure to disappear.

<a id="dameng"></a>
## Dameng history, current implementation and retests

The following reviewed conclusions were moved from Usage. Public sources are the manifest above and this text; referenced local QA is not in the repository and is not a downloadable original report.

References below to “this run” or “current” belong to each original observation date. Current repairs and the later CUSTOM retest are separate and do not backfill historical FULL.

### Historical baseline with driver 8.1.2.192

Historical baseline metadata (driver 8.1.2.192): **DM DBMS 8.1.3.162**, connected driver **8.1.2.192**, Driver API **8.1**,
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
That historical evidence is limited to DM DBMS 8.1.3.162 + driver 8.1.2.192. Standalone
reproduction does not mean a vendor defect is confirmed. See the vendor's [DM8 JDBC guide](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html);
the live manual is not a retained manual for the exact patch release.

<a id="dm-independent-full"></a>
### Independent acceptance with driver 8.1.3.62

The separate **2026-11-04** acceptance used **DM DBMS 8.1.3.162 + driver 8.1.3.62**
(JAR SHA-256 `327bade775f4f15b97618bc9736a4625def52701cf53f5ef5ad72580797e6e45`),
Java 21, Driver API 8.1/JDBC 4.0, `COMPATIBLE_MODE=0`, `CASE_SENSITIVE=1`, null catalog.
No additional connection Properties were needed; the driver's effective `compatibleMode` default
remains unconfirmed. An incorrectly cased schema URL was rejected with SQLState `3F000` / vendor
code `-2103` during `ESTABLISHING_CONNECTION`; read-only metadata established the exact stored name.
The application does not repair case or create a replacement schema.

Real upload, isolated validation, draft/saved/edit probes, encrypted save, restart/readback,
SSE progress, result pagination/filtering, JSON report and bilingual Chrome connection/report pages
were checked. CORE selected 153: **151 passed, 2 failed**. The new FULL selected 270:
**254 passed, 16 failed, 0 skipped, 0 aborted**. Its disjoint result review is 254 conforming,
8 contract violations, 3 other JDBC outcomes not evaluated, and 5 unmet extra suite requirements
(the raw `NOT_EVALUATED=8` includes those five). Allowed unsupported, preparation/environment,
and missing-provider counts are all zero. The three unassessed failures are setArray (`22000/-3301`),
absolute(0) (`HY107/6020`) and indexed batch keys (`HY096/6007`); their vendor attribution remains
unconfirmed. The five suite failures are ARRAY/REF/STRUCT/ROWID metadata presence and internal
procedure update-count exposure. Neither task completion nor the 254 passes establishes complete
JDBC compatibility; the earlier FULL and followups keep their original statistics.

CleanupEvidence reports **CORE 18/18** and **FULL 41/41** journaled creation attempts deleted,
no retained task objects and the existing schema retained. Metadata for all 912 pre-existing objects
(name/type/object ID/last DDL time) matched the initial inventory. Cleanup used exact owned objects,
without DROP SCHEMA, CASCADE, prefix scans or business-object DDL/DML. Connection/history/report
readback remained available after another Server restart. The temporary platform and test services
were session-owned and removed after verification; the supplied driver and existing resources were preserved.

At the 2026-11-04 acceptance DM stayed **candidate** (see [current acceptance](#dm-platform-acceptance)). Runtime DBMS admission remains exactly 8.1.3.162; the numeric driver range
8.1.2.192–8.1.3.62 preserves the old endpoint and admits the newly observed release. Intermediate
releases, other DM8 patches, modes and driver/JDK combinations are **unverified**. The manifest's
qualification records the boundary without promoting the adapter or changing historical reports.
Driver-owned providers require public matching classes and public no-argument constructors; absent
classes/interfaces/constructors are explicit prerequisites. Their real JDBC calls retain failures.

<a id="dm-capabilities"></a>
### Historical capability audit

The capability audit below concerns this new combination. Values remain historical declarations,
not guarantees or automatic standard-test skips. Tagged counts overlap and must not be summed.
Provider declarations default false; explicit provider configuration made the three effective flags
true for this run. No failed behavior was hidden by changing capability flags.

| Capability | Declared | Observed boundary |
|---|---|---|
| `array_type` | `true` | 6 passed; setArray and empty collections fail; metadata is a suite requirement |
| `ref_type` | `true` | Only type metadata checked (suite failure); REF object behavior unverified |
| `struct_type` | `true` | Only type metadata checked (suite failure); Struct object behavior unverified |
| `rowid_type` | `false` | Type metadata suite requirement fails; independent ROWID round-trip/binding/update cases pass |
| `sqlxml` | `true` | 10 tagged cases pass; no all-version claim |
| `rowset` | `true` | 9 tagged cases pass |
| `lob_objects` | `true` | 10 tagged cases pass; Blob readAllBytes round-trip fails |
| `lob_locator_updates` | `true` | 1 tagged locator-update case passes |
| `callable_inout` | `true` | 3 tagged cases pass; procedure parameter ordinal assertion fails |
| `ref_cursor` | `true` | 1 native OUT cursor case passes |
| `batch_generated_keys` | `false` | 2 tagged cases pass; keys requested by column index fail |
| `generated_keys_by_index` | `true` | Column-index batch path fails (HY096/6007); other paths not generalized |
| `multiple_result_sequences` | `true` | Internal procedure update-count exposure is an unmet suite requirement |
| `data_source` | `false` | Configured public provider: 8 tagged passes, getParentLogger null assertion fails |
| `connection_pool` | `false` | Configured public provider: 1 pass, replacement-handle invalidation assertion fails |
| `xa_data_source` | `false` | Configured public provider: 4 tagged cases pass |
| `statement_cancel` | `true` | DBMS_LOCK.SLEEP interruption assertion fails; other SQL shapes unverified |
| `query_timeout` | `true` | 2 tagged cases pass; actual DBMS_LOCK.SLEEP timeout assertion fails |
| `network_timeout` | `true` | Getter/setter round-trip passes; actual stalled-network deadline unverified |
| `parameter_metadata` | `true` | 9 tagged cases pass; callable ordinals remain a separate failure |
| `updatable_result_set` | `true` | No direct updatable ResultSet contract coverage in this catalog; unverified |
| `scrollable_result_set` | `true` | 2 tagged fetch-window cases pass; absolute(0) fails independently |

<a id="dm-attribution"></a>
### Attribution of the recorded FULL failures

The following independent reproductions and manual review supplement the 254/270 FULL above;
**they do not replace its failures, classifications or counts, and are not a new FULL**.
Both CORE failures (`connection.enable_auto_commit_commits_pending_work`, `result_set.absolute`)
are included. The boundary remains DM DBMS 8.1.3.162, driver 8.1.3.62, Java 21.
A separate connection observed both public driver configuration and effective connection
`compatibleMode=0`, evidence not captured in the original FULL. The live DM guide describes
`autoCommitSpecCompliant`, but this JAR's public Configuration collection has no such entry;
its documented default cannot be assumed for this patch or repaired by adding an unknown property.

“Violation” and “not evaluated” below are the **original automated classifications**. Manual API
attribution does not rewrite them.

| Case ID | Test method | Expected behavior and independent observation | Attribution / missing evidence |
|---|---|---|---|
| `connection.enable_auto_commit_commits_pending_work` | `TransactionBehaviorTest.testEnableAutoCommitCommitsPendingWork` | Switching to true should commit immediately; flag becomes true but observer sees the row only after another writer SELECT | Violation; delayed-commit driver concern, patch-matched transaction documentation needed |
| `statement.actual_query_timeout` | `ExecutionControlTest.testActualQueryTimeout` | One-second timeout should bound execute; five-second SLEEP completes normally in 5007 ms | Violation; execution-control concern for this SQL shape; patch/package interruptibility details needed |
| `statement.cancel_running_statement` | `ExecutionControlTest.testCancelRunningStatement` | Suite expects interruption; cancel invoked at 508 ms returns normally but execute completes at 5013 ms | Violation; JDBC cancel is conditional on DBMS/driver abort support; that support for this package is unconfirmed |
| `datasource.get_parent_logger` | `DataSourceTest.testGetParentLogger` | JDBC 4.1 returns a Logger or explicit optional unsupported exception; driver returns null | Historical violation; current attribution not evaluated: API introduced in Java 7/JDBC 4.1, while this driver advertises 4.0. Null is not a conforming 4.1 return, but patch-specific 4.1 applicability/support remains unconfirmed |
| `datasource.pooled_replacement_invalidates_previous_handle` | `PooledConnectionBehaviorTest.testPooledReplacementInvalidatesPreviousHandle` | New logical handle closes the old one; driver returns the same proxy and old handle still queries | Violation; driver handle-lifetime concern, JDBC specification §11.4 supports the assertion |
| `blob_clob.blob_stream_round_trip` | `BlobObjectTest.testBlobStreamRoundTrip` | Read 307200 bytes; readAllBytes returns only 16384 with more data still readable | Violation; DmdbInputStream returns -1 for zero-length read (required 0); getBytes/chunked reads are complete, not assertion replacements |
| `callable_statement.procedure_parameter_metadata` | `ProcedureBehaviorTest.testProcedureParameterMetadata` | Parameter ordinals should be 1/2/3; actual 0/1/2 with correct IN/INOUT/OUT modes | Violation; actual outputs 17/14 are correct; metadata ordinal concern, never normalize by adding one |
| `advanced_type.empty_array` | `AdvancedTypeTest.testEmptyArray` | Non-NULL empty collection should materialize an empty Java array; non-null wrapper/wasNull=false but getArray returns null | Violation; driver materialization concern; zero rows from array ResultSet do not replace the Java-array contract |
| `advanced_type.set_array` | `AdvancedTypeTest.testSetArray` | Original createArrayOf(INTEGER) fails with 22000/-3301 before setArray | Historically not evaluated; setter fixture corrected and formal CUSTOM passes binding, one-row insert and exact [10,20,30] readback. Built-in factory/metadata naming discrepancy remains unresolved |
| `prepared_statement.batch_keys_by_column_index` | `BatchBehaviorTest.testBatchKeysByColumnIndex` | Requested ID keys; batch succeeds but returns NAME/VARCHAR, then getLong raises HY096/6007 | Not evaluated; returned-column concern located; ID-by-name control succeeds; internal index basis remains unconfirmed, no replacement with names or index 0 |
| `result_set.absolute` | `ResultSetTest.testAbsolute` | Actual type 1004 and scroll support true; only absolute(0) raises HY107/6020 | Not evaluated; literal-row query also reproduces, excluding fixture/forward-only; standard requires false and beforeFirst |
| `jdbc_type.array_type_metadata` | `JdbcTypeCoverageTest.testArrayTypeMetadata` | Suite requires type 2003 in getTypeInfo; absent | Not evaluated/SUITE; type presence is extra, not a requirement that all databases support ARRAY |
| `jdbc_type.ref_type_metadata` | `JdbcTypeCoverageTest.testRefTypeMetadata` | Suite requires type 2006; absent | Not evaluated/SUITE; does not disprove all REF operations |
| `jdbc_type.struct_type_metadata` | `JdbcTypeCoverageTest.testStructTypeMetadata` | Suite requires type 2002; absent | Not evaluated/SUITE; does not disprove all Struct operations |
| `jdbc_type.row_id_type_metadata` | `JdbcTypeCoverageTest.testRowIdTypeMetadata` | Suite requires type -8; absent | Not evaluated/SUITE; independent ROWID operation passes do not satisfy type metadata presence |
| `statement.result_sequence` | `MultipleResultsTest.testResultSequence` | Results 11/22, stored value 1 and traversal termination pass, but updateCounts=[] | Not evaluated/SUITE; exposing the internal one-row update is extra; JDBC does not require every internal statement's count |

The three ordinary SQL exceptions remain automatically not evaluated because SQLState alone
cannot establish a vendor violation. Failed API, cursor type, type names and key-column metadata
are now recorded independently; patch-matched vendor documentation/confirmation is still missing.
The eight assertion failures also do not automatically establish confirmed vendor defects.
The writable reproductions created only five journaled objects, all deleted; metadata for 912
pre-existing objects matched exactly. Read-only probes created no test objects. No FULL was rerun,
capability changed, assertion relaxed or case skipped.

<a id="dm-current-implementation"></a>
### Current implementation and subsequent targeted retest

Project fixes: prepare DM's native collection before the standard factory and pass its exact
quoted, qualified fixture name for the setter scenario; observe the cancel thread regardless of
execute outcome; preserve the original primary error and phase with cancel/shutdown failures as
supplemental evidence. Both thread waits are limited to five seconds. Worker diagnostics traverse
cause/nextException/suppressed with identity deduplication, at most 10 records, a queue of at most
16 entries and at most 2000 characters per message/SQLState after redaction. Supplemental branches
do not supply primary SQLState. The earlier change corrected only getParentLogger's API introduction metadata to 4.1;
retain the driver's observed JDBC 4.0 and optional-unsupported policy, without version-based skipping.
Clarify the Chinese suite-requirement terminology. UI, JSON reports and
regression comparisons retain FAILED, NOT_EVALUATED and SUITE: five is a subset of eight, not
an additional count, JDBC violation or pass. Existing generic regression tests and added bilingual
five-case presentation tests verify the boundary.

<a id="dm-set-array-retest"></a>
#### set_array CUSTOM retest

The formal setter retest is separate run `f816ac85-c189-47c9-90df-4a799fc01d89`:
CUSTOM 1 selected/1 passed, no failures/skips/aborts, cleanup SUCCESS 3/3 (two types and one table),
912 existing objects unchanged. The original path used literal `INTEGER` before deferred preparation;
no generated identifier lost its schema, quotes or case. The earlier independent program instead
created one VARRAY and a simple id/array table, used an exact quoted collection name, and did not
read back the inserted value. The formal path uses both existing collection types and the original
identity/name table, data and assertions; neither path changes autocommit or transaction mode.
Both still use createArrayOf and setArray. PostgreSQL/other adapters retain the factory-before-fixture
path. This is a setter-fixture repair, not a finding that the collection name is a conforming SQL
element type: Connection's Java 21 typeName contract describes the element SQL type and refers to
Array.getBaseTypeName. INTEGER failure and the driver's unquoted collection base name remain
separate evidence requiring patch-matched documentation; the successful setter does not resolve them.

Current attribution therefore differs from the historical labels: set_array has a repaired and
formally retested fixture; get_parent_logger remains unassessed for 4.1 applicability; cancel still
needs package/driver abort-support evidence. Other driver behavior concerns, including literal-query
absolute(0) and wrong generated-key columns, remain concerns rather than confirmed vendor defects.
The five suite requirements are unchanged. No other case was formally rerun in this finish check;
manual observations are not substituted for formal outcomes or a new aggregate pass rate.

Reviewed local evidence is retained under ignored `.local/qa/dm-attribution/`: `INDEX.json` records
SHA-256 hashes; `attribution.json` separates each original exception/classification, current attribution,
project repair, formal retest and remaining uncertainty; `formal-report.json` and
`formal-object-ledger.yaml` retain the new result/ownership evidence. `previous/` preserves earlier
reproductions byte-for-byte; `acceptance/` preserves historical reports; `REPRODUCTION.md` describes
the environment-only, dedicated-scope invocation. These local materials are not distributed with Git.
Only these reviewed conclusions and official source references belong in formal documentation.

Contract sources: Java 21 [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html),
[Statement](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html),
[ResultSet](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html),
[DatabaseMetaData](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html),
[Array](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Array.html),
[CommonDataSource](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/CommonDataSource.html),
[InputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStream.html), and
[JDBC 4.3 specification §11.4](https://download.oracle.com/otn-pub/jcp/jdbc-4_3-mrel3-spec/jdbc4.3-fr-spec.pdf).
The live DM guide is not a patch-matched DBMS/driver manual; full release notes and vendor attribution
for this combination remain unavailable. This is the pre-promotion attribution record; see [current acceptance](#dm-platform-acceptance). Other versions and modes are not covered.
