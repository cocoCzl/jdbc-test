# JDBC regression testing examples

English | [中文](README.md)

All addresses, driver classes, accounts, and properties below are anonymous examples. Generated configuration belongs in `configs/`, and private drivers belong in `local-drivers/`; both directories are excluded from Git. Run all commands from the project root.

## Common databases

Bundled adapters use Hikari by default. Give each target its own password environment variable and local config so several targets can coexist.

### PostgreSQL

```bash
python3 scripts/runner.py init \
  --adapter postgresql \
  --url 'jdbc:postgresql://localhost:5432/testdb' \
  --username jdbc_tester \
  --password-env POSTGRES_TEST_PASSWORD \
  --consent \
  --output configs/postgresql.local.json

export POSTGRES_TEST_PASSWORD='your-password'
python3 scripts/runner.py run configs/postgresql.local.json
```

### MySQL

```bash
python3 scripts/runner.py init \
  --adapter mysql \
  --url 'jdbc:mysql://localhost:3306/' \
  --username jdbc_tester \
  --password-env MYSQL_TEST_PASSWORD \
  --consent \
  --output configs/mysql.local.json

export MYSQL_TEST_PASSWORD='your-password'
python3 scripts/runner.py run configs/mysql.local.json
```

### Oracle

Oracle uses the account's existing schema. Always use a dedicated test account rather than a business account.

```bash
python3 scripts/runner.py init \
  --adapter oracle \
  --url 'jdbc:oracle:thin:@localhost:1521/FREEPDB1' \
  --username jdbc_test_user \
  --password-env ORACLE_TEST_PASSWORD \
  --consent \
  --output configs/oracle.local.json

export ORACLE_TEST_PASSWORD='your-password'
python3 scripts/runner.py run configs/oracle.local.json
```

PostgreSQL and MySQL create and clean a random dedicated namespace. Oracle creates and cleans test objects in the dedicated account's schema.

## Private databases and drivers

Private databases do not need project source changes. Put the driver in `local-drivers/`, then select the compatible `oracle`, `mysql`, or `postgresql` dialect. The generated local adapter reuses that dialect's DDL/DML assets.

### Oracle-compatible dialect

```bash
python3 scripts/runner.py init-custom \
  --id database-a \
  --dialect oracle \
  --driver-class 'com.example.jdbc.OracleCompatibleDriver' \
  --driver-dir local-drivers/database-a-driver.jar \
  --url 'jdbc:vendor-a://localhost:9001/test' \
  --username jdbc_tester \
  --password-env DATABASE_A_PASSWORD \
  --consent

export DATABASE_A_PASSWORD='your-password'
python3 scripts/runner.py run configs/database-a.local.json
```

### MySQL-compatible dialect

```bash
python3 scripts/runner.py init-custom \
  --id database-b \
  --dialect mysql \
  --driver-class 'com.example.jdbc.MySqlCompatibleDriver' \
  --driver-dir local-drivers/database-b \
  --url 'jdbc:vendor-b://localhost:9002/test' \
  --username jdbc_tester \
  --password-env DATABASE_B_PASSWORD \
  --consent

export DATABASE_B_PASSWORD='your-password'
python3 scripts/runner.py run configs/database-b.local.json
```

### PostgreSQL-compatible dialect

```bash
python3 scripts/runner.py init-custom \
  --id database-c \
  --dialect postgresql \
  --driver-class 'com.example.jdbc.PostgreSqlCompatibleDriver' \
  --driver-dir local-drivers/database-c \
  --url 'jdbc:vendor-c://localhost:9003/test' \
  --username jdbc_tester \
  --password-env DATABASE_C_PASSWORD \
  --consent

export DATABASE_C_PASSWORD='your-password'
python3 scripts/runner.py run configs/database-c.local.json
```

Local adapters start with permissive metadata identity ranges. After the first run, tighten the product name, driver name, and version ranges in `configs/custom-adapters/<id>/adapter.json`. A local adapter always has `formal_eligible=False`.

## Connection modes

### Hikari pool

Bundled adapters use Hikari by default. A private database may select it explicitly:

```bash
python3 scripts/runner.py init-custom \
  --id database-pool \
  --dialect postgresql \
  --connection-mode hikari \
  --driver-class 'com.example.jdbc.Driver' \
  --driver-dir local-drivers/database-pool \
  --url 'jdbc:vendor://localhost:9000/test' \
  --username jdbc_tester \
  --password-env DATABASE_PASSWORD \
  --consent
```

### Direct DriverManager

`init-custom` defaults to DriverManager, or it can be selected explicitly. The framework loads the driver class and invokes `DriverManager.getConnection(url, properties)`:

```bash
python3 scripts/runner.py init-custom \
  --id database-direct \
  --dialect oracle \
  --connection-mode driver_manager \
  --driver-class 'com.example.jdbc.Driver' \
  --driver-dir local-drivers/database-direct.jar \
  --url 'jdbc:vendor://localhost:9000/test' \
  --username jdbc_tester \
  --password-env DATABASE_PASSWORD \
  --consent
```

## JDBC properties and URL parameters

Repeat `--property` for ordinary vendor properties:

```bash
--property vendor.app.name=jdbc-test \
--property vendor.mode=compatibility
```

Use environment variables for tokens and other sensitive values:

```bash
export VENDOR_ACCESS_TOKEN='your-token'

python3 scripts/runner.py init-custom \
  --id database-properties \
  --dialect mysql \
  --driver-class 'com.example.jdbc.Driver' \
  --driver-dir local-drivers/database-properties \
  --url 'jdbc:vendor://localhost:9000/test' \
  --username jdbc_tester \
  --password-env DATABASE_PASSWORD \
  --property vendor.app.name=jdbc-test \
  --property-env vendor.access.token=VENDOR_ACCESS_TOKEN \
  --consent
```

`user` and `password` are reserved and must be supplied through `--username` and `--password-env`. JDBC URLs are passed through unchanged, including driver-specific parameters:

```bash
--url 'jdbc:vendor://localhost:9000/test?ssl=true&applicationName=jdbc-test'
```

## Single- and multi-JAR drivers

For one driver JAR:

```bash
--driver-dir local-drivers/vendor-driver.jar
```

For a driver with dependencies, place every JAR in one directory:

```text
local-drivers/database-a/
├── vendor-driver.jar
├── dependency-a.jar
└── dependency-b.jar
```

Then configure the directory:

```bash
--driver-dir local-drivers/database-a
```

The framework adds every `*.jar` in stable order to the test classpath.

## Repeated runs and reports

After generating a config, subsequent runs only need the environment variable and `run` command:

```bash
export DATABASE_PASSWORD='your-password'
python3 scripts/runner.py run configs/database-a.local.json
```

Reports are written to `report/<adapter>_timestamp/`. Compatibility failures, execution errors, and preflight failures return a non-zero exit code, but reports are still generated. `diagnostics.log` can contain local troubleshooting details and should be reviewed before sharing.
