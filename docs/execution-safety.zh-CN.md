# 执行安全

[English](execution-safety.md) | 简体中文

测试会执行 DDL/DML。使用专用且明确授权的数据库/schema，并在同意执行前核对冻结的范围。连接探测不执行测试 SQL，不要求写入范围。独立 Worker 不能隔离恶意 SQL 或驱动。

## 授权范围

新 Web 任务要求连接配置提供 `executionDatabase` 和/或 `executionSchema`，并明确同意破坏性测试。缺少范围时，入队前返回 `422 EXECUTION_SCOPE_REQUIRED`。

| 适配包 | 必填范围 |
|---|---|
| MySQL | 数据库；每次获取连接时选择并验证 catalog |
| PostgreSQL、SQL Server、GaussDB、KingbaseES | 数据库和 schema；验证数据库身份与 schema |
| Oracle | 明确授权且已存在的 schema；普通标识符使用大写语义 |

范围标识符以 ASCII 字母开头，后续为字母、数字或下划线，最长 63 个字符。无效名称直接拒绝。平台不会创建 Oracle 用户或 Web 测试 schema；运行前先准备授权范围，不要盲目复用已删除的 schema。

任务快照冻结 URL、用户名、数据库/schema、同意状态、对象前缀和适配能力，不包含密码。后续编辑连接不会改变已入队任务。修改元数据时省略范围字段会保留原值，显式空字符串会清除。仅用于探测的连接必须配置范围后才能创建破坏性任务。

CLI 的已有命名空间模式同样要求明确的 `namespace.name`；登录用户名不能代表范围授权。CLI 创建的命名空间在清理归属对象后仍会保留，需检查后另行移除空命名空间。

## 对象归属与清理

每次任务使用随机前缀。显式 SQL 占位符将测试对象映射为带引号、包含 schema/catalog 的物理名称，最长 28 个字符；预编译 SQL、本地索引、函数体与元数据查询共用映射。Oracle 名称使用大写。映射不替换任意 SQL 单词。所有 DataSource 获取连接时都会初始化范围，包括连接池、DriverManager 模式和 JdbcRowSet；测试对象的限定名不依赖连接默认值。元数据模式会转义下划线。

归属日志在 CREATE 前及确认结果后记录。清理仅删除确认 `CREATED` 的对象，不预先删除冲突对象，不使用 `CASCADE`、`DROP IF EXISTS`，不枚举命名空间，也不按前缀批量删除。无法确认的 CREATE/DROP 记为 `UNKNOWN` 并保留。隐式索引、约束、序列和 LOB 对象由父对象管理，不能仅凭元数据独立删除；依赖发现不可用时记录不确定性。

强制停止可能留下 `CREATING`、`CREATED`、`DROPPING` 或 `UNKNOWN`。终态 JSON 中的归属日志说明残留与不确定性，不证明清理已经执行。没有自动大范围恢复清理；人工移除前必须核对具体归属对象。

## 能力与结果统计

CLI 和 Worker 共用官方适配能力声明。全部 34 项受管能力必须为布尔值。缺失或无效声明导致配置失败；`false` 产生带来源的明确跳过，`true` 只启用测试，不代表兼容通过。DataSource 接口能力来自显式配置的驱动实现，其余能力仍由适配声明控制。FULL 选择 270 个场景。

新任务使用 `protocolVersion: 2` 和 `report_schema_version: 2.0.0`，Server/Worker 必须配套部署。Flyway V5–V8 均为增量迁移，V7 保存清理/终止证据，V8 保存观察元数据；旧任务保留原结果，不补造开始记录或 v2 证据。

| 场景状态 | 含义 |
|---|---|
| PASSED / FAILED | 已记录的实际完成结果 |
| SKIPPED | 明确的方法 / 容器跳过，包含来源和原因 |
| ABORTED | 实际场景中止 |
| PENDING / RUNNING | 活动任务中，已选择但未开始 / 已开始但未完成 |
| NOT_RUN / INCOMPLETE | 任务结束后对应的未运行 / 未完成状态 |

