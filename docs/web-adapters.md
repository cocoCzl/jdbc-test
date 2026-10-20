# Importing custom database adapters in Web

English | [简体中文](web-adapters.zh-CN.md)

Open **Database adapters** in Web to import a local package. No platform source change or rebuild is
needed for products that can use `postgresql`, `mysql`, `oracle` or `sqlserver`. Other dialects are
rejected. Import validates declarations and resource completeness; it does not verify that the SQL,
product version, compatibility mode or driver works. This phase adds neither a SQL engine nor Java
plugins. SQL is executed only by the existing authorized Worker test lifecycle.

## Migrate a CLI package

In a source checkout, generate a template if needed:

```sh
python3 -B scripts/runner.py init-custom --help
# Example template: replace the product/driver/endpoint hints with your own values.
python3 -B scripts/runner.py init-custom --id private-db --dialect postgresql \
  --driver-class example.jdbc.Driver --driver-dir /private/drivers \
  --url jdbc:example://localhost/disposable --username test_user \
  --adapter-output /private/adapters/private-db --output /private/config.private.json
python3 -B scripts/runner.py pack-adapter \
  --adapter /private/adapters/private-db --output /private/adapter.zip
```

Review `adapter.json` before export: identity regexes, supported version bounds, capabilities,
namespace and privilege declarations must describe your product. Do not copy another vendor's
validated combinations as evidence. `init-custom` currently generates Oracle/MySQL/PostgreSQL
examples; an existing valid SQL Server package can also be exported/imported. Template generation
and export do not connect to a database. The output ZIP is exclusively created, never overwritten.

`pack-adapter` uses CLI resource resolution, copies the resolved asset directory and pool YAML into
portable `ddl/`, `dml/`, `pool/` roots and copies explicitly declared combination evidence under
`evidence/`. It preserves core declarations; only resource roots/evidence locations are normalized.
Local absolute asset roots therefore need no equivalent directories on Server. No JARs, connection
configurations or passwords are collected. Review the manifest/extensions and SQL yourself before
sharing; local driver source paths are declarations and are not used to find Web driver artifacts.
The exported package can also be extracted and selected by the CLI as an ordinary local package.

1. Under **Drivers**, upload the JDBC driver and required dependency JARs together, then validate.
   Validation loads driver code in a short-lived Worker; Server only stores/inspects artifacts.
2. Under **Database adapters**, select the ZIP and **Validate and import**. The result displays ID,
   name, version/revision, origin, dialect, matching constraints and declared version combinations.
   Imported packages always have effective `LOCAL` status and `IMPORTED` origin, including manifests
   claiming `official` or `candidate`. Those claims remain in frozen source bytes, not platform trust.
3. Select **Add a connection**. The adapter is preselected; select the independently uploaded driver.
   Supply an authorized existing disposable database/schema and credentials through the normal form.
   A successful connection probe does not validate adapter SQL or JDBC conformance.
4. Create a run, choose existing cases, review the scope/driver/adapter and authorize destructive
   tests. Existing namespace authorization, object prefix ownership and cleanup rules still apply.
   Web uses the authorized existing scope, rather than running CLI namespace creation/drop SQL.
5. Task details, report views and JSON reports show the adapter frozen at creation. They never look
   up the current library version to fill historical evidence. Missing historical snapshots remain
   explicitly unknown. Driver snapshots/fingerprints remain independent.

## Package boundary

The ZIP root must contain `adapter.json`, with the same strict JSON/manifest rules as CLI/Core.
Referenced DDL/DML must exist for every declared area (the existing `sqlxml=false` exception is
retained); Web additionally requires `<pool-root>/<assets.directory>.yaml` because pool resources
must be portable too. Official claims require their referenced evidence files to exist, but do not
confer official status. Core declaration fixtures and a CLI-export/Java-import test verify parity.
Regex engine portability remains the existing documented limitation.

Archive entries must be relative portable paths using letters, numbers, `_`, `-`, `.`, `/`, without
`..`, backslashes, absolute paths or duplicate files. Resources stay under declared asset roots or
`evidence/`. Executable suffixes/JARs/classes are rejected. Limits: 512 entries (directories included),
1 MiB per file, 8 MiB total expanded content and 8 MiB uploaded ZIP. Files are never extracted on
Server. Worker writes validated bytes as new regular files under its owned per-run workspace;
archive metadata cannot create symbolic links. Decompression/manifest errors report a stable code
and the missing field/resource where available. Unknown extension fields are preserved, not executed.

## Persistence, updates and retention

Migration V9 adds `imported_adapter` to the **platform** database. It stores the current package as
manifest bytes plus Base64 resource bytes, a content fingerprint and archive/concurrency state.
Include this table in platform backups; no separate mutable filesystem package directory is needed.
Application restart reloads it from persistence. Driver artifacts still use existing driver storage.

