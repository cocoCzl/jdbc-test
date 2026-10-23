# Development

English | [简体中文](development.zh-CN.md)

User operations belong in [Usage](usage.md); runtime configuration, backup and upgrades in
[Deployment](deployment.md). This guide covers source architecture and maintenance.

<a id="local-startup"></a>
<a id="local-source-startup"></a>
## Local source startup

Use JDK 21, Node >=22.12/npm and the Maven wrapper. Prepare a dedicated platform PostgreSQL database
and login with migration permissions; this is separate from any target database. From the source root:

```sh
umask 077
cp -n .env.example .env
# Edit the actual .env datasource URL, username and password before starting.
./jdbc-test dev
```

PowerShell: copy with `if (-not (Test-Path '.env')) { Copy-Item .env.example .env }`, edit, then
`./jdbc-test.ps1 dev`. Never overwrite an existing configuration. The launcher reads literal `.env`
values, checks platform authentication/state and generates a persistent Key only for a new empty
platform. Existing state needs its original Key. Never source/eval `.env`; see [configuration rules](deployment.md#configuration-rules).

The command builds Java/Suite/Worker, resolves the exact current POM-version Worker, installs web
dependencies when needed, starts the source backend via Maven and Vite, and waits for readiness.
Defaults are frontend `http://127.0.0.1:5173` and backend `http://127.0.0.1:8080`.
`SERVER_PORT` and `JDBC_TEST_WEB_PORT` select ports; the Vite proxy follows the backend.
Only the backend receives credentials/Key. Data defaults to source-root `data/` or
`JDBC_TEST_DATA_DIRECTORY`. Occupied ports abort without killing unrelated services.

Reuse `./jdbc-test dev` daily with the same config/Key/data. Frontend edits use HMR; Server changes
need restart, and Suite/Worker changes rebuild on the next launch. Ctrl+C stops owned descendants;
a failed side stops the other. Platform PG and saved data remain. Startup never runs target tests.

### Debug and manual launch

`./jdbc-test dev --debug` exposes a loopback debugger at `127.0.0.1:5005`. Import `pom.xml` in IDEA
with JDK 21, use Remote JVM Debug / Attach and `jdbc-test-server` sources. Do not launch a second
backend. Main class: `com.jdbctest.server.JdbcTestServerApplication`. The IDEA UI flow remains unverified.

For manual launch, first `./mvnw -DskipTests install`; supply datasource/Key to the actual backend
process and set `JDBC_TEST_WORKER_EXECUTABLE` to the current version's Worker `-all.jar`, then run
`./mvnw -pl jdbc-test-server spring-boot:run`. Manual Maven/IDEA do not load `.env`.
The manual frontend uses `npm ci` then `npm run dev` in `jdbc-test-web`; set nonsecret
`JDBC_TEST_DEV_API_URL` in that Vite process for a nondefault backend. Keep secrets out of shared
IDE settings/arguments and stop each manually owned process.

## Architecture

```text
React / Vite → REST + SSE → Spring Boot Server
                                ├→ Platform PostgreSQL
                                ├→ Driver storage / Run workspaces
                                └→ Worker JVM → external driver → Target database
```

| Path | Responsibility |
|---|---|
| `jdbc-test-core` | Catalog, selection, request/report models and utilities |
| `jdbc-test-suite` | JDBC scenarios, JUnit extensions and packaged SQL |
| `jdbc-test-worker` | Isolated loading/execution, validation, probes and NDJSON |
| `jdbc-test-server` | REST, persistence, scheduling, SSE and production static hosting |
| `jdbc-test-web` | React/TypeScript/Vite frontend |
| `adapters`, `ddl`, `dml`, `pool`, `compatibility/v1` | Built-in declarations, SQL/pool assets and scenario inventory |
| `scripts` | CLI, reports, regression and packaging |

Server depends on Core, never Suite or uploaded driver classes. Worker bundles Core/Suite/JUnit/SQL;
target drivers stay external. CLI uses Maven/JUnit and shares scenarios and safety contracts.
Flyway owns platform migrations V1–V12; Hibernate validates, never generates the schema. Do not edit
historical migrations. Password envelopes use AES-256-GCM and the persistent 32-byte Master Key.
Queued credentials are separately encrypted and removed on handoff/terminal/recovery paths.
Driver files live in `<data-dir>/drivers/`; task workspaces in `<data-dir>/runs/`.

## Java formatting

The unchanged source is [intellij-java-google-style.xml](../code-style/intellij-java-google-style.xml)
(SHA-256 `f25e4a8d94f2b2d33dd943cb672f570b64fb89e680df63d3795ad244505d0d26`).
[java.xml](../code-style/java.xml) projects its Java settings into an IDEA scheme; other languages
are excluded. It adds explicit reading rules: expand nonempty methods, blocks, lambdas and simple classes,
put declaration annotations on separate lines, use continuation indentation for record components,
wrap long resource lists/enum constants and local declaration annotations, and preserve
blank-line whitespace in text blocks.
Use 4-space indentation, 4-space continuation, no tabs and a 100-column target. Keep long
SQL/URL/literal exceptions intact. The source's forced control braces are retained. Imports use
static imports first, a blank line, then ordinary imports. Naming, exceptions, logging and security
continue to follow project conventions and the Alibaba guidelines.

In IDEA, import `code-style/java.xml` through Settings → Editor → Code Style → Java →
Scheme → Import Scheme → IntelliJ IDEA code style XML, then select `JdbcTestGoogleJava`.
Reformat only the four modules' Java source roots; use Optimize Imports separately with this scheme.
The Java-only `.editorconfig` mirrors indentation and margin settings.

Save edits and exit IDEA normally before using its native command-line formatter:

```sh
scripts/format_java.sh apply
scripts/format_java.sh check
# Optional: set IDEA_HOME to the installation containing bin/format.sh (macOS: app/Contents).
# Both commands also accept individual module/src/main/java/...java or src/test/java/...java paths.
```

Both commands use the same IDEA engine/XML; `check` is its non-writing `-d` mode.
The explicit development source `scripts/CheckJavaRules.java` is also accepted; default roots remain
the four modules, so use its exact path when checking the checker itself.
The formatter does not optimize imports; run Optimize Imports separately. Existing individually
baselined wildcard imports are retained. Do not substitute another engine,
bypass a running IDE's instance lock, or automatically rewrite sources during Maven builds.

### Java rules and verification boundaries

Formatting priority is the project's `code-style/java.xml`, derived from the retained user XML;
`.editorconfig` mirrors only its Java indentation/margin settings. Do not replace the native IDEA
engine with google-java-format, Spotless or another formatter, or format other languages with this XML.
For naming, exceptions, logging, collections, concurrency and security, use the applicable sections
of the Alibaba Java Coding Guidelines, **Huangshan edition 1.7.1 (2022-02-03)**, available from the
[official p3c repository](https://github.com/alibaba/p3c). Project-specific contracts take precedence;
format conflicts are resolved by this project's XML. The edition is a review reference, not a claim
that an installed plugin or the checks below implement the complete handbook.

Review names and responsibilities, specific exceptions and preserved causes, meaningful logging
without credentials, collection/generic contracts, thread/resource ownership and input validation.
JDBC scenarios may deliberately exercise deprecated APIs or cause SQL exceptions; Worker stdout is
the NDJSON protocol. Do not suppress these behaviors, redirect the protocol to ordinary logging,
swallow failures, or fabricate compatibility results to satisfy a general business-code rule.
Remove only proven redundant suppressions. Necessary narrow suppressions need a reason; never add
`SuppressWarnings("all")`, `noinspection` or disabled inspections to hide warnings. Review actual
source rather than folded IDE displays. Do not add meaningless logging to an intentionally ignored
exception or change its propagation merely to satisfy a syntax check.

The development-only checker uses JDK 21's public compiler tree API for parsing only, with annotation
processing disabled. It does **not** run attribution/type analysis or load application classes.
Run it in a source checkout. It scans every `.java` in the four modules' `src/main/java` and `src/test/java`, plus
`scripts/CheckJavaRules.java`; it has no file/directory exclusion mechanism.

| Rule | Violation | Legitimate handling / limits |
|---|---|---|
| `wildcard-import` | `import java.util.*;` or a static `.*` import | Explicit imports pass. Existing occurrences are individually baselined. It cannot identify unused imports or resolve imported symbols. |
| `empty-catch` | A catch body with zero AST statements, including `{ /* explanation */ }` | A real recovery, rethrow or return statement passes this syntax rule, but its correctness requires review. Legitimate ignored exceptions need individual review; comments do not waive the rule. It cannot detect useless statements or determine whether swallowing is safe. |
| `suppress-all` | Literal `"all"` in `@SuppressWarnings("all")`, `value = "all"` or an array | Narrow `"unchecked"`/`"deprecation"` are not violations; redundancy requires semantic/IDE review. Qualified `java.lang.SuppressWarnings` and parenthesized literals are recognized. Javac also folds adjacent string literals during parsing. Named constants and general expressions are not resolved. A same-named custom annotation can be a false positive because matching is syntactic. |

Comments, ordinary strings and text blocks containing apparent violations are not code findings.
All findings fail an unbaselined scan. Normal CI rejects new or increased findings and stale baseline
entries. The checked-in `code-style/java-rules-baseline.json` records only reviewed occurrences as
`[relative path, rule, enclosing class/method signature, AST detail, count]`; line numbers are only
diagnostics. This retains historical wildcard imports/empty catches without changing business code.
Adding another occurrence in the same declaration exceeds the count; moving one to another declaration
changes its identity. Fixing an occurrence requires manually removing/reducing its entry. There is no
baseline-writing mode: never regenerate it or increase allowances to make a failing change green.
An intentional new exception requires explicit review of that exact occurrence and its rationale.

```sh
python3 -B -m unittest discover -s scripts -p 'test_java_rules.py' -v
python3 -B scripts/check_java_rules.py
# Read-only inventory, ignoring baseline: exits 1 if any findings exist.
python3 -B scripts/check_java_rules.py --inventory
# Native formatter: check only specified Java files; save and exit IDEA normally first.
scripts/format_java.sh check scripts/CheckJavaRules.java
git diff --check
```

Checker exit codes: **0** = no new/stale findings, **1** = rule/baseline mismatch,
**2** = invalid input, parse error, missing JDK tool, compilation/execution error or timeout.
All eight source roots must exist and contain Java sources; unreadable directories, source symlinks, missing paths, malformed or
duplicate baseline entries and inconsistent tool output fail closed. Temporary compiler outputs are
removed on success/failure. The tools are not Maven/application runtime dependencies.
CI runs both checker contract tests and the full scoped source scan. Contract fixtures cover records,
text blocks, comments/strings, annotation forms, baseline counts and execution failures. They are
syntax evidence, not JDBC compatibility evidence. CI currently has no configured IDEA native runner.

Report four results separately: limited static rules, checker contract tests, IDEA native formatting,
and `git diff --check`. Neither compilation nor these rules proves complete Alibaba compliance,
unused imports, redundant unchecked suppressions, naming/exception/logging/concurrency correctness,
or full formatting. Missing IDEA or an instance lock means native formatting is **BLOCKED**, never
passed; do not kill the IDE, delete locks, change its configuration or repeatedly retry. After normal
IDE exit, rerun the specified-file command above. Optimize Imports and IDE inspections remain separate.

Commit only reviewed code, automated tests, necessary fixtures/check configuration and formal project
documentation. Task sheets, execution logs, audit reports, screenshots, evidence and review ZIPs stay
in the existing ignored `.local/` directory or outside the repository; never add `docs/quality-evidence`.
Local `AGENTS.md` stays ignored/untracked unless separately approved. Preserve existing workspace
changes, configuration, Keys, data and attachments. Clean only resources created by the verification
session, including on failure; do not rewrite history or push/PR/tag/release without authorization.


## Adding database support

`adapters/catalog.json` is the single built-in index. Each `adapters/<id>/adapter.json` owns its name,
trust, identity/version rules, capabilities, namespace and assets. Java and Python strictly validate
UTF-8 JSON, duplicate keys, types, paths, finite values and size/depth limits. Core builds one immutable
registry without loading Suite or drivers. Shared fixtures live in
`jdbc-test-core/src/test/resources/adapter-contract`. Unknown extensions are retained, not automatically executed.
Identity regexes must work in both Java and Python; version declarations are not compatibility evidence.

For a product reusing implemented SQL behavior, add a unique manifest, truthful identity/capabilities
and `candidate`/`local` trust, then register its ID. Reuse `assets.directory` or add `ddl/<asset-id>`,
`dml/<asset-id>` and `pool/<asset-id>.yaml`. No product enum/frontend branch is needed for a generic
registry entry. Driver presets are independent editable form hints, not adapter identity or capability rules.

A distinct dialect requires implemented `SqlDialect` scope, quoting, URL parsing, fixture lookup and
ownership cleanup with failure tests. Current finite behaviors are `postgresql`, `mysql`, `oracle`,
`sqlserver` and `dm`; product identity remains independent. DM preserves exact schema case and requires
no catalog, unlike Oracle folding or MySQL catalog selection. Audit shared logic for existing products.
A product enum alone does not implement support. Never change public assertions or deselect FULL
cases to manufacture compatibility. Real authorized verification is required before widening claims.

Maintain assets in source; custom adapter/SQL package upload and JSON imports are retired.
Multi-JAR drivers use Web upload or a CLI local directory. The CLI Maven resolver loads the primary
JAR only, not a complete transitive bundle. No online Web Maven importer exists.
[Examples](../examples/README.md) cover explicit compatible-dialect selection.

## Adding scenarios and preparation

Add the test in Suite and its stable entry in the scenario inventory. Keep category, JDBC baseline,
applicability and execution prerequisites explicit. Use the shared selector and catalog, never a
second hard-coded count. FULL selects all applicable scenarios, CORE uses maintained inventory IDs,
and CUSTOM intersects modules/includes before exclusions. Empty, stale or unknown selections fail.
Capability false does not skip a standard call; vendor-extension applicability is independent.
There are currently no implemented vendor API scenarios.

`UseSqlScripts` prepares resources lazily per selected method/class; an empty method annotation can
override class scripts. Keep no-table methods independent. Use explicit placeholders and ownership
APIs for quoted, qualified objects and cleanup; never arbitrary word rewriting or broad DROP.
Validate native fixture shape before asserting behavior. SQL NULL observations and factory calls do
not replace ARRAY/ROWID/REF_CURSOR round trips. Missing fixtures/dependencies/permissions are
preparation or environment problems. Preserve original SQLState, cause and phase.

Use precise method-level `JdbcContracts` decisions and separate `SuiteRequirementAssertionError`
for extra suite requirements. Do not infer permission from a flag, SQLState or exception message.
See [JDBC reference](jdbc-capability-contracts.md). Track cleanup independently from case completion;
missing historical evidence remains unknown. New real checks create new reports, never rewrite old FULL results.

### Driver-owned providers

Configure actual public classes/setters; provider absence is an unmet prerequisite. Example CLI/runtime YAML:

```yaml
db:
  data_sources:
    dataSource:
      className: org.postgresql.ds.PGSimpleDataSource
      urlProperty: URL
      userProperty: user
      passwordProperty: password
    connectionPoolDataSource:
      className: org.postgresql.ds.PGConnectionPoolDataSource
    xaDataSource:
      className: org.postgresql.xa.PGXADataSource
```

API `dataSources` uses the same definitions. Defaults are `URL/user/password`; additional properties
map to public scalar bean setters. Provider tests use uploaded driver implementations, not the
platform connection facade. XA needs database prerequisites. CLI `db.password_env` resolves a single
variable literally; unset differs from empty, and simultaneous literal/environment passwords fail.

## API and persistence contracts

Resource DTOs have no universal success envelope. Lists use bounded `page/size` (default 20, max 100)
and `{items,page,size,totalElements,totalPages}`; errors are RFC Problem Details with a stable code.
Creation returns 201/Location, deletion 204, protected historical references 409.

| Routes | Operations |
|---|---|
| `/api/drivers`, `/api/drivers/presets`, `/api/drivers/{id}` | Multipart upload/list, suggestions, read/PATCH/delete |
| `/api/drivers/{id}/validate` | POST isolated validation |
| `/api/adapters` | GET built-in connection options |
| `/api/connections`, `/api/connections/{id}` | Create/list, read/PATCH/delete |
| `/api/connections/test`, `/api/connections/{id}/test` | POST draft/saved probes |
| `/api/test-catalog` | GET profiles/modules/current counts |
| `/api/runs`, `/api/runs/{id}` | Create/list, read snapshots/progress |
| `/api/runs/{id}/cancel`, `/api/runs/{id}/events` | POST cancel, GET SSE |
| `/api/runs/{id}/results`, `/report`, `/report.json` under the run | Results, summary/failures and JSON evidence |

Driver upload uses multipart `metadata` and repeated `files`; defaults 20 artifacts, 100 MiB each,
250 MiB total. Size/SHA-256 and sorted-digest package fingerprints identify immutable binaries.
`loadedArtifact` records the actual defining JAR and independent manifest fields. Driver API integer
versions/compliance are declarations, not connected release evidence. Invalid protocol metadata fails closed.

Connection passwords are write-only (`hasSavedPassword`). `savePassword` saves/replaces;
`clearSavedPassword` conflicts with any supplied password, including empty. Omitted fields preserve
saved values, explicit empty clears scope or selects an empty password according to the field.
Probes use Worker `Driver.connect`, read best-effort metadata and read-only Oracle/DM configuration,
then close without test SQL. Ordinary validation only loads the driver. Used packages/profiles cannot be deleted.

Runs freeze scope/consent, selection, adapter execution and nonsecret settings at enqueue. Optional
`name` is limited to 200 characters; old unnamed runs use a display fallback. Scheduler is local FIFO
with bounded Worker concurrency. Locks prevent terminal/cancel races. Filter groups are WAITING,
IN_PROGRESS, COMPLETED, ABNORMAL and CANCELLED; exact `status` and `statusGroup` cannot be combined.
Filters and counts apply before pagination. Results combine status and module with AND and a stable ID tie break.

Normal execution progresses QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED.
SUCCEEDED means processed, not all cases passed. Only validated exit 4/TEST_FAILURE with no independent
execution issues and successful cleanup can be normally completed with unsuccessful cases.
Other execution/report/cleanup failures remain abnormal. The Server cross-checks selection, events,
report, counts and cleanup. Persisted reconstructions identify `source` and `workerReportValidated`.
Legacy outcomes are interpreted read-only only when retained evidence is sufficient; raw reports remain unchanged.

<a id="worker-protocol"></a>
## Worker protocol

Deploy matching Server and Worker. Protocol 2 reads one stdin JSON request and writes stdout NDJSON;
credentials never go in argv. A per-run `URLClassLoader` with platform parent calls the selected
`Driver.connect` without global registration. Driver output cannot share the public protocol channel;
diagnostics are bounded/redacted, with the recognition limits documented in [Usage](usage.md).

| Mode | Purpose |
|---|---|
| Default | One run |
| `--describe-catalog`, `--catalog-smoke` | Catalog without target drivers; packaged-resource smoke |
| `--validate-driver` | Class loading and Driver metadata, no connection |
| `--probe-connection` | Read-only connection/metadata probe, no suite SQL |

See [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java).
Execution requires protocol 2, scope/consent, prefix `j` plus 20 lowercase hex digits, complete boolean
capability declarations and frozen adapter selection. Events cover run/module/case lifecycle, metadata
and cleanup. Wrong IDs, unexpected/duplicate results or incompatible protocol fail explicitly.
Report schema is 2.0.0 at `<workspace>/report/report.json`.
Worker exits: 0 completed (still validate report), 2 request/selection, 3 driver/connection setup,
4 execution/timeout, 5 catalog/workspace/report/internal error. These differ from CLI comparison exits.

Task budget (60–7200 seconds; UI default 1800) covers preparation/execution/cleanup. The default JUnit
method timeout is 60000 ms, SAME_THREAD; explicit `@Timeout` wins. Discovery and callbacks use the task
budget, not the method timer. Interrupt cannot force JDBC return. Never race a hung call with cleanup
or another case. The Worker exits at budget; Server allows 5 seconds plus configured termination grace
(default 2) before forced termination. Not-run/incomplete and uncertain cleanup remain explicit.

## Frontend maintenance

Preserve existing layout/interactions. Use shared `components/ui`, `components/common` and
`components/data-table`; Radix handles interactions and `foundation.scss` owns visual tokens.
TanStack Query owns server state; REST loads first, SSE updates the same cache, terminal events close
streams/refetch, and a 10-second fallback covers missed terminal events. Reconnects do not change task status.
Passwords stay in component memory, not URLs/storage/cache; diagnostics render as text.

Keep English/Chinese resources in `src/i18n` synchronized. `jdbc-test.locale` overrides supported browser
languages, then English; dates use browser-local time. IDs/API/raw reports stay untranslated.
Dashboard metrics describe loaded recent tasks, not all history. Do not invent backend statistics.
Retain the upstream CPA Keeper MIT notice and source/adaptation attribution in
`jdbc-test-web/public/third-party-notices.txt` when changing shared UI.

<a id="testing"></a>
## Checks

```sh
./mvnw clean verify
python3 -B scripts/run_contract_tests.py
npm --prefix jdbc-test-web ci
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web test
npm --prefix jdbc-test-web run build
npm --prefix jdbc-test-web run test:e2e
```

Default Maven tests do not use user databases. Optional `./mvnw -Pworker-e2e,server-integration verify`
uses disposable Docker databases; unavailable Docker may skip integration tests. Fixture-backed
Playwright is not a real target run. Use `PLAYWRIGHT_CHANNEL=chrome` for installed Chrome, or
`JDBC_TEST_E2E_PREVIEW=true` after build to verify built fixture assets.

`npm --prefix jdbc-test-web run test:e2e:real` requires a running matching Server/Worker, fresh platform
storage and dedicated target PostgreSQL. Supply `JDBC_TEST_E2E_DRIVER_JAR`, `DRIVER_VERSION`,
`TARGET_URL`, `USERNAME`, `DATABASE`, `SCHEMA`, `PASSWORD`, each with the same `JDBC_TEST_E2E_` prefix,
through private configuration/environment. Missing inputs fail rather than fall back to fixtures.
The explicit `history-readonly` project requires an already verified viewer, built preview and
`JDBC_TEST_HISTORY_READONLY=true`; it never creates tasks/probes. Do not use historical QA for writes.

Run checks appropriate to changed behavior; real database FULL is not required for documentation-only
changes. Record existing resources before verification, clean owned processes/containers/databases/
files after success, failure or cancellation, and preserve user/shared resources. Keep detailed evidence
in ignored local paths; summarize routine verification in the response. Update formal docs only when
user operations or maintenance change, directly in the corresponding existing guide. Keep the current
document structure; do not add separate documents for each development/acceptance round or database.

## Upgrade compatibility

V9/V10 and other historical migrations remain. Retired package tables/history are readable and not
rewritten. Historical SQL reports retain SQL_FEATURE and cannot be JDBC baselines. Old custom-adapter
connections need a built-in type or explicit compatible dialect for new tasks. Retired queued executions
fail with RETIRED_PACKAGE_EXECUTION; obsolete sqlPackageFingerprint requests fail SQL_PACKAGES_RETIRED.
Restart interrupts previous nonterminal runs with SERVER_RESTART. Server/Worker snapshots must match;
unknown retired package execution fields fail instead of falling back silently.
See [deployment upgrade/backup rules](deployment.md#operations) and [driver A/B regression](internal-regression.md).

<a id="known-limitations"></a>
## Known limitations

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
| Dameng | Candidate integration verified only for DM DBMS 8.1.3.162 + driver 8.1.2.192, including real Web save/edit/execution and FULL (249 passed, 21 failed). Followups fixed five original failures; eleven compatibility doubts and five extra suite requirements remain. Standalone reproductions are not confirmed vendor defects. See Usage for exact configuration/mode limits. |
| Platform launch validation | Windows host ACL/PowerShell process behavior and IDEA UI remain unverified. Standard Docker source build has no successful retained verification after a registry authentication timeout; alternate local-image execution is not source-build proof. |

Missing fixtures are not driver defects; legal unsupported is not a feature pass. REF/ROWID type
presence, automatic LOB persistence, invalid-call recovery and internal update-count requirements
remain separately scoped, as specified in [JDBC reference](jdbc-capability-contracts.md).
