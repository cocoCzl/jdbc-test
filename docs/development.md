# Development

English | [简体中文](development.zh-CN.md)

For source edits use the unified development command below. To run the website without developing source, choose [Docker or a non-Docker deployment bundle](deployment.md). Native is a deployment bundle, not IDEA source startup.

<a id="local-startup"></a>
<a id="local-source-startup"></a>
## Local source startup

### First-time preparation: three steps

Install **JDK21 (including javac)** and **Node >=22.12/npm**. The committed Maven Wrapper supplies Maven3.9.15; first downloads need network access. dev requires no Python. Prepare a dedicated empty development **platform PG** database/login with schema/migration permissions; Server applies Flyway migrations at formal startup. This stores application state and encrypted credentials, while target databases are separate UI configurations and unnecessary for page/Catalog startup. Do not use QA or historical copies.

1. In the **source root**, copy **`.env.example` to `.env` only if absent**. Existing `.env`, Key and data are never replaced. There is one source-development template; no template selection is needed.
2. In a local editor open that **actual source-root `.env`**. Fill `SPRING_DATASOURCE_URL` (full URL including database name, no credentials), `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` (existing PG password). For local PG the example URL is `jdbc:postgresql://localhost:5432/jdbc_test`; replace its actual host/port/database and username. New platform: leave `JDBC_TEST_MASTER_KEY` empty. Existing platform: preserve its original Key and data path. Do not put secrets into terminal history, shared IDEA configs or Git.
3. Run **`./jdbc-test dev`** in the source root. It protects the owner's local `.env` before reading, authenticates PG and checks state before generating a Key. For a new empty platform it automatically saves the Key without an initialization prompt; open the printed ready address. Missing `.env` exits immediately before Java/build, tells you where to copy, and creates no configuration.

**Terminal commands — Linux/macOS, source root (edit after copy):**

```sh
umask 077
cp -n .env.example .env
# Open this actual .env in your editor and fill the three datasource fields.
./jdbc-test dev
```

**PowerShell terminal alternative — source root:**

```powershell
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
# Edit this actual .env locally before starting.
./jdbc-test.ps1 dev
```

The independent 32-byte Base64 Master Key is saved in the same `.env`, never printed. It encrypts stored target credentials, not PG authentication. Normal starts reuse it; missing Key with existing schema/data/legacy config stops and requires the original Key. No account password changes, DROP or target tests occur.

