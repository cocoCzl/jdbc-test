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

- JDK 21+
- Maven 3.8+
- Python 3.8+（仅生成归档报告时需要）
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
├── src/main/java/           # 构建期辅助代码（配置加载、属性生成）
├── src/test/java/           # JUnit 测试代码
├── config.yaml.example      # 配置模板
├── Dockerfile               # Docker 执行环境
└── pom.xml                  # Maven 项目配置
```

## 快速开始

项目已在 `configs/` 下预置了三种数据库配置：

| 数据库     | 配置文件                        |
|-----------|-------------------------------|
| PostgreSQL | `configs/config-postgresql.yaml` |
| MySQL      | `configs/config-mysql.yaml`      |
| Oracle     | `configs/config-oracle.yaml`     |

### 1. 配置数据库连接

复制对应数据库的配置，或直接使用已有配置：

```bash
# 以 PostgreSQL 为例
cp configs/config-postgresql.yaml configs/config.yaml
```

然后修改 `configs/config.yaml` 中的数据库连接信息：

```yaml
db:
  type: postgresql
  url: jdbc:postgresql://localhost:5432/postgres
  username: your_user
  password: your_password
```

> 密码也支持环境变量替换：`password: ${DB_PASSWORD}`，此时需先 `export DB_PASSWORD='your-password'`。

### 2. 执行测试

**方式一：Python runner（推荐，会生成格式化报告）**

```bash
python3 scripts/runner.py configs/config.yaml
```

也可以直接指定其他配置运行不同数据库：

```bash
python3 scripts/runner.py configs/config-mysql.yaml
python3 scripts/runner.py configs/config-oracle.yaml
```

报告输出到 `report/{db_type}_{timestamp}/`，包含 `report.json`、`report.html` 和 `report.md`。

**方式二：Maven 直接执行（仅生成 Surefire 原始报告）**

```bash
mvn test -Dconfig.yaml=configs/config.yaml
```

Surefire 报告位于 `target/surefire-reports/`。

**方式三：IDEA 中运行**

1. **File → Project Structure → Modules**，确认 `src/test/java` 被标记为 Test Sources Root（绿色文件夹图标）
2. **Run → Edit Configurations → + → JUnit**
   - Test kind: `All in package`
   - Package: `com.jdbctest`
   - Module: `jdbc-test`
   - VM options: `-Dconfig.yaml=configs/config.yaml`
3. 点击绿色 ▶ 运行

> IDEA 中运行仅显示 IDEA 内置测试面板，不生成项目的格式化报告。如需 HTML 报告，请使用 Python runner。

## 运行指定测试

通过配置文件过滤：

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

## 测试覆盖范围

| 模块                    | 测试类                         | 主要验证内容                          |
|------------------------|-------------------------------|-------------------------------------|
| Connection             | ConnectionTest                | 连接、自动提交、事务、Schema、类型映射     |
| Statement              | StatementTest                 | execute/executeQuery/executeUpdate、批处理、超时 |
| PreparedStatement      | PreparedStatementTest          | 参数绑定、批处理、获取生成主键             |
| CallableStatement      | CallableStatementTest          | 函数调用、输出参数、wasNull              |
| ResultSet              | ResultSetTest                 | 游标移动、类型读取、wasNull、滚动结果集     |
| DatabaseMetaData       | DatabaseMetaDataTest          | 表/列/主键/索引等元数据查询              |
| ResultSetMetaData      | ResultSetMetaDataTest         | 列类型、精度、可空性、自增等属性           |
| ParameterMetaData      | ParameterMetaDataTest         | 参数数量、类型、模式等                  |
| Savepoint              | SavepointTest                 | 创建/释放/回滚保存点                    |
| RowSet                 | RowSetTest                    | JdbcRowSet 导航、更新、插入、删除         |
| Blob/Clob              | BlobClobTest                  | 二进制和文本大数据读写、流操作             |
| SQLXML                 | SQLXMLTest                    | XML 数据读写、Source/Result 操作        |
| DataSource             | DataSourceTest                | 连接池获取、超时、日志等                 |
| Wrapper                | WrapperTest                   | isWrapperFor/unwrap 契约             |
| AdvancedType           | AdvancedTypeTest              | 数组、结构等高级类型                     |

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

### MySQL 需要预先创建数据库

MySQL 的 JDBC URL 中必须指定数据库名（如 `jdbc:mysql://host:port/jdbctest`），因此需要提前在 MySQL 中创建数据库：

```sql
CREATE DATABASE jdbctest;
```

PostgreSQL 默认存在 `postgres` 库可直接使用，Oracle 使用用户默认 tablespace 无需额外操作。

### 配置文件找不到

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

### IDEA 中运行按钮灰色

1. 确认 `src/test/java` 目录被标记为 Test Sources Root（绿色文件夹图标）
2. 右键该目录 → **Mark Directory as** → **Test Sources Root**
3. 刷新 Maven：右键 `pom.xml` → **Maven** → **Reload Project**
4. 如仍不行：**File** → **Invalidate Caches** → 勾选全部 → **Invalidate and Restart**
