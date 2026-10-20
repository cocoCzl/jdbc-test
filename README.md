# JDBC Driver Compatibility Test Framework

English | [简体中文](README.zh-CN.md)

A JDBC 4.3 compatibility and regression framework for database vendors and driver maintainers. Run a versioned suite against your database in an authorized test namespace and generate sanitized JSON, Markdown and HTML reports.

The platform stores its management data in **PostgreSQL**, separately from target databases. It supports custom database adapter imports in Web, internal JDBC driver A/B regression in CLI, and declarative SQL feature packages in Web/CLI. Imported adapters remain local; import or connection success does not establish compatibility.

JDBC reports assess implemented API contracts and separate capability declarations, observed behavior and plan requirements. SQL reports assess package-defined functionality and show step expectations and actual results; they do not contribute to a JDBC compatibility rate or JDBC baseline.

Tests execute DDL/DML. Use a dedicated database/schema and review [Execution safety](docs/execution-safety.md) before running. This is not an official JDBC certification suite or a performance, migration, business-SQL or proprietary-API test tool.

## Quick start: copy, edit, run

Prepare **JDK21, Node >=22.12/npm** and a dedicated, already-created **platform PostgreSQL** database/account. In the **source root**:

1. Copy **`.env.example` → `.env` only if `.env` is absent**. Keep any existing configuration/Key.
2. Open that actual **source-root `.env`** in your local editor. Set **`SPRING_DATASOURCE_URL`**, **`SPRING_DATASOURCE_USERNAME`** and **`SPRING_DATASOURCE_PASSWORD`** to your platform's full JDBC URL, login and existing password. Example URL: `jdbc:postgresql://localhost:5432/jdbc_test`; replace host/port/database, include database name and keep credentials out of the URL. New platform: leave `JDBC_TEST_MASTER_KEY` empty; existing platform: keep the original Key.
3. Execute **`./jdbc-test dev`**, then open the printed address. For a new empty platform the tool automatically saves the Key after state checks, builds Java/Worker, installs frontend dependencies if needed and starts source backend + Vite. Ctrl+C stops this launch; later run the same command with original `.env`/Key/data.

**Terminal commands — Linux/macOS, source root (edit between copy and dev):**

```sh
umask 077
cp -n .env.example .env
# Edit .env locally: the three SPRING_DATASOURCE fields (file: .env).
./jdbc-test dev
```

**PowerShell terminal — source root:**

```powershell
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
# Edit the actual .env locally: URL, username, existing password.
./jdbc-test.ps1 dev
```

