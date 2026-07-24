# JDBC Driver Compatibility Test Framework

English | [中文](README.md)

An open-source JDBC 4.3 compatibility and regression framework for database vendors and JDBC driver maintainers. It connects to a user-managed database, runs a versioned suite inside a dedicated test namespace, and produces sanitized, comparable JSON, Markdown, and HTML assessments.

This project is not an official JDBC certification suite. It does not test business SQL, performance, migrations, or vendor-proprietary APIs.

## Adapters

- Official with real validation evidence: PostgreSQL, MySQL, Oracle
- Implemented candidates awaiting real instances: GaussDB, SQL Server
- New databases can use a local declarative adapter without Java-core changes

## Requirements

- JDK 21
- Maven 3.8+
- Python 3.9+; core commands use only the standard library
- A user-provided target database and a pinned JDBC driver

## Quick start

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

## License

Apache License 2.0.
