# JDBC Driver Compatibility Test Framework

English | [中文](README.md)

An open-source JDBC 4.3 compatibility and regression framework for database vendors and JDBC driver maintainers. It connects to a user-managed database, runs a versioned suite inside a dedicated test namespace, and produces sanitized, comparable JSON, Markdown, and HTML assessments.

This project is not an official JDBC certification suite. It does not test business SQL, performance, migrations, or vendor-proprietary APIs.

## Quick Start — Self-hosted Docker

Use Docker Engine/Desktop with Compose v2; host Java, Maven, Node and PostgreSQL
are not required for this deployment. From the repository checkout:

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Edit `.env`: set a strong unique `POSTGRES_PASSWORD` and put the generated
32-byte Base64 key in `JDBC_TEST_MASTER_KEY`. There are no usable default secrets.
Keep the key unchanged after saving credentials and never commit `.env`.

```sh
docker compose up -d --build
docker compose ps
```

When both services are healthy, open **http://localhost:8080**. React, REST and
SSE share the Spring Boot origin. The host Web port binds to loopback by default;
Platform PostgreSQL does not publish a host port. Image/build registry access
is required. This unauthenticated MVP is for localhost/trusted networks, not
direct public exposure. Use a dedicated target database: tests execute DDL/DML.
See [Deployment](docs/deployment.md) and the completed
[Phase 9 verification](docs/phase9-verification.md) for the production acceptance evidence.

## Adapters

- Official with real validation evidence: PostgreSQL, MySQL, Oracle
- Implemented candidates awaiting real instances: GaussDB, SQL Server
- New databases can use a local declarative adapter without Java-core changes

## Requirements

- JDK 21
- Maven >=3.9 (Wrapper 3.9.15)
- Python 3.9+; core commands use only the standard library
- A user-provided target database and a pinned JDBC driver

## Web development

`jdbc-test-web/` is an independent React/strict TypeScript/Vite npm project, not
a Maven module. Development requires Node >=22.12.0 and npm. First prepare a
Platform PostgreSQL database, persistent 32-byte Base64 master key, writable data
directory and built Worker JAR as described in [Web UI](docs/web-ui.md) and
[Platform Database](docs/platform-database.md); the Server is not zero-config.

```sh
./mvnw -DskipTests install
# Set the documented datasource, master-key, data-directory and Worker variables.
./mvnw -pl jdbc-test-server spring-boot:run
```

In another terminal:

```sh
cd jdbc-test-web
npm install
npm run dev
```

Open `http://localhost:5173`; Vite proxies `/api` to the Server on port 8080.
Production Compose builds the same frontend and serves it from Spring Boot on
port 8080, without Vite. The ordinary Maven lifecycle does not run npm.

## CLI Quick Start

```bash
python3 scripts/runner.py init \
  --adapter postgresql \
  --url 'jdbc:postgresql://localhost:5432/test' \
  --username tester \
  --password-env DB_PASSWORD \
  --consent

export DB_PASSWORD='your-password'
python3 scripts/runner.py run configs/config.local.json
```

Compare with an approved baseline:

```bash
python3 scripts/runner.py compare \
  --baseline baseline/report.json \
  --current report/postgresql_YYYY-MM-DD_HH-MM-SS/report.json
```

Run `python3 scripts/runner.py adapters` to list bundled adapters. Never store a plaintext password in the generated local config or commit that config. Oracle requires a dedicated test account/schema. Results are written to `report.json`, `report.md`, and `report.html`; keep `diagnostics.log` local.

## Usage examples

See [examples/README.en.md](examples/README.en.md) for complete copyable examples covering:

- Bundled PostgreSQL, MySQL, and Oracle adapters
- Private databases compatible with Oracle, MySQL, or PostgreSQL dialects
- Hikari pooling and direct DriverManager connections
- JDBC properties, environment-backed properties, and URL parameters
- One driver JAR or a directory containing multiple dependency JARs

## Local adapters

A local adapter contains `adapter.json` plus database-specific SQL assets and can be selected with `runner.py init --adapter /path/to/adapter` without changing Java core code. See `examples/adapters/postgresql-local/adapter.json` for an example.

Adapters must declare product and driver identity/ranges, capabilities, test assets, namespace lifecycle, least privileges, validated combinations, and known differences. Local adapter results remain local/experimental and cannot directly become formal baselines.

To use a local JDBC driver JAR, replace `db.driver` in the generated config with:

```json
{"kind": "local", "path": "/absolute/path/to/driver.jar"}
```

Do not copy or commit driver JARs into this repository.

### Private databases and drivers

For a database compatible with the Oracle, MySQL, or PostgreSQL dialect, `init-custom` creates a local-only adapter that reuses the corresponding SQL assets. Private drivers default to DriverManager but may use Hikari, and arbitrary vendor properties or URL parameters are supported. See the [private database examples](examples/README.en.md#private-databases-and-drivers) for complete commands.

## Safety

- Passwords are read from environment variables and local configs are ignored by Git.
- Database changes require explicit `destructive_consent=true`.
- Shareable reports redact connection and raw diagnostic details.
- Full SQL, exceptions, and stack traces remain only in local `diagnostics.log`.

## Coverage and selection

The default `test_profile=full` runs the complete JDBC 4.3 baseline: connections and transactions, Statement/PreparedStatement, batch-failure semantics, generated keys, ResultSet lifecycle and NULL semantics, type mappings, metadata, LOBs, RowSets, SQLXML, savepoints, and CallableStatement. Use `core` for strict cross-database scenarios that require no capability declaration:

```json
{"test_profile": "core", "test_filter": {"timeout_ms": 60000}}
```

In `full`, optional APIs such as Array, SQLXML, NClob, parameter metadata, and request boundaries are controlled by the adapter capability declaration. A declared `false` becomes `known_unsupported`; a declared `true` that fails is a compatibility failure. Run `python3 scripts/runner.py coverage --profile full` for stable scenario IDs. `test_filter.include_tests` and `exclude_tests` further narrow a run, and reports include summaries by JDBC area and capability.

This project tests standard JDBC driver contracts, not vendor-specific SQL certification, performance testing, or shared-connection concurrency stress. `concurrency.enabled=true` is rejected so no unexecuted test is represented as coverage.

## License

Apache License 2.0.
