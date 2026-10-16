# Deployment

English | [简体中文](deployment.zh-CN.md)

Editing/debugging source? Use [Development → local source startup](development.md#local-source-startup), with `./jdbc-test dev`. To run the website, choose **one** of the three modes below. Native means **non-Docker deployment bundle**, not IDEA source startup. Every mode uses the unified entry; Windows uses `./jdbc-test.ps1` instead of `./jdbc-test`.

## Where does configuration go?

| Mode | Actual configuration | Platform PG URL |
|---|---|---|
| Source development | Source-root `.env` | `SPRING_DATASOURCE_URL` |
| Docker existing PG | Source-root `.env` | `SPRING_DATASOURCE_URL` |
| Docker bundled PG | Source-root `.env`, generated `POSTGRES_*` | Compose decides `jdbc:postgresql://postgres:5432/${POSTGRES_DB:-jdbc_test}` |
| Non-Docker bundle default | Extracted bundle-root `.env` | `SPRING_DATASOURCE_URL` |
| Legacy Native file alternative | Bundle `config/application.properties` | `spring.datasource.url` |
| Legacy environment alternative | Environment of the terminal/service launching Java | `SPRING_DATASOURCE_URL` |

A **platform database** stores website state/encrypted credentials. A **target database** is separately configured for JDBC tests; it is not needed to start the site. The **database password** authenticates PG; the independent **`JDBC_TEST_MASTER_KEY`** encrypts stored target credentials and must survive restarts/restores.

Source development has exactly three steps: **source-root `.env.example` → `.env` (no overwrite), edit the three datasource fields in the actual `.env`, run `./jdbc-test dev`**. See [Development](development.md#local-source-startup). The same example now includes Docker network notes; duplicate `.env.external.example` is merged, with no template choice. Bundle users copy it in their bundle root. Bundled Docker must not copy this external-PG template; the unified Docker command generates its `POSTGRES_*` mode. One configuration per environment; do not mix modes.

<a id="docker-bundled"></a>
## 1. Docker + bundled PG

**Prepare:** Docker Engine/Desktop + Compose v2, shell/PowerShell, free application port (default 8080), source checkout and network for image/dependency downloads. No host Java/Node/Maven/Python. For a new install, start without `.env`, old project volumes/containers or application data.

**Terminal commands — source root, Linux/macOS:**

```sh
./jdbc-test docker --bundled-pg
# After success open the printed website address (default http://127.0.0.1:8080).
# Later starts reuse the original .env/Key/project:
./jdbc-test docker --bundled-pg
# Stop and keep PG/application volumes and configuration:
./jdbc-test docker --bundled-pg --stop
```

The first invocation builds the setup/application image and checks actual Compose project/path/volumes and local data. For a new empty platform the initializer automatically generates a **32-random-byte hex PG password** and a separate **32-random-byte Base64 Master Key**, saves both in source-root `.env`, and passes the same password to PG and Server. No secret copying. It validates configuration and runs Compose with health/readiness waiting; formal Server startup applies migrations.

Defaults are database/user `jdbc_test`, internal PG host `postgres:5432`. The bundled connection URL comes from `docker-compose.yml`, not a user-supplied external URL. Preserve the real `.env`, project name and volumes for all later starts. Choose a distinct project explicitly with `--project NAME` when creating an independent installation.

<a id="docker-external"></a>
## 2. Docker + existing PG

**URL first:** `jdbc:postgresql://pg.example.com:5432/jdbc_test`. Replace host/port/database with actual values, **include database name, do not embed credentials**. For host PG use `host.docker.internal`; Docker Desktop supplies it, Linux also needs `--host-pg`. Container `localhost` is that container, not your host. The tool does not rewrite URLs.

**Prepare:** Docker/Compose and a dedicated prepared platform PG database/login with migration permissions, reachable **from containers**. No `POSTGRES_PASSWORD`; customer supplies the existing full URL, username and password. Do not use shared QA/target databases.

**Terminal commands — source root, Linux/macOS:**

```sh
./jdbc-test docker --external-pg
# URL/user/hidden-password prompts occur when .env is absent.
# After success open the printed address; future starts:
./jdbc-test docker --external-pg
./jdbc-test docker --external-pg --stop
```

**Interactive input — actual prompts when `.env` is absent:**

```text
Full platform JDBC URL (database name required; no user/password in URL): jdbc:postgresql://pg.example.com:5432/jdbc_test
Existing platform database username: jdbc_test
Existing database password (hidden):
```

Enter the raw URL, no `SPRING_DATASOURCE_URL=` prefix or shell quotes. Password is hidden. After read-only authentication/empty-state checks, it generates only a Master Key and saves source-root `.env`. Customer account/password is never changed. If you already have a private `.env` with these three fields and empty Key, the unified entry checks project state and database/data emptiness, then automatically fills only the Key without an initialization prompt. Existing state with missing Key stops and requires recovery.

For Linux host PG, use **`./jdbc-test docker --external-pg --host-pg`** for both start and stop (append `--stop`). Formal startup applies migrations. The container's network URL can differ from a source dev URL; use separate environments, not automated rewriting.

<a id="native"></a>
## 3. Non-Docker deployment bundle + existing PG

**Prepare:** Java 21 runtime, shell/PowerShell, free port8080, dedicated prepared platform PG database/login, and a complete bundle built from this revision ([build below](#build-bundle)). Runtime requires **no Node/npm/Maven/Python**. No unverified Release download is promised.

**Terminal commands — extracted bundle root, Linux/macOS:**

```sh
umask 077
cp -n .env.example .env
# Edit the actual bundle-root .env: URL, username, existing password.
./jdbc-test start
# After readiness open the printed address. Ctrl+C stops and retains data.
# Next time run ./jdbc-test start with the SAME configuration/Key.
```

Use `jdbc:postgresql://localhost:5432/jdbc_test` if PG is on the runtime host; replace actual host/port/database/user. This is **the extracted bundle's `.env`**, not the source file. The entry protects the local config, authenticates PG read-only, checks new-install state, and automatically saves an independent Key in the same file only for a new empty platform. It then checks artifacts/port, starts Server, waits for health/Catalog, and serves the production page. Formal startup migrates the platform DB. Default application files: bundle **`data/`**. Existing data requires its original Key; stop never deletes config/PG/data.

### Windows commands, grouped alternatives

Run these in the corresponding source/bundle-root **PowerShell** terminal; choose only your mode, not every line:

```powershell
# Source development or build machine:
./jdbc-test.ps1 dev
./jdbc-test.ps1 build
# Docker source root, choose ONE mode:
./jdbc-test.ps1 docker --bundled-pg
./jdbc-test.ps1 docker --bundled-pg --stop
# Or existing PG (Linux-only --host-pg is unnecessary on Docker Desktop):
./jdbc-test.ps1 docker --external-pg
./jdbc-test.ps1 docker --external-pg --stop
# Extracted bundle root: copy only if absent, edit .env, start:
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
./jdbc-test.ps1 start
```

Stop on a failed command; never continue with incomplete configuration. A process-scoped execution-policy invocation can be used if required by your local policy; do not change machine-wide policy. Windows host ACL/process behavior still requires actual Windows validation.

<a id="build-bundle"></a>
## Build machine vs runtime machine

**Terminal command — source root:**

```sh
./jdbc-test build
# Alternative destination when the default output already exists:
./jdbc-test build --output dist-next
```

Build requires **JDK21, Node >=22.12/npm, Python >=3.9** and dependency download access. Maven Wrapper supplies Maven. The entry builds Java/Worker, production web and packages the existing allowlisted distribution automatically. It does not need a PG configuration or copy actual `.env`, private config, data or logs. Output: `dist/jdbc-test-server-<version>/`, `.tar.gz`, `.zip` and checksums. Existing output installations are never overwritten. Transfer/checksum/extract the complete archive on the runtime host; `./jdbc-test start` uses Java21 only.

<a id="configuration-rules"></a>
## Advanced: configuration rules and old entry compatibility

Ordinary users copy/edit `.env` and use only `jdbc-test`. The unified entry reads local `.env` as the sole secret source, without merging inherited datasource/Key or old properties. If .env is absent, existing bundle properties/environment configurations are validated and started directly by this same entry. Historical bundle `start.sh/start.ps1` are **thin forwards only**; init/check wrappers are removed. Do not create another .env beside an existing legacy configuration without deliberately preserving its original Key.

The new `.env` format is **literal UTF-8 assignments**:

- Full-line `#` comments and blank lines are allowed; no inline comments, interpolation, `export` statements or multiline values.
- Everything after `=` in an unquoted value is retained, including leading/trailing spaces, `$`, `#`, `=`, backslashes and `${NAME}`.
- A value starting with `"` uses **JSON string escapes** (Jackson codec); starting with `'` uses one pair of literal single quotes. For quote-heavy passwords use JSON-encoded quoted strings instead of guessing escapes.
- Empty fields are preparation state. NUL/control/tab/newline/Unicode line separators and invalid UTF-8/duplicate names are rejected; values are never silently trimmed.
- Old tool-generated files identified by `# Compose dotenv only;` retain their old Compose encoding and are decoded once. The root Docker entry creates a protected temporary Compose encoding from the literal source and removes it after the operation. **Do not pass new literal `.env` directly to legacy Compose** for special-character passwords.

Never `source`/`eval` `.env` or Java `.properties`; they are not Shell scripts and have different rules. Examples contain empty secrets and are not a ready installation. Configuration and data are owner-protected, Git/context/archive exclusions keep secrets out of releases. The Key is generated only at runtime after empty-state checks pass, never baked into an image. Keep `.env` and data/PG backups private. Do not share actual `.env`, environment dumps, `docker inspect` or expanded Compose config.

### Existing properties/environment compatibility

For existing installations only: retain bundle `config/application.properties` with `spring.datasource.url/username/password` and `jdbc-test.security.master-key`, or inject all four `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `JDBC_TEST_MASTER_KEY` from the original protected service environment. **Run the same `./jdbc-test start`**; do not execute separate init/check/start workflows. Spring preflight validates settings and the port before startup. Properties is Java syntax, not dotenv, and is not sourced by Shell.

With no `.env`, Spring datasource environment overrides file values, and `JDBC_TEST_MASTER_KEY` maps to `jdbc-test.security.master-key` with environment priority; empty template properties do not override a valid env Key. Bash export affects only descendants of that same terminal; PowerShell `$env:` is separate syntax. Manual Maven/IDEA do not load `.env`. New terminals/reboots need original environment reinjected; no Key regeneration. New users stay with the copy/edit `.env` path.

### Advanced Docker configuration

The unified entry supports `--project`, `--port`, `--host-pg`, `--new-install` (accepted for compatibility; unnecessary and does not bypass state checks), and `--stop`.  CLI `--port` overrides an existing file for that invocation; edit the saved port in `.env` for a persistent change (new-install init saves its selected port).

It always selects the requested PG mode, rejects mode/project mismatches and never rewrites the URL. Saved custom networks/Compose storage definitions must be restored/reviewed with their original project/config; the default wrapper cannot infer a moved/renamed historical deployment. `JDBC_TEST_SETUP_NETWORK` selects an existing setup network; ensure actual Server uses the same network via a reviewed Compose override. `JDBC_TEST_SETUP_IMAGE` is an advanced locally built **current-revision** image override; it is not a promised public image or a standard-build verification.

## Recovery, checks and operations

Read-only preflight authenticates PG and checks new-install emptiness; it does not migrate. Formal dev/start/Compose up performs migrations. Startup checks never run target tests. Missing `.env` is not proof of new installation: selected actual Compose project/path/volumes, effective data directory and supplied PG schema are checked. Existing state with missing Key means restore **original config/Key**, not generate a replacement. Secure-random failures stop immediately. Locked, private staged atomic writes prevent partial/concurrent replacements; on a stale lock first establish no initializer/launcher is running before manual recovery. No DROP, volume deletion, password reset or privilege elevation.

Stop via Ctrl+C (dev/bundle) or matching Docker command with `--stop`. It retains all data/config. Back up **platform PG + application files + original Key/config** together, stopping Server for a consistent snapshot. Restore them as a set before starting. For upgrades replace source/bundle while preserving project/storage/config/Key; do not reinitialize or run two Servers against one platform. Historical migrations are unchanged.

Invalid URLs/passwords/Key formats and occupied ports produce actionable errors. A valid 32-byte Key of the wrong value cannot be identified statically: retain the original Key to decrypt existing credentials. Do not fix startup failures by deleting state. UI has no authentication; keep loopback defaults or use controlled proxy/VPN and trusted drivers. See [Execution safety](execution-safety.md).

<a id="validation-limits"></a>
## Validation limits

This revision was tested on macOS/JDK21/Node24 against independent temporary platform PG: copying the example and editing its three fields, one-command source dev with backend/Vite/Catalog/proxy/page readiness, repeated dev with the original Key, Ctrl+C cleanup and retained data, unified build, bundle copy/edit/start, saved-credential decryption after restart and missing-Key recovery refusal. Historical start forwards also passed with existing properties and environment configurations. Both consolidated Docker entries passed real Compose/Spring/PG authentication using this build’s artifacts in an alternate local runtime image, including exact special UTF-8 passwords and matching generated PG/application passwords. Stop/restart retained original configuration/Key/volumes, and missing configuration with existing project state refused regeneration.

**Standard Docker source build was retried and blocked by Docker Hub OAuth token timeout for the Temurin21 JDK base image**; the alternate runtime tests do not count as standard-build success. Windows host ACL/process behavior, this revision’s PowerShell runtime and IDEA UI Run/Debug remain unverified. No target tests, QA or historical-copy acceptance were performed.
