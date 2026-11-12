# JDBC Test

[简体中文](README.md) / English

A test platform for validating JDBC driver interface behavior against real databases.

## Core features

- Manage external JDBC drivers and dependency JARs; validate loading in an isolated Worker.
- Configure target connections, separate credentials and authorized scope; probe connectivity and metadata.
- Select standard JDBC scenarios (CORE / FULL / CUSTOM) and execute in a separate Worker JVM.
- Follow progress, inspect individual results and cleanup evidence, and download JSON reports.
- **CLI only:** driver A/B upgrade regression, baseline selection and comparison reports; see [regression](docs/internal-regression.md).

## Current built-in database adapters

| Database | Platform adaptation status | Recorded combination or validation scope | Configuration and limits |
|---|---|---|---|
| PostgreSQL | official | 15.2 / driver 42.7.2 | [Connection setup](docs/usage.md#database-configuration) |
| MySQL | official | 8.0.30 / driver 8.3.0 | [Connection setup](docs/usage.md#database-configuration) |
| Oracle | official | 19.3.0.0.0 / driver 21.13.0.0.0 | [Privileges and LOB limits](docs/database-validation.md#known-limitations) |
| KingbaseES | official | Server V009R002C016; oracle mode; JDBC product 12.1; driver V008R006B0001 | [Combination boundary](docs/database-validation.md#recorded-combinations) |
| GaussDB | candidate | Empty `validated_combinations` | [Reuse boundary](docs/database-validation.md#recorded-combinations) |
| Dameng / DM | official | 8.1.3.162 / driver 8.1.3.62; COMPATIBLE_MODE=0 | [Acceptance scope and history](docs/database-validation.md#dm-platform-acceptance) |

The active index is [adapters/catalog.json](adapters/catalog.json). Status and scope come from each
`adapter.json`, its `qualification` and existing evidence; see [evidence sources](docs/database-validation.md#recorded-combinations).
`official` is an internal platform adaptation status, not vendor certification or an all-tests-pass claim.
`candidate` denotes an adapter awaiting formal acceptance; Dameng registers only the precise combination in the validation records, with older-driver history kept separate.
`supported_versions` controls admission, not proof of every version tested; capability booleans are declarations.
GaussDB's reuse of PostgreSQL infrastructure does not validate every GaussDB/openGauss product.
SQL Server creation, probes and execution are retired; [historical reads](docs/database-validation.md#known-limitations) remain.

## Quick start

<a id="start-the-website"></a>
### Run the website

This is one independent **Docker with bundled platform PG** path. Use Git, Docker Engine/Desktop,
Compose v2, shell/PowerShell, free port 8080 and network access for image/dependency downloads.
No host Java/Node/Maven/Python is required. For a new installation, the selected project must have no
existing configuration, volumes or application data. Restore existing installations with their original
configuration following [Deployment](docs/deployment.md#operations).

```sh
git clone https://github.com/cocoCzl/jdbc-test.git
cd jdbc-test
./jdbc-test docker --bundled-pg
```

Configuration lives in source-root `.env`. This mode generates its PG password and independent Master Key;
do not copy the external-PG template first. Open the printed ready URL, normally `http://127.0.0.1:8080`.
Stop with `./jdbc-test docker --bundled-pg --stop`; configuration and data remain.

Other independent paths: [Docker with external PG](docs/deployment.md#docker-external) or an
[extracted Java 21 bundle](docs/deployment.md#native), running `./jdbc-test start` from its own root.

### Source development and builds

Source development needs **JDK 21, Node >=22.12/npm**, the Maven wrapper and a dedicated platform
PostgreSQL database with migration permissions. In the source root, copy only if absent, then edit
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` in `.env`:

```sh
umask 077
cp -n .env.example .env
# Edit the actual .env platform database fields before running:
./jdbc-test dev
```

Open the printed ready URL, normally `http://127.0.0.1:5173`. Ctrl+C stops the launched frontend/backend.
Keep the original `.env`, Master Key and data for later starts; see [local source startup](docs/development.md#local-source-startup).
Build machines also require **Python >=3.9**: run `./jdbc-test build` in the source root.
[Bundle](docs/deployment.md#build-bundle) runtime machines need only Java 21, shell/PowerShell and platform PG.
On Windows use the corresponding `./jdbc-test.ps1 dev`, `build`, `docker --bundled-pg`,
`docker --external-pg`, or bundle `start` for the chosen mode; see [Windows commands](docs/deployment.md#windows).

## First test

1. **Upload:** provide licensed driver and dependency JARs and validate loading.
2. **Connect:** choose the target type, enter URL, separate credentials and exact scope, probe and save.
3. **Create a run:** select CORE / FULL / CUSTOM; check cases, Providers, budget and write scope, then explicitly consent.
4. **Read results:** inspect progress, individual JDBC conclusions and extra suite requirements; download the report.
5. **Check cleanup:** review `cleanupEvidence` and retained/uncertain objects; establish ownership before manual removal.

Follow the [Usage guide](docs/usage.md). Third-party target drivers are not distributed with the project.

## Results and key boundaries

`SUCCEEDED` means execution/report processing completed, not that every case passed. Legal unsupported
is not a feature pass. Evaluate JDBC conclusions, extra SUITE requirements, preparation/environment
problems and cleanup evidence separately. `SUITE_REQUIREMENT_UNMET` is a subset of `NOT_EVALUATED`,
not an additional count; see [JDBC reference](docs/jdbc-capability-contracts.md).
Do not rank databases using historical reports with different versions or selections.

Platform PostgreSQL stores website state; configure target databases separately. Tests execute **DDL/DML**
and need explicitly authorized dedicated databases/schemas. The website currently has no built-in
authentication; restrict network access. Use trusted drivers: Worker isolation cannot guarantee safety
from malicious drivers. Successful owned-object cleanup alone does not prove existing objects were unaffected.

<a id="documentation"></a>
## Documentation and contribution

Use the [English documentation index](docs/README.en.md): [Usage](docs/usage.md) →
[Deployment and operations](docs/deployment.md) → [Development and contribution](docs/development.md).
It also links JDBC interpretation, CLI regression and database validation records.
Chinese readers can start with [中文文档导航](docs/README.md).

<a id="advanced-guides"></a>
Read [development checks and cleanup rules](docs/development.md#testing) before contributing;
see [examples](examples/README.md) for CLI configuration.
Licensed under [Apache License 2.0](LICENSE); retain upstream notices.
