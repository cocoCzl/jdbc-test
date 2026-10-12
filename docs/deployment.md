# Deployment

English | [简体中文](deployment.zh-CN.md)

Choose one method and follow its steps. Do not combine the two Docker entry files.

| Method | Use when | Runtime requirements | Configuration |
|---|---|---|---|
| [Docker + bundled PG](#bundled-pg) | You want a quick start without preparing a management database | Docker Engine/Desktop, Compose v2 | `.env.example` → `.env` |
| [Docker + existing PG](#external-pg) | You have self-hosted or managed PostgreSQL | Docker Engine/Desktop, Compose v2, existing PG | `.env.external.example` → `.env` |
| [Without Docker + existing PG](#native) | You want to run directly on a server | Java 21, existing PG | Bundle's `config/application.properties` |

The **management database** stores connection settings, encrypted credentials, runs and results. The **target database** is the database under test, configured in the Web UI. Keep them in separate databases/accounts. All URLs and credentials in this guide refer to the management database.

The management database uses PostgreSQL; builds and integration verification currently use PostgreSQL 16. Server includes its management PG driver; uploaded drivers are for targets. Flyway creates/migrates application tables automatically and Hibernate only validates the schema. No manual execution of project SQL is needed.

<a id="bundled-pg"></a>
## 1. Docker + bundled PG

Host Java, Maven, Node and PostgreSQL are not required. The first build needs npm, Maven Central and image registries. Images support the host architecture, including arm64.

### Step 1: Configure

From the repository root:

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Edit `.env` and fill in the two required values:

```dotenv
POSTGRES_PASSWORD='replace-with-a-strong-password'
JDBC_TEST_MASTER_KEY=paste-the-generated-key
```

Keep other defaults. `POSTGRES_DB`/`POSTGRES_USER` both default to `jdbc_test`. **No URL is needed**: Compose starts `postgres` and connects to `jdbc:postgresql://postgres:5432/jdbc_test`. Changing `POSTGRES_DB` also changes the database name in the URL. This method uses `POSTGRES_*`, not the external PG `SPRING_DATASOURCE_*` settings.

### Step 2: Start

```sh
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

When `jdbc-test` and `postgres` are healthy and health returns `UP`, open **http://localhost:8080**. No separate Worker/frontend process is needed.

### Daily operations

```sh
docker compose logs --tail=100 jdbc-test
docker compose restart jdbc-test
docker compose stop
docker compose start
```

`postgres_data` stores the management database; `jdbc_test_data` stores drivers, workspaces, reports and staging files. The PG port is not published. Editing `.env` does not change users/passwords in existing PG volumes; a database administrator must apply those changes.

<a id="external-pg"></a>
## 2. Docker + existing PG

This method starts only `jdbc-test`, with no PG container, no `postgres_data` volume and no requirement for `POSTGRES_PASSWORD`.

### Step 1: Prepare the database

Ask your PG administrator for a dedicated database and a login account that owns it. For example, in an administrator's `psql` session:

```sql
CREATE ROLE jdbc_test LOGIN;
\password jdbc_test
CREATE DATABASE jdbc_test OWNER jdbc_test;
```

`\password` prompts for the password. Skip this step if you already have a suitable dedicated database/account. The application account needs permission to create/alter tables and indexes in its schema (default `public`) for Flyway migrations, without superuser privileges. The database must already exist: the application does not create databases. Avoid databases containing unrelated business tables.

Allow application connections through the PG listener, `pg_hba.conf` and firewall. Add JDBC URL SSL parameters required by your managed PG provider.

### Step 2: Configure

From the repository root:

```sh
cp .env.external.example .env
chmod 600 .env
openssl rand -base64 32
```

Back up an existing `.env` before replacing it. Edit these four values:

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://your-pg-host:5432/jdbc_test
SPRING_DATASOURCE_USERNAME=jdbc_test
SPRING_DATASOURCE_PASSWORD='replace-with-the-database-password'
JDBC_TEST_MASTER_KEY=paste-the-generated-key
```

Include the database name in the URL and keep username/password in separate fields. Compose `.env` single quotes preserve literal `$` and `#` in passwords. Escape single quotes according to Compose dotenv rules.

**The address must be reachable from the container**:

| PG location | Host in the URL |
|---|---|
| Remote server or managed PG | Actual IP/DNS, e.g. `pg.example.com` |
| Docker Desktop host | `host.docker.internal`, not `localhost` |
| Same Docker network | PG service name; attach the application to that network |

On Linux, access host PG with `host.docker.internal` and the optional `docker-compose.host-gateway.yml` below.

### Step 3: Start

```sh
docker compose -f docker-compose.external.yml config --quiet
docker compose -f docker-compose.external.yml up -d --build --wait
docker compose -f docker-compose.external.yml ps
curl --fail http://localhost:8080/actuator/health
```

Open **http://localhost:8080** once health returns `UP`. Linux host PG uses:

```sh
docker compose -f docker-compose.external.yml -f docker-compose.host-gateway.yml up -d --build --wait
```

Keep the same `-f` arguments for subsequent operations when using an additional file. Do not add `-f docker-compose.yml`, which would mix in bundled PG settings.

### Daily operations

```sh
docker compose -f docker-compose.external.yml logs --tail=100 jdbc-test
docker compose -f docker-compose.external.yml restart jdbc-test
docker compose -f docker-compose.external.yml stop
docker compose -f docker-compose.external.yml start
```

Application files persist in `jdbc_test_data`; database persistence, backup and availability follow your existing PG operations.

<a id="native"></a>
## 3. Without Docker + existing PG

The bundle contains Server JAR, Worker JAR and built frontend assets. Runtime requires only **Java 21**, without Maven, Node, Python or Vite. Shell launchers support Linux/macOS; a PowerShell launcher supports Windows.

### Step 1: Get the bundle

Choose `jdbc-test-server-<version>.tar.gz` (Linux/macOS) or `.zip` (Windows) from [GitHub Releases](https://github.com/cocoCzl/jdbc-test/releases). Historical releases may only contain source archives; the updated workflow provides Server bundles for future releases. `jdbc-test-<version>.zip` is the CLI source archive, a different artifact.

Before a release is available, build once from the source repository root:

```sh
python3 scripts/build_distribution.py
```

**Only the build machine** needs JDK 21, Python >=3.9, Node >=22.12.0 and npm. Maven Wrapper supplies Maven; the first build needs network access. The command produces `dist/jdbc-test-server-<version>/`, both archives and SHA-256 files. Copy an archive to the runtime host.

The builder refuses to overwrite an existing deployment directory to protect saved configuration/data. To rebuild, use `python3 scripts/build_distribution.py --output dist-new` or another new output directory.

Linux/macOS extraction example (replace the version):

```sh
tar -xzf jdbc-test-server-1.2.1.tar.gz
cd jdbc-test-server-1.2.1
```

On Windows, extract the ZIP and open PowerShell in its directory. Layout:

```text
jdbc-test-server-<version>/
├── start.sh / start.ps1
├── config/application.properties.example
├── server/jdbc-test-server.jar
├── worker/jdbc-test-worker.jar
├── web/
└── docs/
```

### Step 2: Configure

Prepare the dedicated database/account using the [existing PG step](#external-pg). From the deployment directory:

```sh
cp config/application.properties.example config/application.properties
chmod 600 config/application.properties
openssl rand -base64 32
```

Windows PowerShell equivalent:

```powershell
Copy-Item config/application.properties.example config/application.properties
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
[Convert]::ToBase64String($keyBytes)
$rng.Dispose()
```

Edit the first four settings in `config/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://your-pg-host:5432/jdbc_test
spring.datasource.username=jdbc_test
spring.datasource.password=replace-with-the-database-password
jdbc-test.security.master-key=paste-the-generated-key
```

This is a Java `.properties` file: **do not quote values**. `$` and `#` within values are literal; write literal backslashes as `\\`. Prefer `/` in Windows paths and restrict configuration access through file permissions.

Without Docker, `localhost` means the application host and can be used for PG on that host. The launcher loads this file automatically and **does not read the repository `.env`**. Keep the other defaults: localhost port `8080`, `./data`, and bundled Worker/frontend paths.

### Step 3: Start

Linux/macOS:

```sh
java -version
sh start.sh
```

Windows PowerShell:

```powershell
java -version
.\start.ps1
```

If execution policy blocks the script, allow it only for this process with `powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`.

Keep the terminal open and visit **http://localhost:8080**. In another terminal, run `curl --fail http://localhost:8080/actuator/health` (`curl.exe` on Windows). `UP` means application/management database readiness. Server serves pages, REST and SSE from one origin and launches Workers automatically.

### Stop, restart and service operation

Press `Ctrl+C` to stop gracefully; run the launcher again to restart. Configuration and `data/` persist. Launchers change to their own directory, so starting from another working directory uses correct resource paths.

For Linux boot startup, optionally use systemd. Assuming `/opt/jdbc-test` and a service account named `jdbc-test`, create `/etc/systemd/system/jdbc-test.service`:

```ini
[Unit]
Description=JDBC Test Server
After=network-online.target
Wants=network-online.target

[Service]
User=jdbc-test
WorkingDirectory=/opt/jdbc-test
ExecStart=/bin/sh /opt/jdbc-test/start.sh
Restart=on-failure
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
```

Have an administrator create the account and ensure Java 21 is in the service's `PATH`. After placing the bundle at `/opt/jdbc-test`, set default data-directory and completed configuration permissions:

```sh
sudo useradd --system --no-create-home jdbc-test
sudo mkdir -p /opt/jdbc-test/data
sudo chown jdbc-test:jdbc-test /opt/jdbc-test/data
sudo chown root:jdbc-test /opt/jdbc-test/config/application.properties
sudo chmod 640 /opt/jdbc-test/config/application.properties
```

Skip `useradd` for an existing account; substitute paths for custom data directories. Allow that account to read/traverse the remaining bundle files/directories. Then:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now jdbc-test
sudo journalctl -u jdbc-test -n 100
```

Use `sudo systemctl stop jdbc-test`/`sudo systemctl restart jdbc-test` afterward; no open terminal is needed.

## Configuration and keys

| Purpose | Docker `.env` | Native `config/application.properties` |
|---|---|---|
| Existing PG URL | `SPRING_DATASOURCE_URL` | `spring.datasource.url` |
| Existing PG username | `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` |
| Existing PG password | `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` |
| Encryption key (required) | `JDBC_TEST_MASTER_KEY` | `jdbc-test.security.master-key` |
| HTTP port, default `8080` | `JDBC_TEST_PORT` | `server.port` |
| Bind address, default `127.0.0.1` | `JDBC_TEST_BIND_ADDRESS` (host publishing) | `server.address` |
| Concurrent runs, default `1` | `JDBC_TEST_MAX_CONCURRENT_RUNS` | `jdbc-test.execution.max-concurrent-runs` |
| Run timeout, default `30m` | `JDBC_TEST_RUN_TIMEOUT` | `jdbc-test.execution.run-timeout` |
| JVM settings | `JAVA_TOOL_OPTIONS` | Set environment variable `JAVA_TOOL_OPTIONS` before starting |

Bundled PG also uses `POSTGRES_DB`/`POSTGRES_USER` (default `jdbc_test`) and required `POSTGRES_PASSWORD` to initialize PG and supply application datasource settings. Existing PG does not use them.

The master key must decode to exactly **32 bytes**. Generate it once for a new installation, then preserve it across restarts, upgrades, migrations and restores; changing it makes saved passwords unreadable. Do not commit `.env`/actual configuration or share output containing secrets. After editing Docker `.env`, run the matching `up -d` to recreate containers; `restart` does not reload environment variables. Restart native applications after editing configuration.

## Network and access security

Targets must also be reachable from the application environment. Container `localhost` means the container itself; the same host selection rules apply. The application has **no authentication** and binds to localhost by default. Remote access needs a trusted internal network and an access-controlled proxy or VPN; do not expose it directly to the internet.

Load only trusted drivers: Worker JVM isolation is not a sandbox; Server/Worker share filesystem, user and network access. Tests execute DDL/DML and require a dedicated authorized scope; see [Execution safety](execution-safety.md). Health checks cover the management database, not targets.

## Persistence, backup and upgrades

### Backup

Back up the **management database, application files and same master key** together. A database dump alone cannot restore drivers/filesystem reports. Stop the application for a simple consistent backup:

| Method | Stop application | Application files |
|---|---|---|
| Docker + bundled PG | `docker compose stop jdbc-test` | `jdbc_test_data` volume |
| Docker + existing PG | `docker compose -f docker-compose.external.yml stop jdbc-test` | `jdbc_test_data` volume |
| Without Docker + existing PG | `Ctrl+C` or `systemctl stop` | Bundle's `data/` or configured directory |

For bundled PG, from the repository root:

```sh
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > platform.dump
```

For existing PG, use its backup service or have an administrator run this on a machine with PG clients (enter the password when prompted):

```sh
pg_dump -h your-pg-host -p 5432 -U jdbc_test -d jdbc_test -Fc -f platform.dump
```

Archive matching application files, preserve ownership, and store key/configuration securely. Start after backup. Restore the database using PG administration tools, then matching application files and original key, before starting.

### Upgrade and migrate

Docker: back up, check out the new revision and run the matching `up -d --build --wait`. Native: back up, stop the old application, extract the new bundle, copy the original `config/application.properties` and `data/` into it (or retain configuration for a fixed external directory), then start it. Do not run two versions against the same management database/data directory at once.

Flyway migrates on startup; do not enable Hibernate schema creation/update. Migration rollback may require backups. PG major upgrades require database migration. Switching bundled/existing PG does not move data automatically: migrate the database, retain application files and the same master key first.

Docker `stop`, `restart` and `down` retain named volumes; **`docker compose down -v` permanently deletes volumes in that configuration**. Keep the same Compose project name/repository directory, or a fixed `-p` name, to avoid selecting new volumes. Native deployments must retain `data/`. Shutdown interrupts active runs; recovery marks them `INTERRUPTED` without automatically resuming.

## Troubleshooting and verification

If health does not become `UP`, check matching logs, then PG address/access, database existence, credentials, key format, data permissions and port conflicts. Docker Server/Workers run as UID/GID 10001; custom bind mounts must be writable by that user. An offline target affects target probes/tests, not management health.

All three methods can use this read-only check from a source checkout; set `JDBC_TEST_BASE_URL` for a different port:

```sh
sh scripts/docker-smoke.sh
```

It checks health, catalog, SPA routes and assets without executing database tests. Real target acceptance is described in [Development testing](development.md#testing); bundles do not contain test sources.

Bundled PG can additionally verify persistence in a separate acceptance environment (never wipe existing installations for testing):

```sh
python3 scripts/docker-persistence.py capture /tmp/jdbc-test-checkpoint.json
docker compose restart jdbc-test
# Wait for health UP before verification.
python3 scripts/docker-persistence.py verify /tmp/jdbc-test-checkpoint.json
```

Repeat after `down`/`up -d`. Checkpoints verify captured IDs, driver/report hashes, results and saved-password probes. Existing PG/native deployments use matching stop/start steps to confirm data persists. Share only sanitized diagnostics.