Password rules are literal: everything after `=` in an unquoted value is retained (spaces, `$`, `#`, backslashes, `=`, `${NAME}`, valid UTF-8). No inline comments or Shell expansion. A value starting with double quotes is a JSON string; use JSON escapes, or enclose a quote-leading password in single quotes. Controls/newlines are rejected without trimming. Never source/eval `.env`. See [configuration rules](deployment.md#configuration-rules).

The same command builds the current Java/Suite/Worker, finds the **exact current POM-version Worker** (never first-glob/stale fallback), installs dependencies on first use/lockfile/toolchain change, launches the **source main via Maven spring-boot:run** and Vite. It waits for health/Catalog/page/proxied Catalog, then prints URLs; it does not build production frontend assets in dev.

Defaults: page **http://127.0.0.1:5173**, backend **http://127.0.0.1:8080**. Change `.env` `SERVER_PORT` or optional `JDBC_TEST_WEB_PORT` and rerun; proxy follows the actual backend port automatically. Occupied ports abort without killing other services. Only backend receives credentials/Key; Vite does not load `.env`. Data defaults to source-root **`data/`**, or `JDBC_TEST_DATA_DIRECTORY`; reuse the same path and back up config/Key/data together.

### Daily development

```sh
# Source root, dedicated platform PG already running:
./jdbc-test dev
```

PowerShell: `./jdbc-test.ps1 dev`. Reuse original `.env`, Key and data; no initialization flag or confirmation is needed. The Java build checks current modules each launch; dependencies are installed only when needed. While running, frontend edits use Vite HMR and require no Java rebuild. Server source changes need a restart; Worker/Suite changes are rebuilt on the next dev launch, so Server never silently uses an old external Worker. Ctrl+C stops both sides and only their owned descendants; a failed side also stops the other. PG/config/Key/data are retained. Port conflicts, failed builds and authentication errors abort without leaving a half-started environment.

### Advanced: IDEA Debug or manual source launch

The default does not require IDEA. For debugging with the **same `.env` and automatic Worker/proxy flow**, import root `pom.xml` in IDEA with JDK 21, run:

```sh
./jdbc-test dev --debug
```

Then create **Run → Edit Configurations → + → Remote JVM Debug**:

| Setting | Value |
|---|---|
| Project SDK / Maven importer | JDK 21 |
| Debugger mode | Attach to remote JVM |
| Host / port | `127.0.0.1` / `5005` (must be free) |
| Module sources | `jdbc-test-server` |
| Main source being debugged | `com.jdbctest.server.JdbcTestServerApplication` |

Click Debug to attach; do not start a second backend. The debug socket is loopback-only. IDEA attaching does not rebuild the external Worker; stop/relaunch dev after Worker/Suite changes. This runs a source main, not the packaged Server. **IDEA UI Run/Debug has not been exercised**; configuration is checked against code.

For a fully manual alternative, the old Maven/IDEA Application flow still works: build `./mvnw -DskipTests install`, supply Spring datasource/Key configuration **to that actual backend process**, set `JDBC_TEST_WORKER_EXECUTABLE` to the exact current `jdbc-test-worker/target/jdbc-test-worker-<POM-version>-all.jar`, and run `./mvnw -pl jdbc-test-server spring-boot:run`. IDEA Application uses JDK 21, main class above, `jdbc-test-server` runtime classpath and source-root working directory; no profile is required. Do not put secrets in Program arguments, VM options or shared run configurations. The unified command reads `.env`; **manual Maven/IDEA do not**. A terminal export does not update already-running IDEA. Choose one backend launch method, not both.

Manual frontend alternative: first `cd jdbc-test-web && npm ci`, then `npm run dev`; daily only `npm run dev`, reinstall after dependency/lockfile changes. Set nonsecret `JDBC_TEST_DEV_API_URL` in that Vite process if backend port differs (default proxy is localhost:8080). Stop each manual process in its own terminal. Files, Shell environment and IDEA environment are different sources; do not maintain duplicate secret files for the unified flow.

For literal dotenv rules, legacy properties/environment compatibility, build/runtime dependency split and validation limits see [Deployment](deployment.md#configuration-rules). No target probes/runs are part of startup acceptance.

## Architecture

```text
React / Vite → REST + SSE → Spring Boot Server
                                ├→ Platform PostgreSQL
                                ├→ Driver storage / Run workspaces
                                └→ Worker JVM → external JDBC driver → Target database
```

| Module / path | Responsibility |
|---|---|
| `jdbc-test-core` | Catalog, selection, request/report models, shared utilities |
| `jdbc-test-suite` | JDBC scenarios, JUnit extensions, packaged SQL resources |
| `jdbc-test-worker` | Driver isolation, execution, validation, connection probes, NDJSON |
| `jdbc-test-server` | REST, persistence, scheduling, SSE, production static hosting |
| `jdbc-test-web` | Independent React 19 / strict TypeScript / Vite 7 npm project |
| `scripts` | Python CLI, reports, regressions and release packaging |
| `adapters`, `compatibility/v1`, `ddl`, `dml`, `pool` | Adapter capabilities, scenario IDs and SQL/pool assets |

Server depends on Core and does not load Suite or uploaded driver classes. The Worker fat JAR bundles Core, Suite, JUnit and SQL resources, with target drivers supplied externally. CLI retains its Maven/JUnit execution path; Web uses Server-scheduled Workers. Both share scenarios and execution safety rules. Platform PostgreSQL is separate from the target database.

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
Validated with IDEA 2026.2.3, build IU-262.10968.63. Its formatter does not optimize imports;
the current import blocks were separately reordered using Java compiler source positions without
adding/removing imports. Existing wildcard imports are retained. Do not substitute another engine,
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

## Web UI

Routes cover Dashboard, `/drivers`, `/connections`, `/runs`, creation forms and `/:id` details. The workflow is upload/validate driver → save/probe connection → review selection and authorized scope → consent/start → live results/report.

TanStack Query owns server state; forms and transport state remain local. `src/api/client.ts` handles fetch, JSON/multipart/204, cancellation and RFC Problem Details. Resource DTOs have no universal success envelope. TanStack Table handles tables; shared CPA-style components use Radix interactions, Sass visual tokens and Tailwind layout utilities; ECharts renders outcomes. There is no production mock API.

Run detail loads REST first, then opens native `EventSource` for active runs. SSE updates the same Query cache; terminal events close the stream and refresh final results. Reconnects do not change Run status; a 10-second REST fallback covers missed terminal events. Passwords stay in component memory, clear after requests, and never enter browser storage, URLs or query caches. Diagnostics render as text.

Dashboard outcomes describe only the eight loaded recent runs; catalog totals describe inventory. There are no global trend, module-aggregate or safe diagnostics endpoints. Run lists filter by status. Results combine status and module filters with AND before database pagination, with a stable scenario-ID tie break. The UI does not invent unavailable statistics or a Logs tab.

### Language and metric cards

The top-right menu switches between bundled English (`en`) and Simplified Chinese (`zh-CN`) without reloading. A valid `jdbc-test.locale` preference takes precedence over supported browser languages; otherwise English is the fallback. Blocked storage is tolerated. Dates retain browser-local timezone semantics. Stable IDs, API values, cached data and raw reports remain untranslated.

Metric cards share CSS tone tokens and pale gradients; outcome colors retain their existing meanings. Completed includes explicit skips and is not a compatibility pass rate. Results exposes skipped `message` details as original sanitized evidence; the current DTO has no separate structured skip-source/capability fields. Technical scenario names and unknown module names remain backend-provided.

## Platform database

Flyway V1–V8 owns schema; Hibernate uses `ddl-auto=validate`. UUIDs identify records and UTC `Instant` values use `timestamp with time zone`. References protect historical runs from deletion of used drivers/connections.

| Migration | Purpose |
|---|---|
| V1 | Driver packages/artifacts, connection profiles, runs/modules/results |
| V2 | Unique driver package fingerprint |
| V3 | Explicit connection `adapter_id` |
| V4 | Run snapshots, selected scenarios, encrypted queued credentials |
| V5 | Authorized scope, frozen inputs, evidence version, starts/completions/issues |
| V6 | Driver-owned DataSource providers |
| V7 | Cleanup evidence and original Worker termination reason |
| V8 | Observed product/driver metadata and preflight conclusion |

Saved passwords use AES-256-GCM with a fresh 12-byte nonce and envelope `v1:<base64 nonce>:<base64 ciphertext>`. `JDBC_TEST_MASTER_KEY` supplies the persistent 32-byte key. Plaintext is absent from entities, snapshots and diagnostics. Queue credentials use a separate encrypted table and are deleted on Worker handoff and terminal/recovery paths. Legacy runs retain their original evidence; V5 does not infer starts.

## REST API

Lists use bounded `page`/`size` pagination (default 20, maximum 100), sort allowlists and `{items, page, size, totalElements, totalPages}` responses. Errors use RFC Problem Details with a stable `code`. Creation returns `201` and `Location`; deletion returns `204`. Historical references prevent deletion with `409`.

### Drivers

| Method | Endpoint | Purpose |
|---|---|---|
| POST / GET | `/api/drivers` | Upload / list packages |
| GET | `/api/drivers/presets` | Optional form suggestions |
| GET / PATCH / DELETE | `/api/drivers/{id}` | Read / edit metadata / delete |
| POST | `/api/drivers/{id}/validate` | Validate using Worker |

Upload multipart `metadata` JSON and repeated `files` parts. Defaults: 20 artifacts, 100 MiB each, 250 MiB total. Binaries are immutable; upload a new package to replace them. Server reads JAR metadata/SPI only; ambiguous or absent SPI requires explicit `driverClass`.

The new-driver form reads the immutable preset list from `GET /api/drivers/presets` (the same API
access policy as other driver routes). Each item contains `code`, `nameKey`, `vendor`, `driverClass`
and `urlPrefix`; custom has empty defaults. Presets only suggest form values, without persisting a
preset selection or changing adapters, SQL dialects, validation status or database capabilities.
Vendor remains a free-form issuer label, and URL prefix remains optional package metadata.
Each of these three fields tracks explicit user edits, including clearing and selecting an SPI
candidate. Switching presets updates only unedited fields; restoring defaults explicitly returns all
three fields to preset control. Preset loading failures leave manual upload available.

Adapter `driver.class` and source metadata describe a recommended/validated adapter combination
(and CLI preparation inputs). Driver presets are independent form suggestions; a saved package's
actual class and artifacts govern Worker execution. Custom drivers can use an adapter with an
implemented compatible dialect. `GENERIC` retains only its legacy fallback role. Existing packages
and the metadata editor are never inferred from presets or migrated.

Files live at `<data-dir>/drivers/<driver-id>/<artifact-id>.jar`; user filenames are display metadata. Each artifact has size and SHA-256; the package fingerprint hashes sorted artifact digests joined by a newline. Duplicate packages return `DRIVER_PACKAGE_ALREADY_EXISTS`. Class edits reset validation. Worker validation loads the class but does not connect, with a default 30-second timeout. Used packages return `DRIVER_IN_USE` on deletion. Driver sort fields: `name`, `vendor`, `version`, `status`, `createdAt`.

Oracle packages may need companion artifacts. For an `oracle.jdbc.*` driver, validation returns
additive `warnings` codes `ORACLE_I18N_MISSING` / `ORACLE_XML_MISSING` when the charset converter or
XML classes/method signatures cannot be resolved in the **selected isolated loader**. Warnings do
not invalidate a driver that can handle a built-in charset without XML. They are shown in the
validation response, not persisted as a compatibility certification. This is a limited component
check, not a complete dependency resolver or a guarantee that all vendor versions interoperate.

Upload a **new multi-JAR package** containing compatible vendor versions of `ojdbc`,
`com.oracle.database.nls:orai18n`, and, for SQLXML, `com.oracle.database.xml:xdb` plus
`com.oracle.database.xml:xmlparserv2`. Alternatively put all JARs in the CLI's local driver directory.
Use the vendor's requirements for the chosen driver release; filenames alone do not prove validity.
These are external test inputs, not application/POM dependencies, and are never downloaded silently.

Before namespace/fixture creation, Oracle execution preflight reads `NLS_DATABASE_PARAMETERS`
(SELECT only) and tests both database and national charset conversions using the isolated driver.
An unreadable/missing charset or failed conversion stops preflight with
`ORACLE_CHARSET_PREFLIGHT_FAILED`; the cause is retained. XML components are checked only during
preparation of selected SQLXML methods. Missing components yield `ORACLE_XML_MISSING` as a scenario
prerequisite failure without blocking independent standard cases. The legacy
`preflight.require_sqlxml` field remains parseable but no longer triggers global XML rejection;
this check does not depend on the `sqlxml` capability boolean. Ordinary
connection probes still run no SQL. These checks target Oracle's
`oracle.jdbc.*` implementation; other drivers retain existing behavior. Test failures caused by a
missing dependency must not be reported as database compatibility failures.

### Adapter registry and execution declarations

`adapters/catalog.json` is the single built-in registration index. It lists IDs only; names,
trust, versions, capabilities and driver hints remain in each `adapters/<id>/adapter.json`.
Core loads and validates the entire index once per classloader into an immutable `AdapterRegistry`.
`AdapterCatalog` and `AdapterExecutionConfig.load` project that same definition. Missing resources,
duplicate IDs, ID/path mismatches and malformed declarations fail explicitly, including `contains`.
Core validates metadata without loading Suite, SQL resources or target JDBC drivers. Suite packaging
tests check the declared SQL areas and pool profiles. Indexed packages use the existing classpath
roots `ddl`, `dml`, `pool`; `assets.directory` selects the asset bundle independently of adapter ID.
The CLI uses the same index and strict metadata contract. Runtime external manifests and uploaded
packages are no longer accepted.

`DbType` contains stable codes for known product classifications; it is not a registry whitelist.
Optional `db_type` classifies a custom adapter. Without it, known legacy IDs retain their product
classification; unknown IDs/classifications retain their original declaration and have no known
classification. `adapterId` and the free-form `databaseType` remain strings. Execution uses the
frozen `dialect`, never the display label or driver vendor. Current declared dialects are
`postgresql`, `mysql`, `oracle`, `sqlserver`; GaussDB already declares PostgreSQL behavior and retains
its own identity constraints and assets. The Suite's old generic fallback remains for legacy
configuration only; it cannot be registered as a new manifest dialect. Scope selection, Oracle
identifier normalization, quoting and owned-index cleanup use these finite behaviors.

Adapter JSON uses only `tools.jackson.core:jackson-core`, with no Boot starter or databind dependency
in Core. The existing Boot BOM baseline is 3.1.4; only Jackson 3 core is explicitly managed at
**3.1.7**, the published patch on the maintained 3.1 LTS line checked for this change. See the
[official maintenance policy](https://github.com/FasterXML/jackson/wiki/Jackson-Releases) and
[Central version metadata](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-core/maven-metadata.xml).
This does not upgrade Spring Boot or either databind stack. Server resolves one Jackson 3 core;
Worker temporarily has both the existing Jackson 2 and Jackson 3 core (about 589 KiB added).
Their distinct packages coexist; Worker protocol serialization remains on Jackson 2. Target JDBC
drivers remain external inputs loaded by the isolated Worker loader.

Declarations must be UTF-8 strict JSON: duplicate keys, YAML syntax, comments, trailing roots,
nonfinite numeric constants and trailing commas are rejected. Input is limited to 1 MiB, 64 nested
containers and 128-character numeric tokens. Floating extensions must have finite binary64 magnitude
so Python JSON output cannot emit Infinity. Required fields, field types, trust, managed boolean
capabilities, version ranges and resource paths are checked. The full JSON tree, including unknown
extension fields and nulls, is retained immutably in Java; extensions do not automatically become
execution overrides. Existing omitted revision/quote/pool defaults remain `release`, `"`, `pool`.
New required fields require a separate compatibility decision. Java and Python regex engines are
not identical: identity patterns must remain portable; fixtures do not prove all regexes equivalent.

The existing `adapterExecution` wire fields and protocol version 2 are unchanged. Missing historical
execution snapshots still use the bundled adapter fallback. Frozen snapshots drive Worker scope,
while metadata identity/version validation remains before provisioning and fixture writes.

Run `python3 -B scripts/run_contract_tests.py` and `./mvnw verify`. Shared positive/negative fixtures
live in `jdbc-test-core/src/test/resources/adapter-contract`; both languages exercise them. These
checks cover declarations, packaging, frozen inputs, ownership and existing safety regressions;
they do not prove real-database compatibility. Run native formatting on the changed Java files and
the four separate quality checks described above. Keep investigation logs and dependency evidence local.

### Adding an adapter and preparing drivers

For an adapter that reuses an implemented dialect, copy an existing
`adapters/<id>/adapter.json` to a new directory (for example `adapters/private-db/`).
Set its unique `id`, name, identity/version constraints and truthful capability declarations.
Use `trust: "candidate"` or `"local"` until evidence supports another status; do not copy another
product's validated combinations as your own. Keep `dialect` as the implemented behavior and set
`assets.directory` to the reusable bundle, or add the necessary `ddl/<asset-id>`, `dml/<asset-id>`
and `pool/<asset-id>.yaml` assets. List the new ID once in `adapters/catalog.json`.
No `DbType` member, Java product branch or frontend list is needed. `private-db` is a fixture/example
identity, not a supported product. Shared contract and API fixtures already exercise this route.

A genuinely new dialect needs the smallest implemented `SqlDialect` behavior, matching assets and
failure/cleanup tests before registration. Adding a product enum constant alone does not implement
compatibility. Run these checks from the repository root before testing an authorized disposable target:

```bash
python3 -B scripts/run_contract_tests.py
./mvnw verify
cd jdbc-test-web
npm run lint
npm test
npm run build
```

Private drivers can explicitly reuse a built-in compatibility dialect in connection settings; see
[current examples](../examples/README.md). Adaptation changes require source updates and rebuild/deploy.

The manifest coordinate `org.postgresql:postgresql:42.7.2` is a declaration example, not a latest-version
or security recommendation. Select a different fixed version through CLI `db.driver` (`kind: "maven"`,
`coordinate: "group:artifact:version"`) or prepare local JARs (`kind: "local"`, `path: "/.../driver-jars"`).
The current CLI Maven resolver places only the primary JAR on the runtime classpath; downloaded
transitive dependencies are not a complete loaded bundle. For multi-JAR drivers, use the existing
local directory route or upload all required JARs together in the Web driver package. Private/offline
JARs remain supported; do not commit them. Changing a recommendation never changes installed package
fingerprints or historical runs. The platform PostgreSQL dependency serves platform storage; target
drivers remain separate inputs loaded by the isolated Worker. There is no online Web Maven importer.

The connection editor shows declaration hints and declared version combinations independently of
installed-driver validation and target probe results. Database type selects a built-in adapter. Explicit `databaseType=compatible` reuses its dialect without
asserting another product identity/version range in the frozen execution configuration; actual identity
is still recorded independently. Old display labels remain readable.
The run wizard's adapter filter queries saved connections before pagination; it does not change their
adapter or filter drivers by vendor. An off-filter deep-linked selection remains visible and requires
clearing the filter or choosing another connection before continuing. Saved probes distinguish omitted
password (saved fallback) from explicit `""`; spaces and all other supplied characters are preserved.

### Connections

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/adapters` | Built-in adapter metadata |
| POST / GET | `/api/connections` | Create / list profiles |
| GET / PATCH / DELETE | `/api/connections/{id}` | Read / update / delete |
| POST | `/api/connections/test` | Probe an unsaved draft |
| POST | `/api/connections/{id}/test` | Probe saved settings |

`databaseType` is an open string; `adapterId` independently selects SQL/capabilities. A profile references a validated driver and records JDBC URL, username and authorized execution scope. Create/update/draft-probe and execution paths reject Oracle Thin/OCI embedded user/password segments and credential parameters (`password`, `passwd`, `pwd`, `secret`, `token`, `access_token`) with `CONNECTION_URL_CONTAINS_CREDENTIALS`.

Passwords are write-only; responses expose `hasSavedPassword`. Save/replace with `password` plus `savePassword: true`; clear with `clearSavedPassword: true`, never both actions together. A temporary probe password overrides the saved password for that request. Save and Probe are independent; save edits before probing persisted settings. Missing/null means reuse the saved credential, or `CONNECTION_PASSWORD_REQUIRED` if none exists. Explicit `""` overrides it with an empty password; spaces and Unicode remain literal. Saving `""` creates a nonempty authenticated v1 envelope and `hasSavedPassword: true`. Updates without a new password retain the old envelope. Clear plus any new password, including `""`, is rejected. UI forms require explicit empty-password selection; an untouched blank field does not select this intent. CLI password environment variables distinguish unset from set-to-empty.

Probes use Worker `Driver.connect`, read metadata best-effort, close and exit without SQL or test execution. The default timeout is 30 seconds. Saved probes update last-test time/status; drafts persist nothing. A null connection gives `DRIVER_DOES_NOT_ACCEPT_URL`; used profiles cannot be deleted (`CONNECTION_IN_USE`). Sort fields: `name`, `databaseType`, `lastConnectionTestAt`, `createdAt`.

### Runs

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/test-catalog` | Profiles, modules and scenario counts |
| POST / GET | `/api/runs` | Create / list runs (optional `status` or `statusGroup` filter) |
| GET | `/api/runs/{id}` | Snapshot and progress |
| POST | `/api/runs/{id}/cancel` | Cancel |
| GET | `/api/runs/{id}/events` | SSE, starting with `run.snapshot` |
| GET | `/api/runs/{id}/results` | Paginated scenario results |
| GET | `/api/runs/{id}/report` | Summary and failures |
| GET | `/api/runs/{id}/report.json` | Raw JSON evidence |

Read module and profile counts from the current catalog; CUSTOM selects modules/scenarios. These counts are not proven compatibility results. New runs require explicit scope and `destructiveConsent: true`. Selection and nonsecret execution inputs are frozen at enqueue; a new validation/probe is not performed automatically.

History filters use `statusGroup=WAITING` (QUEUED), `IN_PROGRESS` (PREPARING, legacy PROBING, RUNNING, GENERATING_REPORT), `COMPLETED` (SUCCEEDED), `ABNORMAL` (FAILED, INTERRUPTED), or `CANCELLED` (CANCELLED). Omit both filters for all runs. Exact `status` queries remain supported; combining the two returns HTTP 400 / `CONFLICTING_STATUS_FILTERS`, and an invalid or empty group returns HTTP 400 / `INVALID_STATUS_GROUP`. Group predicates apply in the database before pagination and counting. Responses retain the original internal status, and no history migration is required. PROBING has no current execution transition into it; it remains readable/filterable for historical compatibility.

Normal states: `QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED`. Failure, cancellation and recovery end in `FAILED`, `CANCELLED`, `INTERRUPTED`. A local FIFO scheduler limits concurrent Workers; workspaces are `<data-dir>/runs/<run-id>`. Run row locks protect terminal states against late results/cancel races. Timeout gives `FAILED / RUN_TIMEOUT`; a slot stays occupied until process cleanup finishes. User-facing main labels describe lifecycle groups, with active phases and recorded abnormal reasons shown separately. SUCCEEDED is displayed as Completed, never as proof that every test passed: current completion checks still reject failed assertions and non-permitted aborts, while explicitly permitted unsupported outcomes may complete normally. Result counts/evidence, including partial results after abnormal termination, retain their original meaning. Display groups never control transitions, terminal detection, cancellation, polling or SSE.

Server checks events, report entries and counts before success. Invalid/inconsistent reports fail with `REPORT_INCONSISTENT`; FAILED/ABORTED results other than explicitly permitted unsupported outcomes yield `WORKER_EXECUTION_FAILED` even with exit code 0. A SQL-only assertion failure with a complete, validated report and Worker execution-failure exit 4 is identified separately as `SQL_ASSERTION_FAILED`; CLI maps completed failures to exit 1. The report DTO is `{summary, failures}`. Protocol v2 raw JSON can be reconstructed from persisted evidence, with `source` and `workerReportValidated` identifying that boundary. See [Execution safety](execution-safety.md) for accounting rules.

### Test scope and execution semantics (selection version 2)

The actual chain is inventory → Worker `--describe-catalog` → RunCatalogService → target catalog API → New Run → shared ScenarioSelection → frozen scenario rows/executionInput → isolated Worker → JUnit extension → CASE events → persistence/report. Server never loads suite classes; catalog discovery never loads proprietary classes.

There are currently 270 implemented scenarios in 17 modules, all STANDARD_JDBC, covering the existing connection/transaction, statements/batches/keys, ResultSet, metadata, savepoint, standard LOB/XML/array, RowSet and DataSource/pool/XA scenarios. This is **not every JDBC API**. No real VENDOR_EXTENSION scenario currently invokes proprietary APIs. The inventory's test_category and explicit applicable_adapters support extensions, verified with fixtures; module names, dialect and capability booleans do not determine ownership. jdbc_version records the test's JDBC runtime baseline; execution_prerequisites records Provider and SQL assets. Unsupported optional APIs still require observation.

New Run defaults to all applicable tests after choosing a connection and driver. Modules start collapsed and show selected/total with mixed checkboxes. Expand to change individual cases, or search names/stable IDs. Select all, clear, restore defaults and module checkboxes affect the entire set regardless of search. Refresh preserves partial selections. Target changes preserve remaining selections, select newly applicable cases and explain removed items. Cache keys contain connection, adapter and driver. Review shows selections/exclusions and known missing Providers; SQL, permissions and companion JARs are checked during preparation. Empty selections cannot submit.

Compatibility: explicit CORE keeps the existing 153 IDs, maintained through inventory category: core rather than feature annotations. FULL means all applicable scenarios. CUSTOM requires a module or included ID. Modules intersect includeScenarios; excludeScenarios wins last. Empty FULL/CORE lists add no filter; CUSTOM with empty modules and nonempty includes means that exact ID set. Unknown, deleted or inapplicable IDs and a final empty set are rejected. A stale supplied catalogVersion yields CATALOG_CHANGED. No separate copy/rerun endpoint exists; clients must review current IDs when resubmitting history.

New runs reuse executionInput and selected scenario rows. They freeze the catalog content hash, semantics version, selection basis/exclusions, final metadata including category/JDBC version/source class/method/contract, existing adapter/driver/Provider snapshots and budget. Worker uses frozen metadata instead of reapplying current CORE membership. A missing historical class/method is explicitly unavailable and never replaced. Legacy tasks/reports retain original semantics when evidence is absent. Protocol remains 2; deploy Server/Worker together.

Gate audit: A comprises all current capability declarations except the three Provider flags (savepoints, callable statements, advanced types, LOB/XML/RowSet, metadata, result sets, keys, cancellation/timeouts, client info, request/sharding, java_time and related operations). Declarations remain evidence; false does not disable standard verification. B comprises configured data_source/connection_pool/xa_data_source Providers, SQL assets/object shapes, privileges and Oracle XML companion JARs. Missing prerequisites are explicit, including an absent configured Provider class in the selected driver package. Provider constructor/setter and actual interface failures are not blanket-converted into missing prerequisites. C has no explicit predecessor-test graph today; a failed shared fixture blocks only cases using it, preserving its cause, resource and originating scenario ID. D uses explicit adapter applicability; no production vendor scenarios exist yet.

UseSqlScripts is prepared lazily before a selected method and shared within its class. An empty method annotation overrides class scripts. Connection, metadata and in-memory XML methods that need no table avoid unrelated DDL. Array fixture shape is checked; Oracle's legacy JSON/VARCHAR substitutes cannot masquerade as executed array tests. Kingbase's isolated assets preserve the existing PostgreSQL baseline and the documented validation scope; new preparation resources require targeted real validation. Preparation failures retain SQLState/vendor codes. Failure to obtain the injected scenario connection is CONNECTION_FAILURE in PREPARATION, preserving authentication/connection errors even after a JUnit execution callback; it is not a missing prerequisite or contract invocation. Calls made by the test body retain their own contract context. SQL inside a test body retains its contract interpretation. Public assertions are not relaxed. Holdability is exercised even if supportsResultSetHoldability returns false, and that declaration is recorded and compared with actual behavior.

PASSED/FAILED/SKIPPED/ABORTED remain execution statuses. New conclusions distinguish CONFORMS, CONTRACT_VIOLATION, UNSUPPORTED_ALLOWED, UNSUPPORTED_REQUIRED, PREREQUISITE_UNMET, BLOCKED_PREREQUISITE, TIMEOUT, CONNECTION_FAILURE, PLATFORM_ERROR and NOT_EVALUATED, with phase, category, duration, sanitized exception/SQLState/vendor code, declarations and observations. JdbcContracts.optional accepts SQLFeatureNotSupportedException only around specific permitted factory/logger calls: Connection.createBlob/createClob/createSQLXML and CommonDataSource.getParentLogger. Subsequent object operations retain their assertions. Allowed unsupported is ABORTED plus contractConforms=true, never PASSED, and remains visibly unsupported. JdbcContracts.required currently scopes Connection.getAutoCommit: its explicit unsupported response violates that required API contract. Unscoped SQLFeatureNotSupportedException remains NOT_EVALUATED with its original error and no inferred compliance. Ordinary SQLException, SQLState and error text never establish unsupported permission. Preparation SQL without a known prerequisite marker also remains NOT_EVALUATED. Per-case phaseDurationsMs measures preparation including injected connection acquisition, the method body and connection cleanup; class-level owned-object cleanup is separate cleanupEvidence. Historical reports receive no invented conclusions.

Reports show catalog, selected, invoked contracts, user exclusions, not-run/incomplete counts and separate contract conclusions. Missing prerequisites, skips and timeouts are not passes. Raw JSON includes selection provenance and case evidence; unstarted cases retain the run termination reason. Adapter maturity is independent of driver contract compliance and does not automatically decrease when a reliable adapter exposes a real driver defect.

Optional task budgetSeconds is bounded to 60–7200; UI defaults to 1800. Older API clients retain the configured server budget, now frozen. Preparation, tests and cleanup share the budget. JUnit applies a default 60000ms timeout to the test method body only, using SAME_THREAD; explicit @Timeout takes precedence. Discovery, before/after callbacks, preparation and cleanup are outside that method limit and remain bounded by the shared task budget. JUnit interrupts the method thread at its limit; it cannot force a JDBC call to return. If interruption is ignored, no following method or cleanup runs concurrently: the Worker exits at the task budget, with incomplete cleanup reported as unknown. The Server allows 5 seconds for Worker exit before its configured termination grace (2 seconds by default), then forcibly terminates the Worker if needed. This is not a hard per-case process kill or a promise to continue after an unresponsive call. Unreached cases remain NOT_RUN; unfinished started cases remain INCOMPLETE. CLI timeout_ms retains per-method meaning, core/full and class/method filters remain, stable IDs are also accepted, and an empty filtered set is rejected. CLI execution and reports share the same selector; USER_EXCLUDED identifies unselected cases and NOT_APPLICABLE identifies unrelated extensions. Without PyYAML, explicit CORE membership is still read from the inventory; future vendor extensions require PyYAML to interpret applicability. New CLI reports carry outcome_semantics_version=2 and no longer let false declarations override observations; legacy XML without reliable phase evidence preserves ordinary SQL errors as NOT_EVALUATED rather than guessing a platform cause; stored historical JSON is unchanged.

## Worker protocol

Deploy Server and Worker together. Each Worker reads one stdin JSON request and emits stdout NDJSON. Passwords go through stdin, never argv. User driver stdout cannot corrupt the protocol; stderr diagnostics are bounded and sanitized. A per-run `URLClassLoader` with platform parent loads the selected driver and calls `Driver.connect` directly without global `DriverManager` registration.

| Mode | Purpose |
|---|---|
| No flag | Execute a single run |
| `--describe-catalog` | Emit `CATALOG_DESCRIBED`, including `protocolVersion: 2`, without driver loading |
| `--catalog-smoke` | Check the packaged catalog independently of working directory |
| `--validate-driver` | Load/instantiate driver; return JDBC metadata without a connection |
| `--probe-connection` | Open/close a connection without suite SQL |

```sh
java -jar jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar --describe-catalog
```

Server checks protocol version at catalog load and again before every execution. Mismatch gives `WORKER_PROTOCOL_MISMATCH` before sending execution credentials. Validation/probe modes retain their separate request contracts. For execution fields see [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java): `protocolVersion`, `runId`, `driver`, `connection`, `selection`, `workspace`, `timeoutMs`, `scope`, `objectPrefix`, `capabilities`, `destructiveConsent`, and the frozen `adapterExecution` declaration. Requests require version 2, explicit scope/consent, prefix `j` plus 20 lowercase hex digits, all 34 boolean capability declarations and the selected `connection.adapterId`.

Events include `RUN_STARTED`, `MODULE_STARTED`, `CASE_STARTED`, `CASE_FINISHED`, `MODULE_FINISHED`, `CLEANUP_FINISHED`, `METADATA_VALIDATED`, `RUN_FINISHED`, `RUN_FAILED`. Malformed/unexpected events, wrong run IDs and unselected/duplicate results fail with `WORKER_PROTOCOL_ERROR`. Report output is `<workspace>/report/report.json`, schema `2.0.0`; temporary config is deleted on exit.

| Exit code | Meaning |
|---:|---|
| 0 | Run completed; Server still validates outcomes/report |
| 2 | Invalid request or selection |
| 3 | Driver/connection setup failure |
| 4 | Execution or timeout failure |
| 5 | Catalog, workspace, report or internal failure |

## KingbaseES setup

### Official adapter decision and scope

Official means verified platform integration and interpretable evidence for an observed combination;
it does not require every tested JDBC contract to pass. Apply the same standard to PostgreSQL,
MySQL, Oracle and Kingbase: verify product/driver identity, authorized fixture preparation and
execution, truthful case/prerequisite attribution, report/count consistency and ownership cleanup.
Record real versions and evidence; disclose unexecuted fixture paths and environment prerequisites.
Manifest parsing alone is not validation, and an adapter's status is not a v1 `target_compatible`
conclusion. Existing PostgreSQL/MySQL/Oracle evidence predates the current 270-case selection.

Kingbase was promoted after reviewing the 2026-10-19 actual FULL run
`f48e3615-e4b7-4f63-8cc1-a81495728ce3`: 270 terminal result records, 261 contracts invoked,
and 9 contracts not invoked due to missing or blocked preparation (4 direct, 5 blocked). Recorded
statuses: 252 PASSED, 9 FAILED, 9 ABORTED; cleanup SUCCESS. Historical NOT_RUN/INCOMPLETE counts
are zero; these accounting fields do not mean every contract was invoked.
Only server V009R002C016, `database_mode=oracle`, JDBC database version 12.1 and driver
V008R006B0001 (the recorded artifact SHA-256 below) are validated. Other builds, modes and drivers
are unvalidated. The initial standalone 20-case subset passed; the 153-selected CORE run completed 42 passes
before the 90-second timeout (7 overlap with the subset, so counts must not be added). The later
FULL task completed in 423.4 seconds. This is not a claim of universal PostgreSQL SQL compatibility.

`validated_combinations` uses the observed JDBC metadata version 12.1, with explicit server build,
mode and driver fields, and links the [sanitized evidence](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json).
The original ignored report directories are no longer present. The record preserves actual task
tool-output summaries, all 18 non-passing cases and cleanup observations, with a hash of those
source excerpts. It is explicitly not a hash or reconstruction of the missing original Worker report.
No v1 target outcome was invented, and no historical case status was rewritten.

### Driver and authorized namespace

1. In **Drivers → Add a driver**, select KingbaseES. The editable suggestions are vendor
   `Kingbase`, class `com.kingbase8.Driver` and prefix `jdbc:kingbase8:`. Upload your authorized JAR;
   SPI discovery and isolated validation remain available. No private JAR is distributed.
2. In **Connections → Add a connection**, select **KingbaseES · Official** and the actual validated
   driver package. Use your actual `jdbc:kingbase8://host:4321/test` URL and separate credential fields.
3. Have the owner create a dedicated schema and grant CONNECT plus schema USAGE/CREATE. Enter
   its database/schema and destructive consent. The adapter uses an existing authorized schema;
   owned objects use unique prefixes and cleanup, while the schema itself is retained by the run.
4. Probe before running. The page supports exact scenario selection, module selection and search;
   default full selection includes the documented preparation gaps. API `CUSTOM`/`includeScenarios`
   also supports narrow reproduction. Review case results and cleanup evidence, not just run status.

The CLI's relative `driver.source=local-drivers/kingbase` resolves local private artifacts;
Web/Server runs use the selected uploaded package in the isolated Worker. Product identity,
PostgreSQL dialect infrastructure and SQL assets remain separate responsibilities. Identity is
anchored to `^KingbaseES$` / `^Kingbase8 JDBC Driver$`, so ordinary PostgreSQL is not admitted.
Observed double-quote/lower-case identifier handling and schema selection use the PostgreSQL
infrastructure even in oracle mode; the project's Oracle dialect is not substituted.

The numeric `supported_versions` objects remain empty because the existing range format cannot
express the Kingbase build/driver declaration. This is not all-version support. Build/mode are a
documented validation boundary, not an automatic runtime gate; operators must confirm them.
Historical capability booleans do not disable standard tests. No capability was changed to hide
failures, and promotion adds no known-difference suppression or changed public assertion.

### Results and limits

Nine ABORTED cases identify missing/blocked `procedure_behavior_ddl.sql`, `ref_cursor_ddl.sql`,
`rowid_behavior_ddl.sql` or `multiple_results_ddl.sql`. They are platform preparation limits, not
passes or absent driver capability. Eight array cases and all database-metadata cases did pass
in this actual run, superseding the initial narrow-trial uncertainty without certifying every script.

Two XA prepare failures occurred with `max_prepared_transactions=0`; do not infer unsupported XA
or a driver defect. Historical statuses/conclusions remain unchanged; the evidence review records
this environment limitation separately. LOB bounds/partial writes and DataSource logWriter failures
remain visible; REF metadata, indexed batch keys and unimplemented holdability require the recorded
contract/unsupported interpretation, not a blanket driver-defect conclusion. FULL selection is not
proof every contract ran. See [Execution safety](execution-safety.md#kingbasees-platform-validation).

At the earlier four-database acceptance checkpoint, MySQL, PostgreSQL and Kingbase had actual
FULL tasks and later page flows; Oracle had only driver/connection probes. A later Oracle FULL
attempt is recorded below; its preparation and cleanup qualifications prevent an all-four-complete
claim. Unit and mocked browser checks remain fixture evidence, not additional target validation.

### Targeted preparation follow-up (9 cases)

The historical FULL left four direct preparation gaps and five shared-fixture blockers. Four resources
now live under isolated `ddl/kingbase/`; the existing 35 DDL/DML/pool baseline assets are byte-identical
copies, and shared PostgreSQL SQL is untouched. Resources provide IN/INOUT/OUT arithmetic,
REF_CURSOR output, table-local ROWID and native implicit results with an update. No standard
assertion, capability gate or global parameter was changed.

The owner explicitly authorized a new unique Kingbase test schema. Its fresh schema OID,
actual current user/database/schema and probe-table owner were checked in the database before
scenario tasks. The observed combination remains V009R002C016 / oracle mode / V008R006B0001.
Nine independent tasks and combined task `30d16db6-4faa-4800-b6b1-0c9d50e8d5bf` used the normal
Server/Worker/report path. All selected contracts were invoked, without skips, preparation failures,
blockers, NOT_RUN or INCOMPLETE. Combined results are eight PASSED/CONFORMS and one
FAILED/CONTRACT_VIOLATION. Do not add repeated independent and combined executions as unique
coverage or rewrite the historical 270-record FULL result.

| Stable scenario ID | Historical preparation issue | Kingbase SQL resource | Supplemental result |
| --- | --- | --- | --- |
| `callable_statement.procedure_parameter_metadata` | Direct missing resource | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.ref_cursor_output` | Direct missing resource | `ref_cursor_ddl.sql` | PASSED / CONFORMS |
| `result_set.batch_row_id_binding` | Direct missing resource | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |
| `statement.result_sequence` | Direct missing resource | `multiple_results_ddl.sql` | FAILED: update count not exposed |
| `callable_statement.in_out_and_in_out` | Blocked shared resource | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.null_output` | Blocked shared resource | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.repeated_binding` | Blocked shared resource | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `result_set.row_id_round_trip` | Blocked shared resource | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |
| `result_set.update_and_delete_by_row_id` | Blocked shared resource | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |

`statement.result_sequence` successfully prepared both owned objects and invoked its contract.
Its failure occurs after the result-value and result-set closure checks: the sequence did not expose
the expected one-row update count. The native implicit-result mechanism is relevant to further
contract/driver review; this observation alone is not an established driver defect. Keep the original
assertion and failure. All five old shared-resource blockers are removed. The four missing resources
are no longer a preparation gap for this verified combination; the remaining assertion is separate.

Every task reported owned cleanup SUCCESS; the combined task created and dropped five roots,
showing shared preparation rather than conflicting duplicate CREATE. After all reports were saved,
the unique schema's original OID/owner and empty relation/routine/type inventory were verified,
then it was dropped without CASCADE. No existing schema was reused or cleared. The two historical
XA max_prepared_transactions failures were outside this subset and remain documented unchanged.
Raw sanitized reports, exact per-case task IDs, frozen selections, code/binary fingerprints and hashes
are saved in the private archive outside the checkout at
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/original-229/`;
no Kingbase FULL was repeated. All four direct preparation gaps and five shared-resource blockers
are **RESOLVED**. The remaining assertion is a separate OPEN item, not an unresolved preparation gap.

### Kingbase result-sequence update-count follow-up

OPEN: `statement.result_sequence` (`MultipleResultsTest.testResultSequence`). Test SQL is
[`ddl/kingbase/multiple_results_ddl.sql`](../ddl/kingbase/multiple_results_ddl.sql), called through
`{call {{multiple_results}}()}`. Its owned table starts at `(id=1, value=0)`; the procedure publishes
`SELECT 11`, executes `UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1`,
then publishes `SELECT 22` via native `dbms_sql.return_result`.

The unchanged contract expects ordered result values `[11, 22]`, at least one update count `1`,
and traversal ending with update count `-1`. Actual failure is
`Procedure must report its one-row update ==> expected: <true> but was: <false>`.
Reaching this assertion establishes that `[11, 22]` and earlier result-set closure checks passed;
no update count `1` was observed. Loop completion implies terminal `-1`, but the later explicit
terminal-count assertion and data-value check were not reached. These are source/control-flow
inferences from the report, not a captured per-call JDBC trace. No complete interleaved return
sequence or other intermediate count values were retained; do not invent them.

Independent task `9bf09a2e-779f-48cb-a0ed-bc6ea54a6af5` and combined task
`30d16db6-4faa-4800-b6b1-0c9d50e8d5bf` retain the failure. Evidence paths relative to the private
archive are `original-229/evidence/{task-id}/worker-report-report.json.sanitized.json`;
`closeout/kingbase-result-sequence-followup.json` records SQL, expected/observed values and limits.
Review the implicit-result/JDBC contract before assigning a cause. This is not an established driver
defect; assertions and historical results remain unchanged. No new live testing is part of closeout.

### Oracle FULL follow-up (2026-10-19)

Task `200dfecd-7606-4e82-b499-25fe978133a1` was created from the real New Run page against
Oracle 19.3.0.0.0, ojdbc11 21.13.0.0.0 (JAR SHA-256
`d5672fe8bb1403df85bfedd1d50a09dd10c9b0ae8527e67dd0e9a297ea98780d`). Companion orai18n,
xdb and xmlparserv2 21.13.0.0 and the Oracle DataSource/pool/XA Providers were configured.
Actual session identity and schema were checked. Catalog, page request, frozen metadata,
Worker report and persisted results contain the same 270 IDs, with no exclusions. Budget was
1800 seconds, default method timeout 60 seconds; task duration was 94.296 seconds.

There are 270 terminal records: 251 PASSED, 10 FAILED, 9 ABORTED. Exactly 261 contracts were
invoked; 2 preparation failures and 7 shared-fixture blockers were not invoked. Contract conclusions
are a separate dimension: 251 CONFORMS, 9 CONTRACT_VIOLATION, 1 PLATFORM_ERROR,
2 PREREQUISITE_UNMET and 7 BLOCKED_PREREQUISITE. No permitted-unsupported, NOT_RUN,
INCOMPLETE or timeout outcomes were recorded. The task ended FAILED/WORKER_EXECUTION_FAILED;
this is not an all-pass or all-contracts-executed acceptance.

The legacy Oracle array resource failed initialization with ORA-00933 (SQLState 42000, code 933)
and still contains VARCHAR2 substitutes rather than JDBC array columns; eight array contracts did
not run. `statement.result_sequence` lacks `multiple_results_ddl.sql`. Recorded execution failures
cover parameter ordinals, lock/query timeout and cancellation behavior, ROWID type metadata,
batch generated keys and SQLXML stream text. The batch-key-by-name NullPointerException remains
an unresolved-origin PLATFORM_ERROR, not an established driver defect. The raw per-case exceptions,
phases and SQL codes are retained; no assertion or recorded result was changed. No supplemental
writable Oracle task was started after the cleanup discrepancy described below.

The actual original Worker report was copied and sanitized before runtime cleanup, together with
API download evidence (`source=PERSISTED_WORKER_EVENTS`), frozen inputs, selection audit,
artifact fingerprints, source snapshot and SHA-256 manifests, in the external private archive
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/original-229/preserved-prior-evidence/evidence/200dfecd-7606-4e82-b499-25fe978133a1/`.
Unredacted source hashes and sanitized-copy hashes are explicitly separate; no private JAR or
credentials belong in Git. The older Kingbase FULL original remains unavailable.

Worker ownership evidence reports successful cleanup of the 36 task-owned root records and
read-only checks found no task-prefix residue. Separately, two pre-existing LOB dictionary entries
are missing afterward and neither is in the ownership ledger. Their historical parent mapping and
causal operation remain unknown. Task-owned cleanup success does not establish no impact on
existing objects. Broader Oracle acceptance remains incomplete due to this unresolved difference,
the nine uninvoked contracts and terminal-page/filter/download-button review not completed.

### Read-only Oracle LOB difference investigation

Status: **Cause undetermined; awaiting additional historical evidence. Unresolved.** Two
pre-existing LOB dictionary entries are absent from the retained post-run inventory. Neither
business-data loss nor absence of impact is established. Internal object names, connection identity,
queries, inventories and diagnostic output are restricted archive material and are not published here.

The retained pre/post USER_OBJECTS queries have the same columns, ordering and filters; the
retained session user/current-schema checks agree. The two missing records are OBJECT_ID entries,
not row counts. Their historical parent table/column mapping was not captured. Original private
capture streams were deleted during earlier cleanup; hashes cannot reconstruct them or certify
historical EOF, SCN, DBID/PDB or edition consistency. No retained capture error establishes or
excludes incomplete collection.

Only SELECT was used in the follow-up. Current dictionaries, recycle-bin views, SQL cache and
existing audits did not establish a parent mapping or causal operation. SQL cache is incomplete;
cleanup statements reconstructed from unchanged source/ownership records are explicitly distinct
from captured SQL. Audit coverage for historical ordinary own-table/background operations was
not established. Empty audit/cache and absence of task-prefix residue do not establish safety.

Naming, explicit placeholder expansion, connection-scope checks and ownership cleanup review found
no confirmed shared defect. The synthetic `OracleOwnershipIsolationTest` checks parent-only DROP,
metadata dependencies as evidence, and preservation of unknown/failed CREATEs. It does not reproduce,
explain or fix the disappearance. No production cleanup logic was changed and no write reproduction,
restoration, DDL or cleanup was performed in the historical Oracle scope.

Further attribution requires DBA-provided **existing** pre-run LOB-to-table/column mapping or
DDL/dictionary backups, audit/redo/archive/background diagnostic history, and original capture
streams/SCNs/database identity if retained elsewhere. Do not enable settings or repeat investigation
without new evidence. Keep the historical account/schema closed to write tests; future writable
validation must use an independent dedicated test account/schema.

Oracle array preparation, multiple-result resource, batch-key NPE and page review remain separate
OPEN follow-ups. The former temporary management database is gone; archived JSON survives but
its live page does not. Do not rerun FULL for screenshots or describe offline JSON review as clicks.
The complete private evidence archive is outside the checkout under
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/`:
`original-229/` preserves the 229 files plus their original SHA-256 manifest, and `closeout/`
records unresolved items and local commit provenance. This is a verified local archive, not a claim
of off-device backup. Only formal sanitized summaries and synthetic tests belong in Git.

### Artifact and version sources

Observed on 2026-10-16 using the user-supplied artifact and database; these are separate sources:

| Source | Observed value |
| --- | --- |
| JAR filename | `kingbase8-9.0.1.jar` (filename label only) |
| SHA-256 | `662ad6f0646cc3fb9b552c9fff93e09bf9eb12e4a9f33f0dec18a624cb48104c` |
| SPI / concrete class | `com.kingbase8.Driver`, implements `java.sql.Driver` |
| Manifest Implementation-Version | `V008R006B0001` |
| Manifest Bundle-Version | `0.0.0.V008R006B0001` |
| Manifest JDBC Specification-Version | `4.2` |
| Offline Worker Driver major/minor | `0.0`; `jdbcCompliant=false` |
| Connected metadata driver name/version | `Kingbase8 JDBC Driver` / `V008R006B0001` |
| Connected metadata database name/version | `KingbaseES` / `12.1` |
| Server `version()` | `KingbaseES V009R002C016` |
| Server `SHOW database_mode` | `oracle` |

The filename, Driver major/minor, Manifest version, connected driver version, JDBC database version
and server build are not interchangeable. No supported interval was fabricated from them. The
isolated driver accepted `jdbc:kingbase8://example.invalid:4321/test` and rejected the PostgreSQL
protocol. URL acceptance only checks format; it does not prove connection or SQL compatibility.

### Narrow timeout reproduction

The test-only [KingbaseTypeInfoDiagnostic](../jdbc-test-worker/src/test/java/com/jdbctest/worker/KingbaseTypeInfoDiagnostic.java)
uses the existing isolated loader. It is not part of the service classpath or default CI execution.
Prepare a private connection-probe JSON request locally (`driver.driverClass`, `driver.artifacts`,
`connection.jdbcUrl`, `connection.username`, `connection.password`), restrict its permissions and
never commit it. From the repository root after building the Worker:

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
java -cp "jdbc-test-worker/target/test-classes:jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar" \
  com.jdbctest.worker.KingbaseTypeInfoDiagnostic < "$PRIVATE_PROBE_REQUEST"
```

`PRIVATE_PROBE_REQUEST` is an operator-supplied private path, not a repository configuration or a
credential value. Use an external process deadline and clean the private file/process after use.
The original-scenario reproduction additionally needs a dedicated schema, explicit consent,
unique object prefix and the existing Worker request with `includeScenarios` containing only
`database_metadata.get_type_info`; retain ownership cleanup even on timeout. This does not require
running all CORE scenarios again.

## Testing

```sh
./mvnw clean verify
./mvnw -Pworker-e2e,server-integration verify
npm --prefix jdbc-test-web ci
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run test
npm --prefix jdbc-test-web run build
python3 scripts/run_contract_tests.py
```

Default Maven tests do not require user databases. Docker profiles exercise disposable platform/target PostgreSQL and MySQL; Docker-unavailable integration tests skip. `DeterministicOrchestrationTest` uses test-only H2 and a controlled Worker executable to check scheduling, REST/SSE, cancellation and persistence. Production remains PostgreSQL with real Workers.

Browser checks use separate mocked and real projects:

```sh
cd jdbc-test-web
npx playwright install chromium
npm run test:e2e
export JDBC_TEST_E2E_DRIVER_JAR='/absolute/path/to/postgresql.jar'
export JDBC_TEST_E2E_DRIVER_VERSION='your-driver-version'
export JDBC_TEST_E2E_TARGET_URL='jdbc:postgresql://127.0.0.1:55433/jdbc_ui'
export JDBC_TEST_E2E_USERNAME='jdbc_ui'
export JDBC_TEST_E2E_DATABASE='jdbc_ui'
export JDBC_TEST_E2E_SCHEMA='authorized_test_schema'
export JDBC_TEST_E2E_PASSWORD='your-target-password'
npm run test:e2e:real
```

The real project requires running Server/Worker, a clean platform database/artifact store and a separate authorized target PostgreSQL. Missing inputs fail rather than fall back to mocks. It writes test data; use fresh disposable acceptance storage and never use shared/business databases. After each session, stop owned services and remove the databases/storage/configuration created for that session; keep only requested reports/screenshots in ignored local paths. Trace collection is disabled. `PLAYWRIGHT_CHANNEL=chrome` can use installed Chrome. Production-mode checks are described in [Deployment](deployment.md).

For browser checks against the freshly built assets, use `JDBC_TEST_E2E_PREVIEW=true PLAYWRIGHT_CHANNEL=chrome npm --prefix jdbc-test-web run test:e2e` after building. This runs the mocked project only, captures explicitly labelled fixture screenshots, and verifies the loaded bundle SHA-256 against `dist`. It does not start QA or execute target database operations.


## Shared frontend UI

The white CPA Keeper UI foundation is pinned to upstream [`2c09d65d6a90b1dc9802fe7b26704a1d95d0c909`](https://github.com/Willxup/cpa-usage-keeper/tree/2c09d65d6a90b1dc9802fe7b26704a1d95d0c909), MIT © 2026 Will. The full notice and source/adaptation list are shipped in `jdbc-test-web/public/third-party-notices.txt`.

- Direct port: `web/src/styles/menu.scss` → `src/styles/upstream/_menu.scss`.
- Adaptations: upstream white-theme tokens, Button/Input/Card/Select/Modal/EmptyState presentation, header and DashboardToolbar geometry → `src/styles/foundation.scss` and existing shared components. Namespaced styles, blue primary actions, existing Radix interactions, semantic tables and React Router navigation replace upstream-specific interactions and business dependencies.
- Local additions: JDBC URL view/copy, stable-ID Run titles and grouped snapshots, Metric and execution semantics, Tooltip using Radix. No CPA account/token/cost/auth state, Chart.js or Zustand is imported.

Use `components/ui` for controls, Card and dialogs, `components/common` for metrics/loading/empty/errors, and `components/data-table` for tables/pagination. Select consumes option values but renders a portalled Radix listbox; do not introduce native selects or page-specific menu skins. Tailwind handles layout; `foundation.scss` is the authority for visual tokens/control surfaces, with `index.css` mapping those tokens to Tailwind and defining metric/selection semantics. Sass is pinned to 1.99.0. Both language resources remain bundled locally.

`npm run lint`, `npm run test`, `npm run build` and the mocked `npm run test:e2e` verify UI behavior. The explicitly selected `history-readonly` Playwright project only reads preserved history; it never creates runs or probes connections. It requires an already verified viewer backend, `JDBC_TEST_HISTORY_READONLY=true`, and the current built preview. Standard `real` E2E still requires a separate writable acceptance database and must not be run against historical QA.

The test-only nested-menu/tooltip/toast fixture runs with `PLAYWRIGHT_CHANNEL=chrome npx playwright test --project=primitives` (Vite dev, not preview); its HTML entry is outside the production router/build.

### Testing driver-owned DataSource implementations

Driver upload and **Edit metadata** expose optional standard DataSource implementations. Supply classes
from the uploaded JARs; its standard cases are always present and selected by default. Unconfigured
interfaces report unmet execution prerequisites, not passes. The platform's connection facade is tested only as infrastructure.
Queued runs freeze these definitions and include them in reports. No vendor class is inferred.

CLI/runtime YAML uses `db.data_sources` with the same definitions as API `dataSources`:

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

Property names map to public single-argument JavaBean setters. Defaults are `URL`, `user`, and
`password`; additional `db.properties` use scalar bean setters (String/int/long/boolean).
These names are not credentials. XA execution also requires the database's XA prerequisites.
Examples for other public drivers: MySQL `com.mysql.cj.jdbc.MysqlDataSource`,
`MysqlConnectionPoolDataSource`, `MysqlXADataSource` in the same package; Oracle
`oracle.jdbc.pool.OracleDataSource`, `oracle.jdbc.pool.OracleConnectionPoolDataSource`,
`oracle.jdbc.xa.client.OracleXADataSource`.

`db.password` in Java runtime configuration is a literal string: `${...}`, backslashes and whitespace
are preserved. Explicit `db.password_env: MY_DB_PASSWORD` resolves one environment variable, without
expanding its value; missing variables and simultaneous `password`/`password_env` fail configuration.
CLI user configs use `password_env` and generate a private runtime literal. Never commit credentials.

See [Execution safety](execution-safety.md) for embedded credentials, adapter preflight, cleanup evidence and historical compatibility. This work does not change the three-step source startup, theme modes, Key or target authentication configuration.

## CLI internal driver upgrade regression

See [internal baseline workflow](internal-regression.md) for explicit local/private baseline selection,
evidence requirements, allowed driver artifact changes and CI exit codes.
See [capability contract decisions](jdbc-capability-contracts.md) for the reviewed JDBC assertions,
independent declarations/observations and narrow plan requirements. Historical reports remain unchanged.

### Source maintenance and historical compatibility

New runs execute the built-in standard JDBC catalog only. Uploaded adapter and SQL package pages,
import/version APIs, CLI commands and Worker executors are removed. `GET /api/adapters` supplies built-in
connection choices only. Developers maintain `adapters/catalog.json`, manifests, dialect resources and
case source, then rebuild/deploy. Server still never loads uploaded drivers or executes target SQL.
Protocol 2 advertises `adapterSnapshotVersion=1` for built-in snapshots; deploy matching Server/Worker.
Unknown Worker `sqlPackage`/`adapterPackage` fields fail strictly rather than falling back to JDBC.

V9/V10 migrations remain unchanged. Old package tables, drivers, connections, executed runs and reports
are neither deleted nor rewritten. Historical SQL definitions return as read-only JSON without active
validators; reports retain SQL_FEATURE and cannot enter JDBC baselines. Old custom-adapter connections
remain readable but need a built-in type or explicit compatible dialect before a new run. Queued retired
package runs reaching the executor fail with `RETIRED_PACKAGE_EXECUTION`, retaining not-run and cleanup UNKNOWN evidence.
Normal service restart still interrupts all earlier nonterminal runs with `SERVER_RESTART`.
Old clients supplying `sqlPackageFingerprint` receive `SQL_PACKAGES_RETIRED`, never a substituted run.
`PostgresPackageMigrationTest` retains empty/V8 upgrade and raw historical-data restart checks; it may
only target the session's own disposable PostgreSQL.

### Driver validation metadata and version sources

Driver validation loads the selected class in the isolated Worker and calls only
`java.sql.Driver.getMajorVersion()`, `getMinorVersion()` and `jdbcCompliant()`; it does not connect.
The registered version is an editable operator label, not the probe result. The two API integers
need not equal a full release string. A genuine `0.0` is retained with a review hint.
`jdbcCompliant()` is the driver's declaration, not a platform test or certification conclusion.
See the official [Driver API](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Driver.html).

`DRIVER_VALIDATED` includes `loadedArtifact`: the actual defining JAR's filename and SHA-256,
plus its manifest `Implementation-Version` (package section, then main attributes) and
`Bundle-Version` (main attributes). These independent declarations are not inferred from filenames,
registered labels, dependencies, or vendor-specific methods. Missing manifest fields display as
not provided. Worker verifies the defining JAR belongs to the selected artifact list. Server never
loads driver classes. Older valid events without artifact evidence remain explicitly unknown.

Server rejects missing, wrong-type, negative or out-of-int-range API versions, nonboolean compliance,
class mismatches, duplicate success events and malformed artifact evidence with
`DRIVER_VALIDATION_PROTOCOL_ERROR`; it does not coerce absent data to 0/false. Frontend also rejects
invalid responses. Successful validation means loading and metadata reads completed, not that a
connection or full compatibility test succeeded. Responses remain transient, not persisted probes.
Deploy/restart matching Server and Worker builds after updating; restarting is required for already
running backend code. `DriverValidationProtocolTest` invokes real Worker serialization and Server
parsing with synthetic drivers (connection calls deliberately throw), including classpath order and
mutated missing/type-invalid serialized events. No real database is needed for these checks.
