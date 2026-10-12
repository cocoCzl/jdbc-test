# JDBC Driver Compatibility Test Framework

English | [简体中文](README.zh-CN.md)

A JDBC 4.3 compatibility and regression framework for database vendors and driver maintainers. Run a versioned suite against your database in an authorized test namespace and generate sanitized JSON, Markdown and HTML reports.

Tests execute DDL/DML. Use a dedicated database/schema and review [Execution safety](docs/execution-safety.md) before running. This is not an official JDBC certification suite or a performance, migration, business-SQL or proprietary-API test tool.

## Choose a deployment method

| Method | Configuration | Start |
|---|---|---|
| [Docker + bundled PG](docs/deployment.md#bundled-pg) | `.env.example` → `.env`; set password and key | `docker compose up -d --build --wait` |
| [Docker + existing PG](docs/deployment.md#external-pg) | `.env.external.example` → `.env`; set PG URL/user/password and key | `docker compose -f docker-compose.external.yml up -d --build --wait` |
| [Without Docker + existing PG](docs/deployment.md#native) | Server bundle's `config/application.properties` | `sh start.sh` or PowerShell `./start.ps1` |

All three methods serve the same Web UI. The management database is separate from targets configured in the UI. Follow the linked preparation, startup and backup steps.

## Quick start: Docker + bundled PG

Use Docker Engine/Desktop with Compose v2; no host Java, Maven, Node or PostgreSQL installation is needed.

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Edit `.env`: set a strong `POSTGRES_PASSWORD` and put the generated 32-byte Base64 key in `JDBC_TEST_MASTER_KEY`. Preserve that key after saving connection passwords; never commit `.env`.

```sh
docker compose up -d --build --wait
docker compose ps
```

When both services are healthy, open **http://localhost:8080**. Upload and validate your JDBC driver, save/probe a connection, configure its authorized database/schema, then review selection and consent before starting a run. The first build needs npm, Maven and image registry access; the target database must be reachable from the app container.

The application has **no authentication** and is intended for localhost/trusted networks. Web binds to loopback by default; Platform PostgreSQL has no published host port.

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

JDK 21, Maven >=3.9 (Wrapper 3.9.15), Python >=3.9 for CLI tools, and a user-provided target database/driver. Web development also requires Node >=22.12.0 and npm, Platform PostgreSQL, a persistent master key and the built Worker. Follow [local startup](docs/development.md#local-startup).

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
python3 scripts/build_distribution.py
```

Produces `dist/jdbc-test-server-<version>/`, `.tar.gz`, `.zip` and checksums containing Server, Worker, frontend and launchers. Building requires JDK 21, Python and Node/npm; running requires only Java 21 and existing PG. Follow [native deployment](docs/deployment.md#native) to configure and start.

## License

Apache License 2.0.
