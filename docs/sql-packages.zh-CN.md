# 声明式 SQL 功能测试包（格式 1）

SQL 包定义 SQL 功能及预期，不定义 JDBC 契约。数据库适配包继续负责产品身份、方言和命名空间生命周期。
SQL 断言失败不能自动归因为数据库缺陷，应先核查定义、版本/模式前提和执行证据。
CORE/FULL 与 JDBC 内部基线语义不变；本阶段不支持 SQL 包的 `baseline`/`compare`。

## 完整示例与校验

[postgresql-basics.json](../examples/sql/postgresql-basics.json) 是可直接导入的完整示例，四个语义用例覆盖：
基础有序查询、明确 JDBC 类型的参数绑定、SQL NULL 与字符串 `"NULL"`、精确 Decimal scale、JOIN、聚合、
保留重复数量的无序多重集、更新计数，以及仅指定执行步骤的 `22012` 预期错误和随后事务恢复。
声明适用于 PostgreSQL 12–18；该示例真实验证范围仅为独立临时 PostgreSQL 16。

[mysql-basics.json](../examples/sql/mysql-basics.json) 提供相同四类测试意图的显式 MySQL 变体：
SIGNED/CHAR 参数转换、InnoDB 夹具，以及重复主键的 SQLState `23000`、厂商码 `1062` 和
`SQLIntegrityConstraintViolationException`。MySQL 的 SELECT 除零可能返回 NULL/警告，因此不套用
PostgreSQL `22012` 断言。预期依据为官方 [ER_DUP_ENTRY](https://dev.mysql.com/doc/mysql-errors/8.4/en/server-error-reference.html#error_er_dup_entry)
及[类型转换文档](https://dev.mysql.com/doc/refman/8.4/en/cast-functions.html)。本轮独立临时 MySQL 8.4、
Connector/J 8.3.0 经真实 Worker 四项通过，两张归属表确认删除；不代表完整 MySQL/JDBC 兼容性。

采用严格 UTF-8 JSON，拒绝重复字段、未知字段及不支持的 `formatVersion`；版本必须为整数 `1`。
可直接上传 JSON，或上传仅含根目录文件 `sql-package.json` 的 ZIP。ZIP 复用适配包路径及资源边界：
最多 512 个条目、每文件 1 MiB、解压共 8 MiB，压缩上传本身也限 8 MiB。不支持外部 SQL 文件、JAR、
可执行扩展或任意解压目录。

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
python3 -B scripts/runner.py sql-validate --package examples/sql/postgresql-basics.json
```

需 JDK 21。CLI 与 Web 使用同一个 Core 校验器；校验时不加载驱动、不连接数据库。
退出码 0 仅表示定义有效，2 表示定义无效或校验无法完成，不代表连接成功或用例通过。

## 字段与断言

| 字段 | 必须明确的语义 |
| --- | --- |
| `formatVersion` | 整数 1，不兼容版本拒绝 |
| `id`、`version`、`description` | 包 ID（小写可移植字符，最多 64）、用户版本标签、说明 |
| `cases` | 1–200 项，包内稳定 ID 唯一 |
| 用例 `id`、`name`、`tags` | 稳定 ID（最多 64）、名称、字符串标签列表 |
| `appliesTo` | JDBC 元数据产品名精确列表 `products`、明确 `dialects`、含边界的 `minMajor`/`maxMajor`、`modeDescription` |
| `timeoutSeconds` | 1–300 秒，含准备的用例期限；清理仍受任务总预算约束 |
| `prerequisites` | 准备前的显式 SELECT/ROWS 步骤；用于更细版本、兼容模式及其他可观察前提 |
| `variants` | 每种声明方言对应显式变体，无默认回退 |
| 变体 `prepare`、`execute`、`cleanup` | 顺序步骤、非空执行步骤、逆序删除的逻辑表名列表 |
| 步骤 `id`、`sql`、`parameters`、`expect` | 同阶段唯一 ID、单条 SQL、明确类型的参数、断言 |

识别已有 postgresql/mysql/oracle/sqlserver 方言。产品、方言或主版本不适用时记为前提未满足，不记通过。
适配包声明不能使 SQL 用例自动适用。`modeDescription` 只是说明，要求特定模式或更细版本时必须增加
前提查询及预期。每次取连接仍检查实际授权数据库/Schema。PostgreSQL 16 与 MySQL 8.4 示例已有本文限定范围的真实执行证据；
其他方言需要作者提供变体，并在其独立临时环境验证。

第一版 SQL 必须以大写 `SELECT `、`INSERT INTO `、`UPDATE `、`DELETE FROM `，或仅准备阶段的
`CREATE TABLE {{logical}} (` 开始。拒绝注释和所有分号（包括字面值中的分号），不按分号切割脚本。
不支持过程、事务/会话控制语句、任意 DDL 或任意清理脚本。DML 目标必须是当前变体准备创建的表占位符；
所有占位符必须有显式准备声明，`cleanup` 必须恰好列出每个创建的表一次。

`{{items}}` 是作者显式写出的夹具占位符，映射为带引用、完整命名空间限定、任务及用例专属的物理表名。
除此以外不改写被测 SQL。报告保留模板、实际 SQL、变体、参数、预期/实际值和原因。
**不要在 SQL 或参数中写秘密**：测试输入和输出是报告证据；已知连接凭据沿用现有脱敏投影。

参数形式为 `{ "type": "INTEGER|DECIMAL|STRING|BOOLEAN", "value": ... }`，按列表顺序绑定。
分别使用 JDBC INTEGER/setInt（有符号 32 位）、DECIMAL/setBigDecimal、VARCHAR/setString、
BOOLEAN/setBoolean。JSON `null` 使用对应类型的 setNull。整数与 Decimal 必须使用普通十进制 JSON 字符串；
布尔值使用 JSON 布尔值，不隐式转型，不使用任意 setObject。

- `ROWS`：必须包含 `order: ORDERED|MULTISET`、列类型 `columns`、完整预期 `rows`、
  `decimalMode: NUMERIC|SCALE`。列数等于 columns 长度，行数等于 rows 长度。
  MULTISET 按每种类型化行的出现次数比较，保留重复数量；NUMERIC 认为 1.20 与 1.2 相等，
  SCALE 同时要求数值与 scale 相等。JSON null 与字符串 `"NULL"` 不同。
  结果 INTEGER 接受 JDBC 整数类型（包括 BIGINT），按 BigInteger 精确比较；DECIMAL 只接受 NUMERIC/DECIMAL；
  STRING 只接受字符类型，BOOLEAN 接受 BOOLEAN/BIT。不能通过声明 STRING 将浮点/复杂值统一转字符串。
- `UPDATE`：如 `{ "kind": "UPDATE", "count": 1 }`，精确非负更新计数，不能返回结果集。
- `ERROR`：如 `{ "kind": "ERROR", "sqlState": "22012", "exceptionType": "java.sql.SQLException" }`。
  必须指定精确 SQLState 和/或整数 vendorCode；可选完整异常类型名，按实际异常继承链比较，不加载该类。
  所有指定条件须同时满足，不使用错误消息匹配。范围仅为指定步骤的 PreparedStatement.execute，
  不包括连接、保存点、prepare/bind、关闭、回滚或清理。连接/认证/权限/取消/超时错误及已审查厂商权限码不能
  作为预期通过。所有私有驱动权限分类并不完备，应使用明确的产品错误码及 SQLState，避免宽泛厂商码谓词。

预期错误步骤实际尝试建立保存点，不以元数据 false 直接代替实测；无法建立时是前提未满足。
执行后回滚并释放保存点，恢复错误单独记录，不能覆盖原始失败。准备步骤禁止预期异常。
浮点、时间、二进制、LOB、数组及复杂类型断言、多结果序列不受支持；每步最多 1000 行、100 列。

## CLI 运行

复用已有本地 JSON 连接配置及本地适配包，驱动及依赖可用本地 JAR 目录或固定 Maven 来源。
SQL 入口只支持显式 `db.connection_mode: "driver_manager"` 和已存在的专用授权命名空间：

```json
{
  "adapter": "postgresql",
  "db": {
    "url": "jdbc:postgresql://localhost:5432/disposable_sql",
    "username": "sql_test_user",
    "password_env": "SQL_TEST_PASSWORD",
    "connection_mode": "driver_manager",
    "driver": {"kind": "local", "path": "/private/local-jdbc-jars"}
  },
  "namespace": {
    "mode": "existing", "database": "disposable_sql", "name": "sql_tests",
    "destructive_consent": true
  }
}
```

在本地设置密码环境变量，不放在 argv，不提交配置。`adapter` 可换为私有适配包路径；现有打包器冻结适配
资源，不把驱动复制进 SQL 测试包。Server/Worker 必须配套更新。

```sh
python3 -B scripts/runner.py sql-run --config /private/sql-config.json \
  --package examples/sql/postgresql-basics.json \
  --case basic.query --case null.parameter --case join.aggregate --case expected.error \
  --output report/sql-feature-run --budget-seconds 300
```

输出目录必须是新目录。保留报告与归属账本，临时连接配置、解压资源及执行工作区自动删除。
密码通过 Worker stdin，驱动只在隔离类加载器使用。SQL 运行无需 JDBC 比较密钥，也不产生其签名比较证据。

退出码 0：选中 SQL 断言全部完成且清理确认成功；1：完成执行但有断言、准备、执行或清理失败；
2：无效输入、Worker 不可用、缺少结果、任务中断/超时且证据不完整。这是执行退出码，不是回归比较退出码。
预期错误通过只覆盖该指定步骤；故意失败夹具用于平台验证，不是数据库缺陷记录。

## Web 与持久化

1. 沿用驱动管理上传并验证驱动；需要时导入数据库适配包，然后建立显式授权数据库/Schema 的连接。
2. 打开“SQL 测试包”，导入 JSON/ZIP，检查具体字段错误或已保存定义。定义校验、驱动校验、连接成功和测试通过
   是不同状态。
3. 明确选择测试包版本、用例、现有连接及默认驱动，审查命名空间、适配包和 SQL，必要时输入一次性密码并授权。
4. 沿用任务页、结果弹窗和报告查看 SQL 领域、冻结包定义/指纹、适配包及驱动快照。
   结果弹窗展示逐步预期/实际值、SQL、原因及独立清理证据。SQL 任务不展示 JDBC 契约统计，保留执行计数。

V10 在平台 PostgreSQL 新增不可变 `sql_test_package(fingerprint,snapshot_json)`。
SHA-256 覆盖原始 UTF-8 清单字节，空白变化也改变指纹。用户版本标签不等同内容身份。
相同内容重复导入幂等；同 ID/版本不同内容保存为另一明确版本。第一版包库只追加，无删除入口。
排队时将整个 SQL 包、实际用例元数据/ID 和指纹一起复制到 execution input，与适配包资源和驱动独立冻结。
重导入、更新和重启不从当前库反查改写历史任务。沿用当前部署的访问模型，没有新增或绕过命名空间授权。

## 隔离、超时与取消边界

只支持经过审查的可信包/驱动和专用可丢弃目标。关键字限制、对象前缀均不是安全沙箱，SELECT 函数也可能写入；
数据库账号必须独立限制对专用范围之外的访问，不得通过扩大权限让包运行。

每个用例使用单独的受限连接和独立表名。准备阶段自动提交，创建结果进入归属账本，不假定 DDL 可回滚。
执行阶段使用事务，始终尝试回滚/关闭，再逆序清理归属表。后续用例不复用表名或事务。
清理错误不覆盖原断言，原先通过的用例遇到清理错误也会失败。CREATE/DROP 结果不确定时保留，不预删冲突对象、
不扫描任意对象删除、不使用 CASCADE。

用例期限与 JDBC queryTimeout 仍受总任务/进程预算约束。Web 取消先写任务专属信号，只有 Statement.cancel
跨线程；调用返回后，由原执行线程回滚、删除和关闭，取消任务保持 CANCELLED 并持久化收尾证据。
现有终止宽限期限制等待，驱动不响应时仍强制结束进程。进程被杀、崩溃或忽略超时不能保证清理，
必须保留 UNKNOWN/未完成及归属账本，人工核查精确对象，不能记通过或对仍运行的 SQL 并发清理。

## 本阶段验证范围

单元/mock 覆盖精确值、行列数量、有序与多重集重复数量、NULL/Decimal scale、不支持的实际类型、非法包/ZIP、
预期异常作用域、准备与执行错误、独立恢复/清理错误、取消及超时检查。
REST/JPA 覆盖不可变版本、用例精确选择、排队快照和 JDBC 目录隔离。第三阶段初始浏览器测试
使用夹具模拟；后续真实浏览器集成验收范围见本节末尾。

本轮独立临时 PostgreSQL 16 验证空库完整迁移、带合成旧数据的 V8→V9→V10、旧驱动/连接/任务/结果/报告读取，
及关闭/重开应用上下文后适配资源、SQL 包和任务快照恢复。
真实上传 pgjdbc 和 Worker 验证 REST 导入→连接→选择 SQL 任务→持久化报告，覆盖四个示例、故意断言失败、
语句超时、协作取消和归属表零残留；CLI 使用同一临时目标验证退出码 0/1，无数据库验证非法输入退出码 2。
不证明跨产品兼容性、其他 PostgreSQL 版本、恶意驱动隔离或强制杀进程后的清理。未访问业务、共享或历史数据库。

可复用的验证命令（PostgreSQL 两项只允许本轮新建的可丢弃实例）：

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
./mvnw -pl jdbc-test-core,jdbc-test-worker,jdbc-test-server -am test \
  -Dtest=SqlTestPackageTest,SqlFeatureExecutorTest,SqlPackageFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# JDBC_TEST_DISPOSABLE_PG_URL 只能指向该临时容器的回环 /postgres URL。
./mvnw -pl jdbc-test-server -am test \
  -Dtest=PostgresPackageMigrationTest,PostgresSqlFlowTest \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 -B scripts/run_contract_tests.py
npm --prefix jdbc-test-web test
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run build
```

实际目标为 PostgreSQL 16.15、pgjdbc 42.7.11。Web 全量 219 项通过，最终展示变更后相关 101 项复验通过；
中英文桌面/移动端夹具浏览器流程通过。Python 合约 78 项通过。Java 语法检查器测试 23 项通过，
扫描无新增/失效基线项，保留既有 224 项。IDEA 原生格式检查仍受已有实例锁访问限制阻塞，未关闭 IDE、
未处理锁文件；git diff --check 通过。上述语法扫描不等同完整语义或 Alibaba 规范检查。
Web 运行发行包包含示例 JSON 和本文；CLI 命令需要源码/CLI 发行包及已构建 Worker，非默认路径可传 --worker。

集成验收还使用真实临时 PostgreSQL 平台/目标、Server 和 Worker，通过浏览器验证导入、排队期间更新和
正常重启。报告按 JSON 结构逐字段比较（跨 JVM 对象字段顺序可能不同）；冻结清单、指纹、步骤和值保持一致。
已完成且报告已校验的 SQL 断言失败标识为 `SQL_ASSERTION_FAILED`，与 `WORKER_EXECUTION_FAILED` 区分，
沿用 FAILED 执行状态。Worker 的 SQL、模板、参数、结果行及输出快照均遮蔽已知连接密码；无法识别任意
作者自填的秘密，仍不得在定义中嵌入秘密。客户端断开不再向已关闭 SSE 流写入 JSON；其他 REST/平台错误
仍保留错误响应。
