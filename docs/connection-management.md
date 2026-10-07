# Connection Management

## Connection profile

A Database Connection Profile stores `name`, open-ended `databaseType`, explicit `adapterId`, JDBC URL, username, optional encrypted password, and a reference to one validated default Driver Package. It never copies driver classes, artifact paths, or JAR bytes.

`databaseType` describes the target product and remains a string so private vendor products do not require a Server enum change. `adapterId` selects the JDBC compatibility SQL and capabilities. Driver and adapter are independent: a vendor driver can use the PostgreSQL adapter where that compatibility choice is appropriate.

`GET /api/adapters` exposes the static built-in catalog. Its metadata comes from existing `adapters/*/adapter.json` manifests, packaged as read-only Core resources; it does not execute a suite or expose SQL assets.

## Credentials and URLs

Passwords are write-only. With `savePassword: true`, a nonblank password is encrypted using AES-256-GCM and stored in `encrypted_password`. With `savePassword: false`, no password is persisted. Responses expose only `hasSavedPassword`.

`PATCH` replaces a password only with `password` plus `savePassword: true`. `clearSavedPassword: true` deletes it. Supplying both is a validation error. A temporary password in a saved test request overrides the saved credential only for that request.

Persisted JDBC URLs reject `password`, `passwd`, `pwd`, `secret`, `token`, and `access_token` parameters. The request fails with `CONNECTION_URL_CONTAINS_CREDENTIALS`; it is never silently rewritten.

## REST API

| Method | Endpoint | Meaning |
|---|---|---|
| GET | `/api/adapters` | List built-in adapter metadata |
| GET | `/api/connections` | Paginated profiles; supported sorts: `name`, `databaseType`, `lastConnectionTestAt`, `createdAt` |
| POST | `/api/connections` | Create profile: `201 Created` and Location |
| GET | `/api/connections/{id}` | Read a profile |
| PATCH | `/api/connections/{id}` | Update metadata and explicit password actions |
| DELETE | `/api/connections/{id}` | Delete unused profile: `204 No Content` |
| POST | `/api/connections/test` | Test an unsaved draft |
| POST | `/api/connections/{id}/test` | Test a saved profile |

The list response has `items`, `page`, `size`, `totalElements`, and `totalPages`. Default size is 20 and maximum is 100. The shared error response uses codes including `CONNECTION_NOT_FOUND`, `CONNECTION_IN_USE`, `CONNECTION_PASSWORD_REQUIRED`, `CONNECTION_TIMEOUT`, `CONNECTION_FAILED`, `CONNECTION_URL_CONTAINS_CREDENTIALS`, `ADAPTER_NOT_FOUND`, `DRIVER_NOT_FOUND`, `DRIVER_NOT_VALIDATED`, and `DRIVER_DOES_NOT_ACCEPT_URL`.

## Connection probe and security boundary

Draft and saved tests start the configured Worker fat JAR in `--probe-connection` mode. The Server resolves Driver Storage internally and sends external artifact paths, JDBC URL, username, and short-lived plaintext password as JSON on stdin. Passwords never appear in argv, stdout, REST responses, or persisted probe diagnostics.

The Worker creates a `URLClassLoader` with the platform class loader parent, loads the selected `java.sql.Driver`, calls `Driver.connect`, reads `DatabaseMetaData` best-effort, closes the connection and class loader, then exits. A null connect result becomes `DRIVER_DOES_NOT_ACCEPT_URL`. It executes no SQL, DDL, DML, namespace action, or Test Run.

`jdbc-test.worker.connection-probe-timeout` defaults to 30 seconds. On timeout the Server terminates the Worker, then forcibly terminates it after `termination-grace-period`. Database reads and last-test writes use separate short transactions. Saved probes update `last_connection_test_at` and `last_connection_test_status`; drafts persist nothing. TestRun references prevent deletion with `409 CONNECTION_IN_USE`.

The Server never loads a user target Driver or calls target `DriverManager`/`Driver.connect`. Its PostgreSQL dependency is restricted to Platform Database persistence. User artifacts stay off the Server classpath and load only inside Worker.
