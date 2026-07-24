# JDBC 回归测试使用示例

[English](README.en.md) | 中文

本文档中的地址、驱动类、账号和属性均为匿名示例。生成的配置位于 `configs/`，私有驱动位于 `local-drivers/`；两个目录都不会提交到 Git。所有命令从项目根目录执行。

## 常见数据库

内置适配包默认使用 Hikari 连接池。每次使用独立的密码环境变量和本地配置，便于同时保存多个目标。

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

Oracle 使用账号现有 schema，请使用专用测试账号，不要使用业务账号。

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

PostgreSQL 和 MySQL 会创建随机专用测试命名空间并在结束时清理；Oracle 会在专用账号的 schema 中创建和清理测试对象。

## 自研数据库与私有驱动

自研数据库不需要写入项目源码。将驱动放入 `local-drivers/`，然后根据实际兼容方言选择 `oracle`、`mysql` 或 `postgresql`。生成的本地适配包会复用所选方言的 DDL/DML。

### Oracle 兼容方言

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

### MySQL 兼容方言

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

### PostgreSQL 兼容方言

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

本地适配包首次使用宽松的元数据身份范围。首跑后可在 `configs/custom-adapters/<id>/adapter.json` 中收紧产品名、驱动名和版本范围。它始终标记为 `local`，所以 `formal_eligible=False` 是正常结果。

## 连接方式

### Hikari 连接池

内置适配包默认使用 Hikari。自研数据库也可显式选择：

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

### DriverManager 直连

`init-custom` 默认使用 DriverManager，也可显式声明。框架会加载驱动类，然后调用 `DriverManager.getConnection(url, properties)`：

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

## JDBC Properties 与 URL 参数

普通厂商属性可重复使用 `--property`：

```bash
--property vendor.app.name=jdbc-test \
--property vendor.mode=compatibility
```

令牌、密钥等敏感值使用环境变量，不写入本地 JSON：

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

`user` 和 `password` 是保留属性，必须分别通过 `--username` 和 `--password-env` 配置。JDBC URL 会原样交给驱动，因此也可以使用驱动定义的 URL 参数：

```bash
--url 'jdbc:vendor://localhost:9000/test?ssl=true&applicationName=jdbc-test'
```

## 单 JAR 与多 JAR 驱动

单个驱动 JAR：

```bash
--driver-dir local-drivers/vendor-driver.jar
```

驱动包含多个依赖时，将所有 JAR 放入同一目录：

```text
local-drivers/database-a/
├── vendor-driver.jar
├── dependency-a.jar
└── dependency-b.jar
```

然后配置目录：

```bash
--driver-dir local-drivers/database-a
```

框架会按稳定顺序将目录中的全部 `*.jar` 加入测试 classpath。

## 日常重复运行与报告

配置生成后无需再次执行 `init`，只需设置环境变量并运行：

```bash
export DATABASE_PASSWORD='your-password'
python3 scripts/runner.py run configs/database-a.local.json
```

报告位于 `report/<adapter>_时间戳/`。兼容性失败、执行错误或预检失败会返回非零退出码，但报告仍会生成。`diagnostics.log` 可能包含本地排障信息，分享前应人工检查。