Version/revision are user labels. Imported SHA-256 covers exact manifest bytes and sorted resource
paths/bytes, independent of ZIP timestamps/compression/order. Built-in SHA-256 identifies its bundled
manifest only; its SQL stays in the Worker build. Neither digest is a signature or certification.

Identical active reimports are idempotent. Different content under the same ID returns a conflict
unless the UI explicitly selects **Update**, sending the displayed `expectedFingerprint`. Concurrent
changes are rejected. A dialect change requires a new ID. Built-in IDs cannot be overwritten.
Connections select an adapter ID and new tasks use its current explicit version; updates therefore
need review just as a new driver choice does. Existing tasks contain a full independent package,
execution declaration, public provenance, selected tests and driver snapshot in `executionInput`.
Their resources and report fingerprints survive later updates and application restarts.

**Archive** is the supported removal operation. Connection references block it. The ID/content stay
reserved and hidden from new selections, preserving evidence; there is no destructive purge or
version-history browser in this phase. Connection writes and archive checks serialize on the adapter
row. Earlier unreferenced library versions are retained only by tasks that froze them; keep original
ZIPs if you need to reselect an unused version. Current packages and task records are not garbage
collected by import/update. Worker temporary credentials still follow existing cleanup rules; the
run workspace remains subject to existing report retention.

Deploy matching Server and Worker builds. Protocol 2 retains existing fields and adds optional
`adapterPackage`/`adapterSnapshot`; the metadata handshake advertises `adapterPackageVersion=1`.
A Worker lacking snapshot support is rejected before a new snapshot-bearing task launches. Legacy
runs without these snapshots retain their existing bundled fallback and do not acquire new evidence.

## Validation scope

Automated tests use synthetic assets, a fixture driver/Worker and isolated H2 platform stores:
REST import → driver upload/validation → connection → selected task creation; explicit update and
queued task snapshots; Worker resource materialization; close/reopen persistence; malformed/missing/
escaping/unsupported/oversized packages; source forgery; built-in protection and referenced archive.
Browser fixtures exercise import/preselected connection and bilingual/mobile presentation. Fixture
success is not a real product/driver validation. No business/shared/historical database is accessed.

Web submits the reviewed adapter fingerprint when creating a task; a concurrent library change
returns `ADAPTER_CHANGED` and requires a refresh. Older API clients may omit this optional check.

## Development checks

The focused Java check covers 49 tests, including built-in/legacy behavior, reopen persistence,
Worker configuration, a queued imported task and the existing driver-provider snapshot workflow:

```sh
./mvnw -q -pl jdbc-test-core,jdbc-test-worker,jdbc-test-server -am test \
  '-Dtest=AdapterPackageSnapshotTest,AdapterManifestTest,AdapterExecutionConfigTest,AdapterControllerTest,ConnectionServiceTest,ImportedAdapterFlowTest,AdapterRestartTest,WorkerAdapterSnapshotTest,WorkerMainTest,WorkerProcessRunnerTest,LegacyEvidenceReportTest,DeterministicOrchestrationTest#queuedImportedPackageReachesWorkerAndReportAfterLibraryUpdate+providerMetadataRoundTripsAndQueuedRunUsesFrozenImplementations' \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 -B scripts/run_contract_tests.py
python3 -B -m unittest discover -s scripts -p 'test_java_rules.py' -v
python3 -B scripts/check_java_rules.py
cd jdbc-test-web
npm run lint
npm test
npm run build
PLAYWRIGHT_CHANNEL=chrome npm run test:e2e -- adapter-import.spec.ts
```

The browser test intercepts all API requests with fixtures. It is not a real Server/target run.
Tests need permission to create local child processes and temporary loopback listeners. The default
Playwright port is 5173; use an isolated test configuration/unused port when a development service
already occupies it. Temporary resources created for a verification session must be removed after
it; preserve fixture screenshots and pre-existing services. Run the repository's IDEA native
formatter separately when its instance lock permits; never bypass that lock.

Phase 3 closed the PostgreSQL migration gap: empty V1–V10 and populated synthetic V8→V9→V10,
old records and adapter/task snapshots across application restart passed on session-owned temporary
PostgreSQL 16. H2 and browser fixture evidence remain separately scoped. See [SQL packages](sql-packages.md#verification-in-this-phase).

Subsequent [real integration acceptance (2026-10-20)](acceptance-2026-10-20.md) verified browser
upload/import/connection, SQL execution, queued revision freezing and normal restart recovery with
a real PostgreSQL platform/target and Worker. The custom adapter tests the mechanism, not a proprietary
product. Initial H2/fixture checks above remain separately scoped.
