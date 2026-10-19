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

CLI 和 Worker 共用适配声明；34 项布尔值仍需合法，但不再根据 false 禁用标准能力验证。Provider 缺失、SQL 准备和配套 JAR 不足单独呈现执行前提不足。FULL 默认选择当前全部 270 项标准测试；当前无真实厂商扩展。CORE 显式维护旧 153 项，CUSTOM 支持精确选择。新结论与执行状态分离，允许不支持须由具体 API 契约证实，历史结果不重解释。详见[选择与结果语义](development.zh-CN.md#测试范围与执行语义选择版本-2)。

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

这些历史选择存在重叠。当时因能力配置跳过的记录只表示旧门控生效，不证明数据库不支持；新任务不再以这些布尔值禁用标准能力验证。具体原因见结果页或原始 JSON。这些结果覆盖本地生产 JAR 部署，不代表 Docker 镜像验收、其他驱动版本或发布授权。可丢弃容器的回归不能替代真实目标组合验收。默认 Maven/CI 测试不连接真实目标。

## KingbaseES 平台验证

KingbaseES 已提升为正式平台适配，仅限服务端 V009R002C016、oracle 模式、JDBC 数据库版本 12.1、驱动 V008R006B0001。与 PostgreSQL、MySQL、Oracle 一致，正式表示平台适配及结果可解释，不要求所有 JDBC 用例通过；本次不改变断言、能力开关、终态规则或历史结果。

2026-10-19 真实 FULL 任务有 270 项终态结果记录：261 项实际调用契约，另 9 项因准备直接缺失（4 项）或连带阻塞（5 项）未调用契约；原状态为 252 PASSED、9 FAILED、9 ABORTED。历史未运行/未完成计数字段均为 0，不表示全部契约均已调用。Server、隔离 Worker、平台 PostgreSQL 报告链路及后续浏览器流程已有实际验证。清理 SUCCESS，后查表/例程均为 0，专用 schema 已删除，未决 prepared transaction 为 0。最初独立 Worker 的 CORE 超时是另一条历史观察，后续 FULL 完成不抹去超时，也未证实首次 getTypeInfo 较慢的原因。

九项中止由过程、REF cursor、ROWID、多结果 SQL 资产缺失/阻塞引起，属于平台准备范围限制，不是驱动失败或通过。两项 XA 准备失败时 max_prepared_transactions=0，按环境前提不足解释，原始 CONTRACT_VIOLATION 标签不改写。LOB 边界/局部写入、DataSource logWriter 观察保留供驱动/契约复核；REF 元数据、按列下标批量键、未实现 holdability 须结合可选 API 契约，不能笼统认定驱动缺陷。正式状态不认证这些未执行的准备路径，也不推广至其他版本、模式或驱动。

[脱敏组合证据](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)保留实际统计、全部 18 项非通过结果、归因评审及任务源摘录。原 ignored 报告目前不可用，摘录哈希不是原始 Worker 报告哈希。后续四库验收未全部完成：MySQL、PG、Kingbase 已执行 FULL，Oracle 仅驱动/连接探测。夹具截图和单元测试不作为额外实库证据。接入及最小诊断见[开发指南](development.zh-CN.md#kingbasees-接入)。

## 凭据、适配预检与清理证据

内嵌凭据在驱动使用前拒绝，包括历史 URL 的已保存探测、运行及 Worker 入口。公开投影递归遮盖 Oracle 凭据片段、查询/分号秘密属性（包括 SQL Server 大括号转义）及明确敏感 JSON 键。驱动 stdout/stderr 不进入公开通道；已知执行密码也从诊断/元数据字段移除。文本识别不能保证发现任意秘密。合法原始输入交给驱动，脱敏 URL 不用于执行。不自动扫描或改写历史记录：所有者应人工检查旧记录，必要时轮换凭据；这不表示已经发生泄漏。

`adapterExecution` 冻结 manifest 身份、版本范围、assetId、dialect 与引号规则。Web/CLI 共用 Java 预检，先检查再创建自动命名空间或执行 fixture DDL/DML。非法正则、受约束但不可解析的版本与不匹配均拒绝预检（`PREFLIGHT_FAILURE`），不编造用例失败。报告保留声明、产品/驱动观察版本及 MATCHED/REJECTED；可选端点缺省表示无该端限制。旧队列输入没有此字段时使用安装版本的 manifest，不重新判定历史报告。声明通过不证明未测目标受支持，本轮不新增 GaussDB/SQL Server/MySQL/Oracle 的真实目标验证声明。

`cleanupEvidence` 是 protocol 2 / report schema 2.0.0 的增量字段：SUCCESS、FAILED、UNKNOWN、NOT_EXECUTED，以及阶段、尝试/确认删除数量、保留对象、命名空间处置与安全 SQLState/vendorCode。清理失败影响运行结论（`CLEANUP_FAILURE`），不改真实用例统计。用例/容器/预检失败保留主要原因并附加清理证据；超时、取消和崩溃保留既有主终态。清理在原执行线程及 Worker deadline 内进行，绝不对仍存活的中断 JDBC 调用另开线程并发清理；确认删除的对象不重复 DROP。声明保留的命名空间不算失败残留。

正常运行只有存在成功清理证据才可成功。REST/SSE（`run.cleanup`）、持久化详情和重建 JSON 保留一致证据，新 Server 应用上下文重读后仍一致。历史缺失字段显示 UNKNOWN/未记录，不能推断成功。这些字段不授权大范围恢复删除。
