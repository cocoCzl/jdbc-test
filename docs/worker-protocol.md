# Worker Protocol (Phase 3)

`jdbc-test-worker` is a single-run JVM executable. It reads one JSON request from
stdin and writes newline-delimited JSON (NDJSON) events to stdout. Passwords are
never command-line arguments.

## Start

```text
java -jar jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar < request.json
```

For an artifact-only catalog smoke check:

```text
java -jar jdbc-test-worker-1.2.1-SNAPSHOT-all.jar --catalog-smoke
```

For Driver Package verification without a database connection:

```text
java -jar jdbc-test-worker-1.2.1-SNAPSHOT-all.jar --validate-driver < validation.json
```

```json
{"driverClass":"org.postgresql.Driver","artifacts":["/private/driver/postgresql.jar"]}
```

It emits `DRIVER_VALIDATED` with `driverClass`, `majorVersion`, `minorVersion`, and `jdbcCompliant`, or `DRIVER_VALIDATION_FAILED`. User Driver `System.out` is redirected to a bounded sink while its code runs; protocol events retain the Worker's original stdout stream and remain valid NDJSON.

The smoke command is independent of the current working directory and reports
the packaged catalog count.

## Request

```json
{
  "runId": "run-1",
  "driver": {"driverClass": "org.postgresql.Driver", "artifacts": ["/drivers/postgresql.jar"]},
  "connection": {"jdbcUrl": "jdbc:postgresql://db/test", "username": "tester", "password": "provided-on-stdin", "properties": {}, "adapterId": "postgresql"},
  "selection": {"profile": "CORE", "modules": ["connection"], "includeScenarios": [], "excludeScenarios": []},
  "workspace": "/var/lib/jdbc-test/runs/run-1",
  "timeoutMs": 60000
}
```

The worker validates the request, creates only `temp/`, `report/`, and runtime
files below the supplied workspace, and deletes `temp/config.json` on exit.

Run requests must supply the target `connection.adapterId` (for example `postgresql`) so the suite resolves the matching packaged DDL/DML assets. Probe and validation requests do not execute suite SQL and do not need this field. The Server supplies it from the Connection snapshot.

## stdout events

Every stdout line is one JSON object. Ordinary logs never go to stdout. Events
include `RUN_STARTED`, `CASE_STARTED`, `CASE_FINISHED`, `RUN_FINISHED`, and
`RUN_FAILED`. Case events contain `runId`, `scenarioId`, `moduleId`,
`displayName`, `status`, `durationMs`, and `timestamp`; statuses are `PASSED`,
`FAILED`, `SKIPPED`, or `ABORTED`.

## stderr and credentials

stderr is for diagnostics only and applies credential sanitization to exception
messages. Passwords are not written to stdout, stderr, report JSON, or argv.
The temporary compatibility config is owner-readable/writable where the host
filesystem supports POSIX permissions and is removed in a `finally` block.

## Driver isolation

Each run creates a new `URLClassLoader` from the requested artifact paths, with
the platform class loader as parent. The named class must instantiate as
`java.sql.Driver`; it is used directly via `Driver.connect`, never registered
in the global `DriverManager`. The loader is closed after the run.

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | Run completed |
| 2 | Invalid JSON/request/selection |
| 3 | Driver artifact/class/connection setup failure |
| 4 | Test execution or timeout failure |
| 5 | Catalog, workspace, report, or internal failure |

The report is written to `<workspace>/report/report.json`. It retains stable
`scenarioId` keys and v1-compatible summary fields; the existing Python report
pipeline remains unchanged.

The optional `worker-e2e` Maven profile runs `WorkerPostgresIT` with a
Testcontainers PostgreSQL and passes the PostgreSQL JDBC JAR as an external
artifact. It is deliberately excluded from the default build because it needs
Docker.

## Connection probe mode

`--probe-connection` accepts one stdin JSON request:

```json
{"driver":{"driverClass":"org.postgresql.Driver","artifacts":["/private/driver/postgresql.jar"]},"connection":{"jdbcUrl":"jdbc:postgresql://db/test","username":"tester","password":"provided-on-stdin"}}
```

It loads the external driver, calls `Driver.connect`, returns a `CONNECTION_PROBED` NDJSON event with best-effort database and driver metadata, then closes the connection and class loader. A null result becomes `CONNECTION_PROBE_FAILED` with `DRIVER_DOES_NOT_ACCEPT_URL`. The mode executes no suite SQL. Driver `System.out` remains redirected to the bounded diagnostics sink, so stdout is protocol-only NDJSON.
# Worker protocol additions

`--describe-catalog` is a read-only mode. It emits one `CATALOG_DESCRIBED` NDJSON event containing profiles, modules, scenarios and counts. It is used only to obtain cached Server catalog metadata; it never executes a suite.

Run stdout remains NDJSON only. The Server accepts `RUN_STARTED`, `MODULE_STARTED`, `CASE_STARTED`, `CASE_FINISHED`, `MODULE_FINISHED`, `RUN_FINISHED`, and `RUN_FAILED`; malformed lines fail the run with `WORKER_PROTOCOL_ERROR`.
