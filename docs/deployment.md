# Deployment

English | [简体中文](deployment.zh-CN.md)

Use Docker Engine/Desktop and Compose v2. The first build needs access to npm, Maven Central and image registries; host Java, Maven, Node and PostgreSQL are not required. The image supports the host architecture, including arm64.

## Start

From the repository checkout:

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Edit `.env`: set a strong `POSTGRES_PASSWORD` and put the generated key in `JDBC_TEST_MASTER_KEY`. The key must decode to exactly 32 bytes. Keep it stable across restarts and restores; changing it makes saved passwords unreadable. Never commit `.env` or share configuration output containing secrets. Changing `.env` does not rotate the password in an existing PostgreSQL volume.

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

Open **http://localhost:8080** when both services are healthy. The Server serves the Web UI, REST and SSE from one origin and starts each Worker as a child JVM. Only `jdbc-test` and `postgres` services are needed; production does not run Vite. Health checks cover the platform database, not target databases.

## Configuration

| Variable | Default / purpose |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | `jdbc_test` |
| `POSTGRES_PASSWORD` | Required; no default |
| `JDBC_TEST_MASTER_KEY` | Required persistent Base64 AES-256 key |
| `JDBC_TEST_PORT` | `8080` |
| `JDBC_TEST_BIND_ADDRESS` | `127.0.0.1` |
| `JDBC_TEST_MAX_CONCURRENT_RUNS` | `1` |
| `JDBC_TEST_RUN_TIMEOUT` | `30m` |
| `JAVA_TOOL_OPTIONS` | Optional JVM settings, inherited by Workers |

Compose selects the `docker` profile, stores application files at `/data/jdbc-test`, and uses `/app/worker/jdbc-test-worker.jar`. Platform PostgreSQL is reached through service DNS `postgres`; its port is not published to the host. The Server and Workers run as UID/GID 10001. Custom bind mounts must be writable by that user.

The multi-stage build packages frontend assets and Java artifacts without database E2E tests. The runtime contains Java 21, application artifacts and static assets; uploaded target drivers are supplied at runtime. Image tags receive patch updates; pin reviewed digests if immutable builds are required. `Dockerfile.cli` and the source-only CLI ZIP are separate from the production Compose deployment.

## Target database access and security

Target databases must be reachable from the application container. `localhost` in a JDBC URL means the container itself. On Docker Desktop, use `host.docker.internal` for a host database and configure its listener/firewall. Linux may additionally need a Compose `extra_hosts` entry mapping that name to `host-gateway`. Databases on an attached Docker network can use service DNS names.

The application has **no authentication**. Keep it on localhost or a trusted internal network; external access needs an access-controlled proxy or VPN. Load only trusted drivers: Worker JVM isolation is not a sandbox, and Server/Worker share filesystem, user and network access. Test runs execute DDL/DML and require a dedicated authorized scope; see [Execution safety](execution-safety.md).

## Persistence and updates

`postgres_data` stores platform metadata, encrypted credentials and test results. `jdbc_test_data` stores drivers, workspaces, reports and staging files.

```sh
docker compose restart jdbc-test
docker compose down
docker compose up -d
```

These commands retain named volumes. **`docker compose down -v` permanently deletes both volumes.** Keep the same Compose project name when restarting or restoring. Flyway applies migrations; Hibernate only validates schema. Do not enable schema creation/update.

Back up before upgrading, then check out the desired revision, run `docker compose build` and `docker compose up -d`. Migration rollback may require backup restoration. A PostgreSQL major upgrade requires a supported migration, not just a new image tag.

Shutdown interrupts active runs, clears temporary credentials and terminates Workers. Startup recovery marks unfinished runs `INTERRUPTED`; it does not resume them automatically.

## Backup and recovery

Back up the platform database and application data together, and retain the same master key separately. A database dump alone cannot restore driver JARs or filesystem reports. Stop the application for a simple consistent backup:

```sh
docker compose stop jdbc-test
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > platform.dump
```

While the application is stopped, archive `jdbc_test_data` using your volume backup tool and preserve ownership. Restart with `docker compose start jdbc-test`. Restore matching database, data volume and master key before starting the application. Treat backup files as secrets; there is no built-in automatic backup service.

## Troubleshooting and verification

```sh
docker compose logs --tail=100 jdbc-test
docker compose logs --tail=100 postgres
sh scripts/docker-smoke.sh
```

Check database health, key format, volume permissions, port conflicts and registry connectivity. If driver files are missing, check the data volume and project name before reuploading. An offline target causes a probe/run failure; it does not make platform health fail. Share only sanitized diagnostics.

For production browser verification, provide private driver and target credentials through a protected environment, set `JDBC_TEST_E2E_MODE=production` and optionally `JDBC_TEST_E2E_BASE_URL` (default `http://127.0.0.1:8080`), then run `npm --prefix jdbc-test-web run test:e2e:real`. Use fresh acceptance storage; never wipe an existing installation for a test. See [Development](development.md#testing) for build and regression commands.

To verify persistence after an acceptance run:

```sh
python3 scripts/docker-persistence.py capture /tmp/jdbc-test-checkpoint.json
docker compose restart jdbc-test
# Wait for health UP before verification.
python3 scripts/docker-persistence.py verify /tmp/jdbc-test-checkpoint.json
```

Repeat verification after `docker compose down` followed by `docker compose up -d` and health recovery. The checkpoint verifies captured IDs, artifact/report hashes, results, validation and saved-password probes. A non-Docker run of this check does not establish Docker volume behavior.
