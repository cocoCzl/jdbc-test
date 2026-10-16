# JDBC Driver Compatibility Test Framework

English | [简体中文](README.zh-CN.md)

A JDBC 4.3 compatibility and regression framework for database vendors and driver maintainers. Run a versioned suite against your database in an authorized test namespace and generate sanitized JSON, Markdown and HTML reports.

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

## Documentation

| Guide | English | 简体中文 |
|---|---|---|
| Deployment, configuration and backups | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| Architecture, local development, APIs and Worker protocol | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| Authorized scope, cleanup and result accounting | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |

## Supported adapters

- PostgreSQL, MySQL and Oracle: official adapters with real-target validation evidence.
- GaussDB and SQL Server: implemented adapters awaiting real-instance validation.
- Other databases: local declarative adapters without changes to Java core.

See [verified combinations](docs/execution-safety.md#verified-combinations) for exact versions and acceptance limits.

## Development requirements

Source Web development requires JDK 21, Maven Wrapper 3.9.15, Node >=22.12.0/npm and a dedicated platform PG; follow [local source startup](docs/development.md#local-source-startup) for config/Key/Worker preparation. Python >=3.9 and an authorized target database/driver are additionally needed for CLI target tests, not for opening the development website.

## CLI quick start

```sh
python3 scripts/runner.py init \
  --adapter postgresql \
  --url 'jdbc:postgresql://localhost:5432/test' \
  --username tester \
  --password-env DB_PASSWORD \
  --consent

export DB_PASSWORD='your-password'
python3 scripts/runner.py run configs/config.local.json
```

List adapters with `python3 scripts/runner.py adapters`. Keep generated configs and driver JARs local; Oracle existing-schema mode also needs explicit `--namespace` authorization. Passwords come from environment variables. Reports are written under `report/`; review local `diagnostics.log` before sharing.

Compare an approved baseline:

```sh
python3 scripts/runner.py compare \
  --baseline baseline/report.json \
  --current report/postgresql_YYYY-MM-DD_HH-MM-SS/report.json
```

`report.json` is authoritative; Markdown/HTML are derived. New compatibility failures, environment/preflight errors, incomplete adapters and cleanup failures yield a nonzero exit code. Explicit capability skips and reviewed known differences do not block.

## Examples and custom adapters

[Usage examples](examples/README.md) cover built-in/private databases, Hikari/DriverManager, JDBC properties, environment-backed secrets and multi-JAR drivers. `init-custom` reuses Oracle/MySQL/PostgreSQL dialect assets for a local-only adapter. A local adapter contains `adapter.json` and SQL assets; select it with `runner.py init --adapter /path/to/adapter`. Local results remain experimental rather than formal baselines.

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

Optional APIs follow adapter capability declarations: `false` gives a sourced `known_unsupported` skip; `true` enables assertions. A skip does not prove lack of database support. View stable IDs with `python3 scripts/runner.py coverage --profile full`; narrow selection with `test_filter.include_tests` / `exclude_tests`. Shared-connection stress (`concurrency.enabled=true`) is rejected.

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
