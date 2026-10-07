# Driver Management

A Driver Package is an immutable binary set used by a future JDBC test run. It contains one or more JDBC JAR artifacts, so private vendor packages such as `vendor-jdbc.jar`, `vendor-common.jar`, and `vendor-security.jar` upload as one resource. Maven, local Maven repositories, and the Server application classpath are not involved.

## Storage and integrity

`jdbc-test.storage.data-directory` defaults to `./data`. Uploads stream into `<data-dir>/staging/<random-id>/`; after ZIP/JAR validation they move into:

```text
<data-dir>/drivers/<driver-id>/<artifact-id>.jar
```

The platform-generated IDs form physical file names. The user supplied file name is display metadata only and never resolves a filesystem path. Directories are owner-only on POSIX filesystems when supported. Each artifact records its original file name, byte size, and streamed SHA-256. The package fingerprint is SHA-256 of lexically sorted artifact digests joined with a newline; upload order therefore has no effect. Identical binary packages are rejected with `DRIVER_PACKAGE_ALREADY_EXISTS`.

Artifacts cannot be replaced. Upload a new Driver Package to change binaries. The defaults are 20 artifacts, 100 MiB per artifact, and 250 MiB per package; all are configurable under `jdbc-test.storage`, and Spring multipart limits match the default maximums.

## Discovery and validation

The Server reads only JAR ZIP metadata. It scans `META-INF/services/java.sql.Driver`, ignores comments, blank lines and duplicate classes, and selects the sole discovered driver when `driverClass` is omitted. Multiple candidates require an explicit class. Packages without SPI metadata also require the user to provide a class.

`POST /api/drivers/{id}/validate` starts the configured Worker executable (`jdbc-test.worker.executable`) in `--validate-driver` mode. The Worker creates a fresh URLClassLoader, initializes and instantiates the requested class, checks that it is a `java.sql.Driver`, and returns JDBC major/minor version and `jdbcCompliant`. It does not connect to a target database. Validation times out after 30 seconds by default and terminates the Worker. Editing `driverClass` returns the package to `PENDING_VALIDATION`.

Worker protocol output retains the original stdout stream. During user Driver loading/execution, `System.out` is redirected to a bounded diagnostic sink, so third party prints cannot corrupt protocol NDJSON.

## REST API

| Method | Resource | Success |
|---|---|---:|
| `POST` | `/api/drivers` multipart `metadata` JSON plus `files` | 201 + Location |
| `GET` | `/api/drivers` | 200 paginated items |
| `GET` | `/api/drivers/{id}` | 200 |
| `PATCH` | `/api/drivers/{id}` | 200 |
| `POST` | `/api/drivers/{id}/validate` | 200 |
| `DELETE` | `/api/drivers/{id}` | 204 |

List parameters are `page`, `size` (maximum 100), and controlled `sort`, whose fields are `name`, `vendor`, `version`, `status`, and `createdAt`. Errors use RFC Problem Details with a stable `code` property. Driver records expose artifact ID, display filename, size, and SHA-256; storage keys and physical paths are never returned.

A Driver Package in use by a connection default driver or a historical test run cannot be deleted and returns `409 DRIVER_IN_USE`. Database metadata is deleted before storage cleanup. A failed cleanup may leave an orphan directory, which is safer than retaining a metadata record whose binary has been removed.
