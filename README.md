# JDBC Test

English | [简体中文](README.zh-CN.md)

Test JDBC driver behavior against real databases. Upload an external driver, configure a connection,
select standard JDBC scenarios, run them in an isolated Worker, and inspect results and reports.
The CLI also supports driver A/B regression comparisons.

Start here: [Usage](docs/usage.md) · [Deployment](docs/deployment.md).

Built-in adapters include PostgreSQL, MySQL, Oracle, SQL Server, GaussDB, KingbaseES and Dameng.
An adapter provides preparation and execution rules; it does not promise that all JDBC tests pass.
See [database configuration and verification limits](docs/usage.md#database-configuration).

## Start the website

For source development, use **JDK 21, Node >=22.12/npm**, the Maven wrapper, and a dedicated
**platform PostgreSQL** database with migration permissions. The platform stores website state;
configure the separate target database later in the UI.

From the source root, copy only if absent, edit the actual `.env` datasource URL/user/password, then run:

```sh
umask 077
cp -n .env.example .env
./jdbc-test dev
```

Open the ready address printed by the command (normally `http://127.0.0.1:5173`).
The launcher creates a Master Key only for a new empty platform; preserve the original `.env`, Key
and data on later starts. Ctrl+C stops the owned frontend/backend processes.
On Windows use `./jdbc-test.ps1 dev` after copying/editing `.env` in PowerShell.
Source startup details are in the advanced guides below.

For deployment choose [Docker with bundled PG](docs/deployment.md#docker-bundled),
[Docker with existing PG](docs/deployment.md#docker-external), or a
[Java 21 deployment bundle](docs/deployment.md#native). Build the bundle with `./jdbc-test build`.

## First test

1. In **Drivers**, upload the driver and required dependency JARs; validate loading.
2. In **Connections**, select the database type, enter the URL and separate credentials, test and save.
3. Configure an explicitly authorized dedicated database/schema. In **New Run**, review the name,
   selected scenarios and write scope, then consent and start.
4. Review progress, individual conclusions, cleanup evidence and the downloaded report.

Tests execute DDL/DML. Use a dedicated authorized scope and trusted drivers. The website has no
built-in authentication; restrict network access. Start with the [usage guide](docs/usage.md).

## Documentation

| Purpose | English | 简体中文 |
|---|---|---|
| Drivers, connections, tasks and results | [Usage](docs/usage.md) | [使用指南](docs/usage.zh-CN.md) |
| Deployment, configuration, backup and upgrades | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |

### Advanced guides

| Purpose | English | 简体中文 |
|---|---|---|
| Architecture, local development and extensions | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| Driver A/B comparison and exit codes | [Driver regression](docs/internal-regression.md) | [驱动回归](docs/internal-regression.zh-CN.md) |
| JDBC contract and classification boundaries | [JDBC reference](docs/jdbc-capability-contracts.md) | [JDBC 判定参考](docs/jdbc-capability-contracts.zh-CN.md) |
| CLI configuration examples | [Examples](examples/README.md) | [示例](examples/README.zh-CN.md) |

Apache License 2.0. Third-party drivers are separate inputs and are not distributed with the project.
