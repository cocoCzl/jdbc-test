# Production Deployment — Phase 9

## Architecture and prerequisites

Docker Engine/Desktop with Compose v2 and outbound build access to npm, Maven
Central and image registries are required. No host JDK/Maven/Node/PostgreSQL is
required for deployment. Default architecture follows the host, including
macOS arm64; no forced amd64 emulation.

The Java build stage uses a BuildKit Maven cache so subsequent builds and retries
reuse downloaded dependencies. This cache is build-only; the final runtime image
does not contain Maven or its repository. A first build still needs registry,
Maven Central and npm connectivity.

```text
Browser :8080 → jdbc-test (Spring Boot + /app/web)
                      ├→ Platform PostgreSQL (postgres:5432)
                      └→ /app/worker/jdbc-test-worker.jar → child JVM
                                                       → uploaded Driver → Target DB
```

Only `jdbc-test` and `postgres` are deployed. No Nginx, Vite, broker or cache.
`jdbc-test-web` stays an independent Node project, not a Maven module.

## Configuration and secrets

Copy `.env.example` to `.env`, restrict permissions (`chmod 600 .env`) and edit
it in an editor. Set a strong unique `POSTGRES_PASSWORD`. Generate the key with:

```sh
openssl rand -base64 32
```

Put the result in `JDBC_TEST_MASTER_KEY`; it must decode to exactly 32 bytes.
Empty secrets are rejected by Compose. Do not put secrets in shell command
arguments, source control, screenshots or shared `docker compose config` output.
The key is never generated on startup or stored in the DB/data volume. Keep it
securely outside the runtime data backup. Changing it makes previously saved
connection credentials unreadable. Existing PostgreSQL volumes retain the DB
password used at initialization; editing `.env` is not DB password rotation.

| Variable | Purpose/default |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | `jdbc_test` |
| `POSTGRES_PASSWORD` | Required, no default |
| `JDBC_TEST_MASTER_KEY` | Required persistent Base64 AES-256 key |
| `JDBC_TEST_PORT` | Host Web port, default 8080 |
| `JDBC_TEST_BIND_ADDRESS` | Host bind, default 127.0.0.1 |
| `JDBC_TEST_MAX_CONCURRENT_RUNS` | Default 1 |
| `JDBC_TEST_RUN_TIMEOUT` | Default 30m |
| `JAVA_TOOL_OPTIONS` | Optional deployer JVM settings; inherited by Worker |

Container config selects only the `docker` profile, listens on `0.0.0.0:8080`,
uses data `/data/jdbc-test` and Worker `/app/worker/jdbc-test-worker.jar`.
Datasource URL uses service DNS `postgres`, not localhost. Development retains
`application.yml` and the documented Vite proxy with no CORS wildcard.

## Build, startup and health

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

Open http://localhost:8080 after both services are healthy. PostgreSQL's
`pg_isready` gates startup; Flyway connection retries and the restart policy
also cover transient availability. Explicit Flyway wiring migrates before
Hibernate `ddl-auto=validate`; startup recovery interrupts unfinished runs
before application readiness. Health exposes only status, includes Platform
DB/readiness and never probes target databases. Only the health actuator
endpoint is exposed; no env/config/heapdump endpoint or credential details.

The multi-stage Dockerfile uses Node `24.11.1-alpine` (the accepted Phase 8
Node baseline; only the frontend build stage uses musl), Temurin
`21-jdk-noble` for the Maven Wrapper build, and Red Hat's official
`registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24` at runtime. This
maintained OpenJDK 21 JRE image supports arm64/amd64 and includes standard Java,
curl and user-management tools without adding a package-install layer. It was
selected after repeated Docker Hub JRE timeouts; it is not an unofficial mirror
or a JDK substituted for the final JRE. PostgreSQL remains the accepted major
`16-alpine`; no automatic
major upgrade. Tags bound majors/OS lines rather than `latest`; these tags
receive patch updates, not bit-for-bit immutable builds. Pin reviewed image
digests in deployments requiring that stronger guarantee. `npm ci` uses the
committed lockfile. Image builds package Java without running database E2E;
regressions run separately. No build sources/Maven/Node/node_modules/.m2 or
uploaded target drivers are copied into the runtime image.

The existing CLI image is retained as `Dockerfile.cli`. It pins the Java 21
Noble base and uses the repository's Maven Wrapper instead of installing a
distro Maven/JDK pair. The source-only CLI archive remains separate from the
production Compose build; use the repository checkout for production deployment.
No release or image registry publication is performed by this phase.

## Static routing and cache

Vite `dist` is generated only in the build stage and copied to `/app/web`;
no generated assets modify Java source/resources or enter Git. A conditional
Spring MVC configuration serves that directory. Root forwards only to
`index.html`. GET/HEAD extensionless navigations accepting `text/html` get the
SPA shell; React handles unknown client routes. `/api`, `/actuator`, `/error`
and their subpaths never fallback. Missing `.js/.css/...` remain 404.
Hashed `/assets/**` use one-year immutable caching; index/client routes use
revalidation (`no-cache`). Production API base is relative, not a built-in host.
SSE `/api/runs/{id}/events` stays native Spring MVC `text/event-stream`; no
extra proxy, compression or buffering layer is introduced.

## Runtime and shutdown

UID/GID 10001 runs Server and child Workers. `/app` jars/assets are root-owned
and not writable by that user; named data volume directories are writable by
10001, without chmod 777. For custom bind mounts, provision the owner/permissions
yourself. Runtime includes a standard Java executable; subprocesses use
`${java.home}/bin/java`, never a development `target` path.

