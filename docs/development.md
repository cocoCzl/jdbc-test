# Development

English | [简体中文](development.zh-CN.md)

[Documentation](README.en.md) · [English home](../README.en.md)

Contents: [Local source startup](#local-source-startup) · [Architecture](#architecture) · [Adding database support](#adding-database-support) · [Adding scenarios and preparation](#adding-scenarios) · [API and persistence contracts](#api) · [Worker protocol](#worker-protocol) · [Java formatting](#java-style) · [Frontend maintenance](#frontend) · [Checks](#testing) · [Upgrade compatibility](#upgrade-compatibility) · [Known limitations](#known-limitations)

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

<a id="architecture"></a>
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
Flyway owns platform migrations V1–V15; Hibernate validates, never generates the schema. Do not edit
historical migrations. Password envelopes use AES-256-GCM and the persistent 32-byte Master Key.
Queued credentials are separately encrypted and removed on handoff/terminal/recovery paths.
Driver files live in `<data-dir>/drivers/`; task workspaces in `<data-dir>/runs/`.

<a id="adding-database-support"></a>
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
`dm`; product identity remains independent. DM preserves exact schema case and requires
no catalog, unlike Oracle folding or MySQL catalog selection. Audit shared logic for existing products.
The DM audit follows the same registry → Server snapshot → isolated Worker → Suite assets flow.
All eleven declared fixture areas and `pool/dm.yaml` exist; no Oracle fallback or second executor is used.
Schema identifiers retain the shared `[A-Za-z][A-Za-z0-9_]{0,62}` policy (numeric-only, leading underscore,
quoted delimiters and non-ASCII names are rejected). Each checkout sets and checks the exact name;
close errors are suppressed on the original scope failure. Only successful journaled CREATEs are
eligible for exact-object cleanup; uncertain CREATEs stay visible, without schema drops or scans.
The adapter API exposes immutable historical capability declarations and the current qualification
scope; it does not certify driver behavior. Public provider interface/constructor availability is a
prerequisite; actual constructor, setter and JDBC-call behavior still needs observation.
Connection-probe diagnostics retain bounded next-exception, cause and suppressed branches with
SQLState/vendor codes, redact credentials, and publish success only after the connection closes.

A product enum alone does not implement support. Never change public assertions or deselect FULL
cases to manufacture compatibility. Real authorized verification is required before widening claims.

<a id="adapter-acceptance"></a>
### Formal acceptance of a platform adapter

Existing rules: `official` means project-maintained integration; historical results are immutable,
and driver failures do not automatically disqualify the adapter. Minimum code validation requires
nonempty `validated_combinations` and safe evidence paths in Java/Python; the Python loader also
checks file existence. These checks do not review evidence content. Kingbase already has an official
combination with failures and preparation blocks; all-tests-pass is not a DM acceptance condition.

The maintenance standard made explicit here is to verify identity, artifact fingerprint, precise
version/mode/JDK/date, reviewable redacted provenance, execution and cleanup evidence; audit
upload/connection/scope/standard calls/terminal outcomes/presentation/ownership, and fix platform
defects within the accepted scope. Unverified contract preconditions retain failure and unknown
conclusions. Driver questions, extra suite requirements and out-of-scope environment prerequisites
stay separate. Known incorrect platform attribution, unauthorized cleanup or unverifiable combination
evidence blocks acceptance; exhaustive versions/platforms or all tests passing are not required.
See the [DM acceptance record](database-validation.md#dm-platform-acceptance).

CLI `formal_eligible` additionally requires formal run kind, runtime identity matching manifest regexes,
official trust, no experimental override and complete version-token matching. It does not enforce the
combination's mode, CASE_SENSITIVE, JDK or artifact fingerprint, or prove the entire precise combination.
See the per-dimension [acceptance boundaries](database-validation.md#dm-platform-acceptance).
Operators must preserve the registered scope, and comparisons separately require
versioned environment evidence. Status changes affect new runs only, never promote old reports;
unregistered combinations remain available for ordinary testing without formal eligibility.

For the read-only external DM smoke, first build the packaged Worker, then supply private environment
variables `DM_JDBC_JAR`, `DM_JDBC_URL`, `DM_USERNAME`, `DM_PASSWORD`, `DM_SCHEMA` and run:

```sh
./mvnw -pl jdbc-test-worker -am -Dtest=WorkerDmIT -Dsurefire.excluded.groups=none test
```

This opt-in integration test validates loading and the actual connected metadata; it does not prove
writable suite compatibility. The browser integration `e2e/dm.real.spec.ts` requires
`JDBC_TEST_DM_REAL=true`, explicit `JDBC_TEST_DM_CONNECTION_ID`/`JDBC_TEST_DM_RUN_ID`, `DM_SCHEMA`,
and a disposable platform URL. It probes/saves that connection and checks bilingual report/history
readback without creating another task. Use the normal task flow for authorized CORE/FULL and retain
each task's original result counts, observations and cleanupEvidence. Never commit private JARs or env files.

Maintain assets in source; custom adapter/SQL package upload and JSON imports are retired.
Multi-JAR drivers use Web upload or a CLI local directory. The CLI Maven resolver loads the primary
JAR only, not a complete transitive bundle. No online Web Maven importer exists.
[Examples](../examples/README.md) cover explicit compatible-dialect selection.

<a id="adding-scenarios"></a>
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

<a id="api"></a>
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

<a id="execution-and-query-boundaries"></a>
<a id="执行与查询边界"></a>
### Execution and query boundaries

SQL Server no longer supports new connections, probes or executions. `adapters/catalog.json` is the
active support index. Product/dialect enum values remain for historical parsing; existing connections,
run statuses, evidence and raw reports are not rewritten. New runs and queued frozen inputs enforce
retirement; old SQL Server inputs fail with `DATABASE_SUPPORT_RETIRED`. Contract checks tie packaged
resources and bilingual support declarations to the registry; driver default dialects and UI options use it.

Driver validation takes a configuration snapshot and monotonic revision in a short transaction.
Driver-class edits and every validation advance the revision; results use a conditional update.
Stale success/failure, competing validation and deletion return `DRIVER_VALIDATION_STALE` without
overwriting current state. Worker execution/admission never spans a database transaction. Artifacts
are immutable; names, default dialect and DataSource metadata do not change class-loading validation.

### SSE delivery

SSE queues only after commit, with 64 pending events per subscriber, independent virtual senders and
256 subscribers globally. The run row lock protects persistent event sequences and snapshot registration.
Snapshot sequences discard duplicates and reorder interleaving commit callbacks. Terminal sends precede
completion. Overflow disconnects that client; a new snapshot recovers persisted state. Streams expire
after 30 seconds and browsers reconnect; REST refreshes active runs every 10 seconds. This single-server
design has no durable event replay/outbox. A noninterruptible send can retain one bounded subscriber
slot until the transport write ends, without blocking Workers or database transactions. Embedded
Tomcat NIO uses the explicit `server.tomcat.connection-timeout=30s` for socket writes; the 30-second
SSE async lifetime alone is not a write cancellation guarantee. Interrupts are best effort; replacing
Tomcat or setting an infinite transport timeout requires equivalent bounded write handling. Quota is
released when the sender exits, so stuck sends cannot create unbounded replacement senders.
V14 sequences order committed changes and reject stale/duplicate updates, including delayed SSE after
newer REST refreshes; they do not replay events. A cold catalog load rejects concurrent requests with
`WORKER_BUSY` rather than accumulating HTTP waiters outside short Worker admission.

The SSE sender also waits for MVC afterConcurrentHandlingStarted before draining any event, so
SseEmitter cannot bypass admission via its pre-initialization early-send buffer. The snapshot and
realtime events remain in the same bounded subscriber queue until the transport handler is ready.

### Lists and historical evidence

Run lists use a closed scalar page projection and two grouped projections, without full run/case entities
or repeated evidence parsing. List SQL excludes `execution_input`; V15 backfills `execution_summary`
without per-scenario selection metadata, maintained when execution inputs are frozen.
List `accounting.scenarios` is empty; list selectionSnapshot retains only catalog version/count and budgets.
Scenario detail remains available from run details/reports. Status-group predicates and indexed correlated
counts execute before paging, preserving original status versus interpreted execution outcome.
`RunListQueryTest` measures SQL, entity loads and bytes on H2 with 12 runs, page size 10 and 20/1000 cases
per run. These are fixture measurements, not production latency or driver compatibility claims.

Historical case evidence that is absent, non-object or malformed no longer breaks database-backed
details/report reconstruction. Invalid content is marked `LEGACY_EVIDENCE_UNREADABLE` without fabricating
a JDBC conclusion; the persisted raw evidence and original case/run statuses remain unchanged.


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

<a id="java-style"></a>
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


<a id="frontend"></a>
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

The npm override for `@typescript-eslint/typescript-estree` selects patched `minimatch` 11
for GHSA-g3hv-75wg-m249. It is limited to that development dependency; other callers keep
their own versions. The current caller uses `minimatch(path, pattern, { dot: true })`, whose
behavior should be checked alongside lint on the supported Node version in use. Remove the
override when the upstream parser requires a patched version itself; do not downgrade the
glob matcher to resolve dependency warnings.

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

<a id="upgrade-compatibility"></a>
## Upgrade compatibility

V9/V10 and other historical migrations remain. Retired package tables/history are readable and not
rewritten. Historical SQL reports retain SQL_FEATURE and cannot be JDBC baselines. Old custom-adapter
connections need a built-in type or explicit compatible dialect for new tasks. Retired queued executions
fail with RETIRED_PACKAGE_EXECUTION; obsolete sqlPackageFingerprint requests fail SQL_PACKAGES_RETIRED.
Restart interrupts previous nonterminal runs with SERVER_RESTART. Server/Worker snapshots must match;
unknown retired package execution fields fail instead of falling back silently.
See [deployment upgrade/backup rules](deployment.md#operations) and [driver A/B regression](internal-regression.md).

### V13–V15 derived-field migrations

V15 backfills only derived fields, treating malformed historical JSON as unknown. Fetches and JDBC
write batches use 500 rows **inside one Flyway transaction**, not independently committed chunks.
On PostgreSQL, V15 includes ALTER TABLE, the entire backfill and ordinary CREATE INDEX (not CONCURRENTLY).
DDL locks last until commit and can block readers/writers; allow downtime and enough transaction/WAL
space. V13 and V14 commit separately before V15. A V15 failure rolls back its columns, data, indexes and
history entry together; after correcting the cause, restart retries V15 without `flyway repair`.
The PostgreSQL integration test deliberately fails the final index creation after more than 1000 rows,
then retries without editing history. H2/nontransactional DDL does not establish this recovery guarantee.
Subsequent evidence/input writes maintain projections through entity setters; do not bypass these setters.
The upgrade scans historical evidence once and creates indexes; allow a maintenance window for large stores.

<a id="known-limitations"></a>
## Known limitations

Database combinations, the unresolved Oracle LOB inventory difference and closed historical write scope,
and Dameng attribution are maintained in [database validation records](database-validation.md#known-limitations).
Historical reports remain separate from current implementation. Platform startup validation limits are
in [Deployment](deployment.md#validation-limits).