Missing `.env` stops **before Java/toolchain/build**, with the copy location; no first-run generation round trip. Never source `.env` or put secrets in command arguments. See [Development](docs/development.md#local-source-startup) for preparation, literal password syntax, daily startup and optional IDEA Debug.

To run the website without source development, choose [Deployment](docs/deployment.md): `./jdbc-test docker --bundled-pg`, `./jdbc-test docker --external-pg`, or **`./jdbc-test start` in an extracted non-Docker bundle**. Build that bundle with `./jdbc-test build`. Docker hosts need Docker/Compose only; bundle runtime needs Java21 only. Build machines also need Python >=3.9 for the archive step. Each environment has one private configuration; ordinary users only use the unified entry.

## First Web run

After startup, prepare a dedicated target database/schema and a trusted JDBC driver:

1. In **Drivers**, upload the driver and required dependency JARs, then validate.
2. In **Database adapters**, import a ZIP exported by `pack-adapter`, or use a built-in adapter. See [template/export instructions](docs/web-adapters.md#migrate-a-cli-package).
3. Create a connection with the chosen driver, adapter and authorized target scope; test the connection.
4. In **SQL test packages**, import [postgresql-basics.json](examples/sql/postgresql-basics.json) or [mysql-basics.json](examples/sql/mysql-basics.json), select its version, connection and cases, then create a task.
5. Open task progress and report to inspect each step, expected/actual values, cleanup and frozen driver/adapter/SQL package fingerprints. JDBC runs remain a separate entry.

Use the example matching the target product. Upload validation, connection success and test success are distinct. See [SQL package workflow and limits](docs/sql-packages.md).

## Documentation

| Guide | English | 简体中文 |
|---|---|---|
| Deployment, configuration and backups | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| Architecture, local development, APIs and Worker protocol | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| Authorized scope, cleanup and result accounting | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |
| Custom adapter templates, import and snapshots | [Web adapters](docs/web-adapters.md) | [Web 适配包](docs/web-adapters.zh-CN.md) |
| SQL package format, examples and CLI/Web workflow | [SQL packages](docs/sql-packages.md) | [SQL 测试包](docs/sql-packages.zh-CN.md) |
| Private driver A/B baselines and CI exits | [Internal regression](docs/internal-regression.md) | [内部回归](docs/internal-regression.zh-CN.md) |
| Tested scope, fixes and remaining gaps (2026-10-20) | [Acceptance summary](docs/acceptance-2026-10-20.md) | [验收摘要](docs/acceptance-2026-10-20.zh-CN.md) |

## Database support

| Database | Adapter ID | Status |
|---|---|---|
| PostgreSQL | `postgresql` | Official |
| MySQL | `mysql` | Official |
| Oracle | `oracle` | Official |
| GaussDB | `gaussdb` | Candidate |
| SQL Server | `sqlserver` | Candidate |
| KingbaseES | `kingbase` | Official |

Adapter status describes integration maturity, not certification or universal JDBC compatibility. Local packages can reuse supported dialects without rebuilding the platform; unsupported dialects are rejected.

The [2026-10-20 acceptance summary](docs/acceptance-2026-10-20.md) records real Web/Server/Worker validation on disposable PostgreSQL **16.15 / pgjdbc 42.7.11**, four MySQL SQL cases on **8.4.11 / Connector/J 8.3.0**, and exactly six PostgreSQL JDBC cases for driver **42.7.2 → 42.7.11**. The custom PostgreSQL package validates the integration mechanism, not a proprietary database. Other products/versions/modes and the complete JDBC matrix were not validated in this session.

Earlier KingbaseES V009R002C016 oracle-mode / driver V008R006B0001 evidence remains in its [validation record](adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json). Oracle 19.3 / ojdbc11 21.13 has preparation gaps and an unresolved LOB dictionary difference; see the [historical follow-up and limits](docs/development.md#oracle-full-follow-up-2026-10-19). Neither record establishes complete product compatibility.

## Development requirements

Source Web development requires JDK 21, Maven Wrapper 3.9.15, Node >=22.12.0/npm and a dedicated platform PG; follow [local source startup](docs/development.md#local-source-startup) for config/Key/Worker preparation. Python >=3.9 and an authorized target database/driver are additionally needed for CLI target tests, not for opening the development website.

## CLI quick start

From the source root, build the Worker once with JDK 21, then validate a package without a database:

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
python3 -B scripts/runner.py sql-validate --package examples/sql/postgresql-basics.json
```

Create a private connection config using the [complete SQL config example](docs/sql-packages.md#cli-execution), set its password environment variable through your secret mechanism, and point it at an authorized existing namespace and local driver. Then:

```sh
python3 -B scripts/runner.py sql-run --config /private/sql-config.json \
  --package examples/sql/postgresql-basics.json --case basic.query \
  --output report/sql-first-run
```

Use a new output directory each time. SQL execution exits **0** only when all selected cases pass and cleanup is confirmed, **1** for completed failures, **2** for invalid/incomplete execution. `sql-validate` exit 0 only means the definition is valid.

For JDBC driver A/B, follow the [internal regression quick start](docs/internal-regression.md#run-a-select-run-b-compare): prepare the connection config, explicit database identity/build/mode and a persistent private comparison key; run A, select its report with `baseline create`, change only the driver, run B, then `compare --scope internal`. Comparison exits **0** for no new regressions (old failures remain visible), **1** for new regressions, **2** for incomparable/incomplete evidence. This 0 does **not** mean all tests passed. Default formal comparison still requires formal eligibility; legacy reports missing evidence must be rerun. SQL reports cannot be used as JDBC baselines.

List adapters with `python3 scripts/runner.py adapters`. Keep configs, keys and driver JARs local. `report.json` is authoritative; review local diagnostics before sharing. See [capability contract decisions](docs/jdbc-capability-contracts.md).

## Examples and custom adapters

[Usage examples](examples/README.md) cover built-in/private databases, Hikari/DriverManager, JDBC properties, environment-backed secrets and multi-JAR drivers. `init-custom` reuses Oracle/MySQL/PostgreSQL dialect assets for a local-only adapter. A local adapter contains `adapter.json` and SQL assets; select it with `runner.py init --adapter /path/to/adapter`. Local results retain their original provenance and can be explicitly selected as internal driver-upgrade baselines; see [internal regression](docs/internal-regression.md).

Adapters declare identity/version ranges, capabilities, assets, namespace lifecycle, least privileges, verified combinations and known differences. To use a local driver, set generated `db.driver` to:

```json
{"kind": "local", "path": "/absolute/path/to/driver.jar"}
```

Do not commit proprietary or third-party JDBC binaries.

## Coverage and selection

The default CLI `test_profile=full` selects the JDBC 4.3 baseline: connections/transactions, statements, batches, generated keys, ResultSet, types, metadata, LOB, RowSet, SQLXML, savepoints and CallableStatement. Use `core` for strict cross-database scenarios:

```json
{"test_profile": "core", "test_filter": {"timeout_ms": 60000}}
```

New Run defaults to all applicable tests, with module and individual exclusions. Static false declarations no longer disable standard verification. Missing Providers/SQL preparation are prerequisites; permitted unsupported requires an observed response allowed by the specific API. CORE explicitly retains 153 legacy IDs; FULL covers 270 implemented standard cases, with no real vendor extensions yet, rather than every JDBC API. View stable IDs with `python3 scripts/runner.py coverage --profile full`; narrow selection with `test_filter.include_tests` / `exclude_tests`. Shared-connection stress (`concurrency.enabled=true`) is rejected.

[Import custom adapter packages in Web](docs/web-adapters.md): export an existing CLI package with `pack-adapter`, import it, then select it when creating a connection. Packages persist in the platform database and tasks freeze their resources and local provenance.

## Build the CLI archive

```sh
python3 scripts/build_release.py --output dist
```

The source-only ZIP excludes third-party driver JARs and includes a SHA-256 file. Production Compose deployment uses the repository checkout.

Build the native Web/Server distribution separately:

```sh
./jdbc-test build
```

Produces `dist/jdbc-test-server-<version>/`, `.tar.gz`, `.zip` and checksums containing Server, Worker, frontend and launchers. Building requires JDK 21, Python and Node/npm; running requires only Java 21 and existing PG. Follow [non-Docker bundle startup](docs/deployment.md#native) to configure and start.

## License

Apache License 2.0.
