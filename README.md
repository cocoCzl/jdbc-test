# jdbc-test

English | [简体中文](README.zh-CN.md)

A standard JDBC testing platform for uploaded drivers. Platform persistence remains **PostgreSQL**,
separate from target databases.

**Upload driver → configure connection → select standard JDBC tests → execute → inspect report.**
Cases, assertions, database dialects and preparation resources live in source and require rebuild/deploy
when changed. Users do not create JSON/ZIP packages. Private drivers, dependency JARs, validation and
internal driver upgrade regression remain supported. SQL packages and uploaded adapter packages are retired.

JDBC results separate contract conclusions, permitted unsupported behavior, plan requirements and
preparation/platform problems. Historical SQL reports remain read only and excluded from JDBC contract
results and baselines. This is not complete JDBC certification or proof that any JDBC database or full
product is compatible. Tests execute DDL/DML; use dedicated target databases/schemas and review
[Execution safety](docs/execution-safety.md).

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

1. In **Drivers**, upload the driver and dependency JARs, then validate. Registered version, Driver API major/minor and JAR declarations are separate sources; validation confirms loading and metadata reads only.
2. Create a connection. Database type selects its built-in adapter. For a proprietary product choose **Proprietary / compatible database**, then explicitly select a built-in compatibility dialect. This does not establish product compatibility. Configure its driver and dedicated authorized namespace, then probe the connection.
3. In **New run**, select the connection, driver and source-maintained standard JDBC cases; review scope/consent and execute.
4. Inspect progress, individual results, preparation issues, cleanup, frozen driver/adapter/selection snapshots and reports.

Driver validation, connection success and test success are distinct. Incompatible SQL, permissions or namespace requirements remain preparation issues, never passes or automatic driver defects.

## Documentation

| Guide | English | 简体中文 |
|---|---|---|
| Deployment, configuration and backups | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| Architecture, local development, APIs and Worker protocol | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| Authorized scope, cleanup and result accounting | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |
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

Adapter status describes integration maturity, not certification or universal JDBC compatibility. Compatible products can explicitly reuse a built-in dialect; changes to adaptation logic require source changes and rebuild/deploy.

The historical [2026-10-20 acceptance summary](docs/acceptance-2026-10-20.md) records real Web/Server/Worker validation on disposable PostgreSQL **16.15 / pgjdbc 42.7.11**, four MySQL SQL cases on **8.4.11 / Connector/J 8.3.0**, and exactly six PostgreSQL JDBC cases for driver **42.7.2 → 42.7.11**. The custom PostgreSQL package validates the integration mechanism, not a proprietary database. Other products/versions/modes and the complete JDBC matrix were not validated in this session.

Earlier KingbaseES V009R002C016 oracle-mode / driver V008R006B0001 evidence remains in its [validation record](adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json). Oracle 19.3 / ojdbc11 21.13 has preparation gaps and an unresolved LOB dictionary difference; see the [historical follow-up and limits](docs/development.md#oracle-full-follow-up-2026-10-19). Neither record establishes complete product compatibility.

## Development requirements

Source Web development requires JDK 21, Maven Wrapper 3.9.15, Node >=22.12.0/npm and a dedicated platform PG; follow [local source startup](docs/development.md#local-source-startup) for config/Key/Worker preparation. Python >=3.9 and an authorized target database/driver are additionally needed for CLI target tests, not for opening the development website.

## CLI quick start and internal driver regression

Run from the source root; keep configuration and drivers local:

```sh
python3 -B scripts/runner.py adapters
python3 -B scripts/runner.py init --adapter postgresql \
  --url jdbc:postgresql://localhost:5432/test_db --username jdbc_tester \
  --namespace authorized_test_schema --password-env DB_PASSWORD --consent
# Supply DB_PASSWORD through your secret mechanism; --driver-dir selects driver/dependency JARs.
python3 -B scripts/runner.py run configs/config.local.json
```

`run`: 0 means this assessment passed, 1 means test/assessment failure, 2 means invalid input or failure before assessment starts. Preparation/execution issues in a retained assessment still fail that assessment. For A/B use `baseline create` then `compare --scope internal`; see
[Internal regression](docs/internal-regression.md). Comparison 0 means no new regressions, with existing
failures visible; 1 means new regressions; 2 means incomparable/insufficient evidence. These commands'
0 codes have different meanings. Historical SQL reports cannot become JDBC baselines.

See [examples](examples/README.md) for compatible dialect selection, local/multi-JAR drivers and
connection properties. `init-custom`, `pack-adapter`, `sql-validate`, `sql-run` and external adapter-path
inputs are retired. Do not commit configuration, keys, credentials or private drivers. `report.json`
is authoritative; review diagnostics before sharing.

## Coverage and selection

The default CLI `test_profile=full` selects the JDBC 4.3 baseline: connections/transactions, statements, batches, generated keys, ResultSet, types, metadata, LOB, RowSet, SQLXML, savepoints and CallableStatement. Use `core` for strict cross-database scenarios:

```json
{"test_profile": "core", "test_filter": {"timeout_ms": 60000}}
```

New Run defaults to all applicable tests, with module and individual exclusions. Static false declarations no longer disable standard verification. Missing Providers/SQL preparation are prerequisites; permitted unsupported requires an observed response allowed by the specific API. CORE explicitly retains 153 legacy IDs; FULL covers 270 implemented standard cases, with no real vendor extensions yet, rather than every JDBC API. View stable IDs with `python3 scripts/runner.py coverage --profile full`; narrow selection with `test_filter.include_tests` / `exclude_tests`. Shared-connection stress (`concurrency.enabled=true`) is rejected.


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