`completed` 统计已记录的终态结果，包括明确跳过。`started` 只统计实际开始，执行前跳过不计入。容器/初始化失败保留层级、异常和受影响的已选后代场景，不编造子场景失败。全部跳过不能算兼容性成功。

REST、SSE、持久证据和原始 JSON 使用同一套规则。崩溃后重建的原始 JSON 通过 `source` 和 `workerReportValidated` 标明来源；原 Worker 报告仍是独立制品。正常终态报告中缺少结果必须有匹配的容器失败证据。错误任务 ID、未选择或重复结果、无效顺序，以及范围、能力、状态、数量不一致仍属错误。回归命令见[开发指南](development.zh-CN.md#测试)。

## 已验证组合

2026-10-07 的检查点使用生产 Web → REST → 外部驱动 Worker → 持久化/报告链路，分别使用明确授权的范围。私有地址、凭据和原始 QA 资料不进入 Git。

| 数据库 / 驱动 | Connection | Statement | Core | Full |
|---|---:|---:|---:|---|
| MySQL 8.0.30 / Connector/J 8.3.0 | 17 通过 | 23 通过 | 136 通过 | 172 通过，30 跳过 |
| PostgreSQL 15.2 / pgjdbc 42.7.2 | 17 通过 | 23 通过 | 136 通过 | 198 通过，4 跳过 |
| Oracle 19.3 / ojdbc11 21.13 | 17 通过 | 23 通过 | 136 通过 | 189 通过，13 跳过 |

这些选择存在重叠。能力被禁用是配置原因，不证明数据库不支持；具体原因见结果页或原始 JSON。这些结果覆盖本地生产 JAR 部署，不代表 Docker 镜像验收、其他驱动版本或发布授权。可丢弃容器的回归不能替代真实目标组合验收。默认 Maven/CI 测试不连接真实目标。

## KingbaseES 候选验证

2026-10-16 的有限实测仅对应：**服务端 V009R002C016、兼容模式 oracle、JDBC 数据库版本 12.1、驱动声明 V008R006B0001**。驱动文件名 `kingbase8-9.0.1.jar` 不是实际版本依据，Driver major/minor 离线报告为 0.0；这些来源不可互相替代。适配器保持 candidate，`validated_combinations=[]`，不推广至其他版本或模式。

20 项与较早 CORE 探索都通过**单独验证程序调用正常隔离 Worker / catalog / JUnit / 所有权清理链路**执行，未经过 Web → Server 排队与持久化验收。20 项使用已注册 kingbase 适配器；较早 CORE 使用候选执行快照，原始请求及当时 Worker 摘要未保留，不能补造。

[脱敏部分结果](../adapters/kingbase/evidence/v9-oracle-partial-20261016.json)逐项保留来源类/方法、状态、时间戳和耗时，以及原始脱敏事件摘要。它是白名单投影，不是完整原始报告。

| 测试 ID | 实际检查内容 | 结果 | 耗时 ms | 与 CORE 重叠 |
|---|---|---|---:|---|
| `connection.commit` | 手动事务提交后查询确认删除结果 | PASSED | 505 | 是 |
| `connection.rollback` | 删除后回滚并确认原有测试行恢复 | PASSED | 490 | 是 |
| `connection.is_valid` | 有效连接 isValid(5) 返回 true | PASSED | 379 | 是 |
| `connection.get_meta_data` | 取得非空元数据，核对产品名、驱动名及驱动版本非空 | PASSED | 352 | 是 |
| `connection.catalog_and_schema` | 读取 catalog/schema，将非空当前值设回，断言操作不抛异常；不测试切换到另一个 schema | PASSED | 460 | 是 |
| `database_metadata.get_database_product_name` | 数据库产品名称非空 | PASSED | 344 | 是 |
| `database_metadata.get_driver_version` | JDBC 驱动版本字符串非空 | PASSED | 347 | 是 |
| `parameter_metadata.get_parameter_count` | 预编译查询的 ParameterMetaData.getParameterCount() 等于 3 | PASSED | 420 | 否 |
| `prepared_statement.set_boolean` | 绑定 true 查询，断言有数据行且 active 列为 true | PASSED | 376 | 否 |
| `prepared_statement.set_timestamp` | 绑定时间戳查询 created_at 大于阈值的数据，断言有数据行 | PASSED | 387 | 否 |
| `prepared_statement.execute_update` | 执行预编译更新并断言影响行数 | PASSED | 398 | 否 |
| `prepared_statement.get_generated_keys` | 预编译插入后取得生成键，断言数值大于 0 | PASSED | 395 | 否 |
| `savepoint.set_savepoint` | 创建非空保存点并回滚到该保存点 | PASSED | 461 | 否 |
| `savepoint.release_savepoint` | 创建命名保存点并释放 | PASSED | 448 | 否 |
| `savepoint.rollback_to_savepoint` | 保存点之后插入数据，回滚后断言插入已撤销 | PASSED | 630 | 否 |
| `savepoint.write_after_savepoint_rollback` | 回滚到保存点后继续写入，仅保留后一次写入，再整体回滚 | PASSED | 557 | 否 |
| `statement.execute_insert` | Statement.execute 插入，断言无结果集且更新计数为 1 | PASSED | 391 | 否 |
| `statement.execute_query` | 取得结果集，断言存在首行且 name 列为预期夹具值 | PASSED | 395 | 否 |
| `statement.get_generated_keys` | Statement 插入后取得生成键，断言数值大于 0 | PASSED | 403 | 否 |
| `statement.execute_update_ddl` | 创建自有临时表，断言 CREATE 的更新计数为 0，再按所有权记录删除 | PASSED | 606 | 否 |

20 项耗时合计 8,744 ms，Worker RUN 事件跨度 22,094.530 ms。用例耗时为 Worker 单调时钟测量，包含 JUnit 参数准备和 afterEach 连接关闭，不含 beforeAll fixture 与最终清理；UTC 事件时间为另一计时来源。

较早 CORE 选择 153 项，开始 43 项、完成 42 项；逐条 CASE_FINISHED 均明确 PASSED，失败/中止/跳过为 0，`database_metadata.get_type_info` 未完成，110 项未开始。42 项的独立状态/耗时见结果文件；其中 **7 项与上述 20 项重叠，不可直接相加**。整体结论仍为 TIMEOUT；后来的单项通过不能追认原任务通过。20 项 Worker 清理成功；CORE 超时时为 UNKNOWN_PENDING_PROCESS_EXIT。两次外层程序均在进程退出后删除自建唯一 schema 并确认不存在，不能把外层清理追记为 Worker SUCCESS。

### CORE 超时诊断

[脱敏诊断记录](../adapters/kingbase/evidence/v9-oracle-type-info-20261016.json)及[英文阶段明细](execution-safety.md#core-timeout-diagnosis)保留复现来源、阶段耗时、方法栈与清理结果。90,000 ms 是 Worker 对整个 JUnit 执行及 `JdbcContext.shutdown()` 的共享期限，包含前置准备、全部选中用例与清理，不是单个测试的限制。报告写入在 Future 之后；Server 另有进程等待期限，但原探索未经过 Server，事件明确是 Worker 自身 TIMEOUT。

原 CORE 用例耗时合计 75,172 ms，其中 bulk rollback/commit 为 27,505 / 26,667 ms；get_type_info 开始时已过 80,468.754 ms，超时事件为 90,042.113 ms，活跃用例仅获得 9,573.359 ms。

最小只读复现中，首次 `getTypeInfo()` 调用耗时 **35,835.853 ms**；随后首次 next 0.027 ms、遍历 1,041 行 0.276 ms、断言 0.003 ms、ResultSet 关闭 0.016 ms。相同连接第二次调用 66.265 ms、读取 TYPE_NAME/DATA_TYPE 的遍历 2.953 ms（这是热调用，不能当作独立冷调用对照）。连接/加载器关闭 2.243 / 0.963 ms，进程正常退出。

保持原 Worker 90 秒预算、只选原始 `database_metadata.get_type_info`，结果 **PASSED / 35,024 ms**；正常报告写出，进程约 37.785 秒退出，Worker 清理一张自建表成功，外层删除自建 schema 并验证不存在。用例结束至清理事件 742.820 ms，元数据事件至 RUN_FINISHED 8.775 ms（包含报告处理，非独立写入基准）。两次线程采样均位于驱动 `getTypeInfo → TypeInfoCache.getSQLType → executeWithFlags → socket read`。

当前证据支持的最小结论是：**前面测试累积耗时消耗总预算，同时该组合驱动首次类型信息查询约需 35 秒**。复现未见遍历、断言、报告或关闭阻塞，尚无已确认的平台缺陷。原中断调用没有内部阶段记录，不能断言其当时精确耗时；采样也未区分服务端执行、网络与驱动查询次数。不提高超时、不跳过用例、不关闭能力，没有反复运行完整 CORE。

复用 PostgreSQL 执行设施及选中 SQL 的准备/清理路径仅在此组合有限实测；完整 CORE、其他 SQL、LOB/XML/CallableStatement、其他元数据、DataSource/连接池/XA 及其他模式仍未确认。进一步验证需要约定版本/模式矩阵、驱动文件和专用 schema/最小权限，不能据此宣称全面支持。接入及诊断操作见[开发指南](development.zh-CN.md#kingbasees-候选接入)。

## 凭据、适配预检与清理证据

内嵌凭据在驱动使用前拒绝，包括历史 URL 的已保存探测、运行及 Worker 入口。公开投影递归遮盖 Oracle 凭据片段、查询/分号秘密属性（包括 SQL Server 大括号转义）及明确敏感 JSON 键。驱动 stdout/stderr 不进入公开通道；已知执行密码也从诊断/元数据字段移除。文本识别不能保证发现任意秘密。合法原始输入交给驱动，脱敏 URL 不用于执行。不自动扫描或改写历史记录：所有者应人工检查旧记录，必要时轮换凭据；这不表示已经发生泄漏。

`adapterExecution` 冻结 manifest 身份、版本范围、assetId、dialect 与引号规则。Web/CLI 共用 Java 预检，先检查再创建自动命名空间或执行 fixture DDL/DML。非法正则、受约束但不可解析的版本与不匹配均拒绝预检（`PREFLIGHT_FAILURE`），不编造用例失败。报告保留声明、产品/驱动观察版本及 MATCHED/REJECTED；可选端点缺省表示无该端限制。旧队列输入没有此字段时使用安装版本的 manifest，不重新判定历史报告。声明通过不证明未测目标受支持，本轮不新增 GaussDB/SQL Server/MySQL/Oracle 的真实目标验证声明。

`cleanupEvidence` 是 protocol 2 / report schema 2.0.0 的增量字段：SUCCESS、FAILED、UNKNOWN、NOT_EXECUTED，以及阶段、尝试/确认删除数量、保留对象、命名空间处置与安全 SQLState/vendorCode。清理失败影响运行结论（`CLEANUP_FAILURE`），不改真实用例统计。用例/容器/预检失败保留主要原因并附加清理证据；超时、取消和崩溃保留既有主终态。清理在原执行线程及 Worker deadline 内进行，绝不对仍存活的中断 JDBC 调用另开线程并发清理；确认删除的对象不重复 DROP。声明保留的命名空间不算失败残留。

正常运行只有存在成功清理证据才可成功。REST/SSE（`run.cleanup`）、持久化详情和重建 JSON 保留一致证据，新 Server 应用上下文重读后仍一致。历史缺失字段显示 UNKNOWN/未记录，不能推断成功。这些字段不授权大范围恢复删除。
