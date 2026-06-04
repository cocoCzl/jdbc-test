# JDBC Test

JDBC Test 是一个基于 Maven 和 JUnit 5 的 JDBC 接口兼容性测试项目，用于验证不同数据库及其 JDBC 驱动在常见 JDBC API 场景下的行为。

当前项目内置支持：

- PostgreSQL
- GaussDB
- MySQL
- Oracle
- SQL Server

测试覆盖连接、Statement、PreparedStatement、CallableStatement、ResultSet、元数据、Savepoint、RowSet、Blob/Clob、SQLXML 和部分高级类型等场景。

## 环境要求

- JDK 21
- Maven 3.8+
- Python 3.8+，使用 `scripts/runner.py` 生成归档报告时需要
- 可访问的目标数据库实例

Python 依赖：

```bash
pip install -r scripts/requirements.txt
```

## 目录结构

```text
.
├── configs/                 # 本地配置文件，可放置不同数据库的 config
├── ddl/                     # 各数据库建表和初始化结构脚本
├── dml/                     # 各数据库测试数据脚本
├── lib/                     # Maven 无法直接下载的第三方 JDBC 驱动 JAR
├── pool/                    # HikariCP 连接池配置
├── profile/                 # 数据库能力开关配置
├── report/                  # runner 生成的测试报告归档
├── scripts/runner.py        # 测试执行和报告生成脚本
├── src/main/java/           # 构建期辅助代码
├── src/test/java/           # JUnit 测试代码
├── config.yaml.example      # 配置模板
├── Dockerfile               # Docker 执行环境
└── pom.xml                  # Maven 项目配置
```

## 快速开始

复制配置模板：

```bash
cp config.yaml.example configs/config.yaml
```

修改 `configs/config.yaml` 中的数据库连接信息：

```yaml
db:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/postgres
  username: postgres
  password: ${DB_PASSWORD}
```

设置密码环境变量：

```bash
export DB_PASSWORD='your-password'
```

执行测试并生成报告：

```bash
python scripts/runner.py configs/config.yaml
```

报告会输出到：

```text
report/{db_type}_{timestamp}/
```

根据配置，目录中会生成 `report.json`、`report.html` 和 `report.md`。

## 直接使用 Maven 执行

也可以不经过 Python runner，直接运行 JUnit 测试：

```bash
mvn test -Dconfig.yaml=configs/config.yaml
```

这种方式会生成 Maven Surefire 原始报告：

```text
target/surefire-reports/
```

## 配置说明

完整配置示例见 `config.yaml.example`。

```yaml
db:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/postgres
  username: postgres
  password: ${DB_PASSWORD}

ddl:
  base_path: ddl

dml:
  base_path: dml

pool:
  profile_dir: pool

profile:
  profile_dir: profile

concurrency:
  enabled: false
  threads: 4
  timeout: 300000

execution:
  mode: local

report:
  output_dir: report
  format:
    - json
    - html
    - markdown

test_filter:
  include_tests: []
  exclude_tests: []
  timeout: 60000
```

主要字段：

- `db.type`：数据库类型，支持 `postgresql`、`gaussdb`、`mysql`、`oracle`、`sqlserver`
- `db.url`：完整 JDBC URL
- `db.username` / `db.password`：数据库账号密码，密码支持 `${ENV_NAME}` 环境变量替换
- `ddl.base_path`：DDL 脚本根目录
- `dml.base_path`：DML 脚本根目录
- `pool.profile_dir`：连接池配置目录
- `profile.profile_dir`：数据库能力配置目录
- `concurrency.enabled`：是否按测试类并行执行
- `concurrency.threads`：并行线程数
- `execution.mode`：执行模式，支持 `local` 和 `docker`
- `report.format`：归档报告格式，支持 `json`、`html`、`markdown`
- `test_filter.include_tests`：只运行指定测试类或方法
- `test_filter.exclude_tests`：排除指定测试类或方法

## 运行指定测试

通过配置文件过滤测试：

```yaml
test_filter:
  include_tests:
    - ConnectionTest
    - PreparedStatementTest
  exclude_tests: []
  timeout: 60000
```

也可以直接使用 Maven 参数：

```bash
mvn test -Dconfig.yaml=configs/config.yaml -Dtest=ConnectionTest
```

## 数据库脚本

测试类通过注解加载对应的 SQL 脚本。脚本按数据库类型分目录存放：

```text
ddl/{db_type}/
dml/{db_type}/
```

例如 MySQL 的 PreparedStatement 测试脚本：

```text
ddl/mysql/preparedstatement_ddl.sql
dml/mysql/preparedstatement_dml.sql
```

执行测试前会运行 DDL 和 DML，测试结束后会尝试清理本次创建的表。

## 数据库能力 Profile

`profile/{db_type}.yaml` 用于声明数据库是否支持某些能力。测试中带有能力要求的用例会根据 profile 自动跳过不支持的场景。

示例：

```yaml
features:
  savepoint: true
  callable_statement: true
  sqlxml: false
  rowset: true
  blobs: true
  clobs: true
```

## 连接池配置

`pool/{db_type}.yaml` 用于配置 HikariCP：

```yaml
maximumPoolSize: 20
minimumIdle: 5
connectionTimeout: 30000
idleTimeout: 600000
maxLifetime: 1800000
leakDetectionThreshold: 60000
```

## 自定义 JDBC 驱动

如果目标数据库驱动无法从 Maven 中央仓库下载，可以将 JAR 放入 `lib/`，并在配置中声明：

```yaml
db:
  type: mydb
  url: jdbc:mydb://localhost:1234/db
  username: user
  password: ${DB_PASSWORD}
  custom_driver:
    load_method: local_repo
    jar_path: lib/my-jdbc-driver.jar
    group_id: com.example
    artifact_id: my-jdbc-driver
    version: 1.0.0
```

`load_method` 支持：

- `local_repo`：runner 自动执行 `mvn install:install-file` 安装到本地 Maven 仓库
- `classpath`：runner 将 JAR 加入 Surefire 附加 classpath

更多说明见 `lib/README.md`。

## Docker 执行

将配置中的执行模式改为：

```yaml
execution:
  mode: docker
```

然后运行：

```bash
python scripts/runner.py configs/config.yaml
```

Docker 模式会构建 `jdbc-test-runner` 镜像，并将当前项目目录挂载到容器内执行 Maven 测试。

## 报告

使用 `scripts/runner.py` 执行后，会解析 `target/surefire-reports/` 并生成归档报告：

```text
report/{db_type}_{timestamp}/
├── report.json
├── report.html
└── report.md
```

报告包含：

- 测试汇总
- 测试类和测试方法明细
- 失败、错误和跳过原因
- 数据库产品、JDBC 驱动、Java、操作系统等环境信息

## 常见问题

### 配置文件不存在

默认加载顺序：

1. 命令行参数传入的配置路径
2. 环境变量 `CONFIG_PATH`
3. `configs/config.yaml`
4. `config.yaml`

如果没有默认配置，请先复制模板：

```bash
cp config.yaml.example configs/config.yaml
```

### 密码为空

如果配置中使用 `${DB_PASSWORD}`，需要先设置环境变量：

```bash
export DB_PASSWORD='your-password'
```

### SQL 文件不存在

确认 `db.type` 与脚本目录一致。例如：

```yaml
db:
  type: mysql
```

对应脚本目录应为：

```text
ddl/mysql/
dml/mysql/
```

### 部分测试被跳过

这是数据库能力 profile 生效的结果。检查：

```text
profile/{db_type}.yaml
```

如果数据库实际支持该能力，可以将对应 feature 改为 `true`。