Exec-form Java entrypoint and Compose `init: true` pass signals and reap child
processes. Compose allows 60 seconds to stop. On context close the dispatcher
stops accepting work, locks unfinished rows, records `INTERRUPTED` with
`SERVER_SHUTDOWN`, deletes temporary credentials, closes SSE and terminates
Workers (force kill after grace when needed), waiting for task cleanup before
DB teardown. Abrupt loss leaves nonterminal rows for startup recovery:
`INTERRUPTED` / `SERVER_RESTART`. Runs do not auto-resume.

## Persistence, restart and updates

Named volume `postgres_data` contains Platform metadata, saved encrypted
credentials, Run history and results. `jdbc_test_data` contains `drivers/`,
`runs/` (including reports/diagnostics) and `staging/`.

```sh
docker compose restart jdbc-test
docker compose down
docker compose up -d
```

These retain named volumes. **`docker compose down -v` permanently deletes
both volumes and is only for intentional fresh-install testing/removal.**
Project names determine volume names; keep the same checkout/project name or
explicit `-p` when restarting/restoring. Flyway V1–V4 runs once on a fresh DB,
then validates existing migrations on restart. Never enable ddl-auto create/update.

Before updating, back up. Then fetch the desired source revision, rebuild and
recreate (`git pull`, `docker compose build`, `docker compose up -d`). New
Flyway migrations apply automatically; rollback after migrations may require
restoring a backup. PostgreSQL major-version upgrades require a deliberate
supported migration, not just changing the image tag on an existing volume.

## Target database networking

Platform PostgreSQL has no host port; only Compose peers can reach it. Target
DBs must be reachable from the app container over LAN/VPN or an attached Docker
network. `localhost` inside JDBC URLs refers to the app container, not the host.
On Docker Desktop macOS/Windows, use `host.docker.internal` for host DBs, with
host DB listening/firewall configured appropriately. Linux may require an
explicit `extra_hosts: ["host.docker.internal:host-gateway"]` override and host
DB interface/firewall changes; do not assume universal Desktop behavior.
Another Docker DB can be attached to the Compose network and addressed by its
service/container DNS name. Do not use host networking or fixed container IPs.

## Logs and troubleshooting

```sh
docker compose logs --tail=100 jdbc-test
docker compose logs --tail=100 postgres
sh scripts/docker-smoke.sh
```

Check DB health, required key format, volume permissions and image build
registry connectivity first. For port conflicts, change `JDBC_TEST_PORT`.
Driver errors should use bounded sanitized diagnostics, not raw third-party
stderr in platform logs. Do not share Docker inspect/config/env output: it
contains deployment secrets. An offline target is a business probe/run failure,
not unhealthy Platform health. If Driver files disappear while metadata remains,
check the data volume/project name rather than reuploading binaries blindly.

## Backup and disaster recovery

Back up **both** Platform PostgreSQL and `jdbc_test_data` consistently, plus
retain the same master key separately. DB alone cannot restore Driver binaries
or filesystem reports. Stop the app for a simple consistent backup; create a
Platform dump without putting the password in host command arguments:

```sh
docker compose stop jdbc-test
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > platform.dump
```

Use your standard Docker named-volume backup tool to archive `jdbc_test_data`
while the app remains stopped, preserving UID/GID ownership. Restart afterward.
Restore matching DB + data volumes + master key before starting the app. Protect
backup files as secrets; missing key prevents saved-credential decryption.
This phase does not add an automatic backup/updater system.

## Security boundary

This single-user MVP has **no authentication**. Use localhost or trusted
internal networks; public access requires a trusted access-controlled reverse
proxy/VPN supplied by the deployer. No partial auth/Spring Security is added.
Only Web is published, loopback by default. Uploaded Drivers load only inside
independent Worker JVMs; the Server Platform PostgreSQL dependency is not a
target Driver. The container is **not a complete sandbox**: Worker and Server
share Linux user, filesystem and network namespaces. Trusted drivers only;
per-run containers/network/resource policies are future work. Docker daemon
administrators can read environment variables, volumes and processes; `.env`
is not a hardware-backed secret store. Never place the master key in reports,
DB, logs or a data-volume configuration file.

## Verification commands

```sh
./mvnw clean verify
./mvnw -Pserver-integration verify
./mvnw -Pworker-e2e verify
cd jdbc-test-web
npm ci
npm run lint
npm run test
npm run build
```

The existing real Playwright workflow is reused with `JDBC_TEST_E2E_MODE=production`
and optional `JDBC_TEST_E2E_BASE_URL` (default http://127.0.0.1:8080). Supply
private E2E driver path, target URL/user/password through a protected environment
file, then `npm run test:e2e:real`; no Vite starts in production mode. Real tests
keep tracing/HAR off, observe actual SSE, assert no uncaught browser errors,
and check Statement23/Connection17/results/report/history/direct refresh.
Use a fresh acceptance project/volumes (fingerprint uniqueness rejects repeated
uploads of the same binary); never wipe an existing user's volumes for a test.

Immediately after the two real browser runs, capture a secret-free checkpoint:

```sh
python3 scripts/docker-persistence.py capture /tmp/jdbc-test-checkpoint.json
docker compose restart jdbc-test
# Wait for app health UP, then:
python3 scripts/docker-persistence.py verify /tmp/jdbc-test-checkpoint.json
docker compose down
docker compose up -d
# Wait for app health UP, then repeat verify.
```

The verification addresses only captured IDs, checks unchanged artifact/report
hashes and results, validates the uploaded Driver again and probes using the
saved password (no plaintext input). It may also be used for an explicitly
labelled non-Docker supplemental acceptance; that does not prove Docker volume
or runtime behavior. Actual Phase 9 status is in `phase9-verification.md`.
