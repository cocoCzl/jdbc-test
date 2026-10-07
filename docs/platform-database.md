# Platform Database

Phase 4 establishes PostgreSQL as the database for `jdbc-test-server` platform
metadata. It is intentionally separate from a target database used by a Worker:
the server's PostgreSQL driver is only used for this platform database, while a
user-selected target driver is loaded inside an isolated Worker JVM.

## Schema

Flyway migration `V1__init_schema.sql` is the single schema authority. It creates:

- `jdbc_driver_package` and `jdbc_driver_artifact` for driver metadata and the
  one-to-many artifact relationship;
- `database_connection_profile` for connection metadata and an encrypted
  password;
- `test_run`, `test_run_module`, and `test_case_result` for run history and
  selection/result snapshots.

All identifiers use PostgreSQL `uuid`. Application timestamps are `Instant` and
are stored as `timestamp with time zone` (UTC). Foreign keys protect references;
historical runs are not cascade-deleted when a driver or connection changes.
The migration adds indexes for run status/creation time, result lookup by run,
status and module, artifact lookup by driver, and connection lookup by default
driver. Run/module and run/scenario pairs are unique.

Hibernate is configured with `spring.jpa.hibernate.ddl-auto=validate`; it never
creates or updates production schema. Flyway runs before Hibernate validation.

## Credentials

`CredentialEncryptionService` uses AES-256-GCM with a fresh random 12-byte nonce
for every encryption. Values are stored as a versioned envelope:

```text
v1:<base64 nonce>:<base64 ciphertext>
```

The 32-byte key is supplied as Base64 through `JDBC_TEST_MASTER_KEY`. There is no
unsafe default. Entities retain ciphertext only; decryption is explicit and the
plaintext is never part of a run snapshot, log, or entity string representation.

`JdbcUrlSanitizer` redacts common credential keys (`password`, `passwd`, `pwd`,
`secret`, `token`, and `access_token`) before URLs enter snapshots or diagnostics.

## Verification

The default Maven build does not start a server or require a database. The
`server-integration` profile runs `PlatformPersistenceIT` with Testcontainers
PostgreSQL to verify Flyway, Hibernate validation, UUID/Instant persistence,
foreign keys, repositories, and encrypted-password storage.

## Connection profiles

Platform PostgreSQL stores connection profile metadata and optional AES-256-GCM `encrypted_password`; it never holds a plaintext password column. `adapter_id`, last connection test time, and last test status are Platform metadata. A target connection is always opened by the separate Worker process with the selected uploaded Driver Package, never by the Platform Database driver.
# Test run persistence

Platform PostgreSQL stores run metadata, selected scenario snapshots and case results. Queue credentials use a separate AES-GCM encrypted table and are removed after Worker handoff and on every terminal/recovery path.
