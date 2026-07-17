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

See the [English quick start](docs/QUICKSTART.en.md) for details.

## Safety

- Passwords are read from environment variables and local configs are ignored by Git.
- Database changes require explicit `destructive_consent=true`.
- Shareable reports redact connection and raw diagnostic details.
- Full SQL, exceptions, and stack traces remain only in local `diagnostics.log`.

## License

Apache License 2.0.
