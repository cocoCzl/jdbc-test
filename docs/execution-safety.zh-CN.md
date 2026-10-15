# 执行安全

[English](execution-safety.md) | 简体中文

测试会执行 DDL/DML。使用专用且明确授权的数据库/schema，并在同意执行前核对冻结的范围。连接探测不执行测试 SQL，不要求写入范围。独立 Worker 不能隔离恶意 SQL 或驱动。

## 授权范围

新 Web 任务要求连接配置提供 `executionDatabase` 和/或 `executionSchema`，并明确同意破坏性测试。缺少范围时，入队前返回 `422 EXECUTION_SCOPE_REQUIRED`。

| 适配包 | 必填范围 |
|---|---|
| MySQL | 数据库；每次获取连接时选择并验证 catalog |
| PostgreSQL、SQL Server、GaussDB | 数据库和 schema；验证数据库身份与 schema |
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

## 凭据、适配预检与清理证据

内嵌凭据在驱动使用前拒绝，包括历史 URL 的已保存探测、运行及 Worker 入口。公开投影递归遮盖 Oracle 凭据片段、查询/分号秘密属性（包括 SQL Server 大括号转义）及明确敏感 JSON 键。驱动 stdout/stderr 不进入公开通道；已知执行密码也从诊断/元数据字段移除。文本识别不能保证发现任意秘密。合法原始输入交给驱动，脱敏 URL 不用于执行。不自动扫描或改写历史记录：所有者应人工检查旧记录，必要时轮换凭据；这不表示已经发生泄漏。

`adapterExecution` 冻结 manifest 身份、版本范围、assetId、dialect 与引号规则。Web/CLI 共用 Java 预检，先检查再创建自动命名空间或执行 fixture DDL/DML。非法正则、受约束但不可解析的版本与不匹配均拒绝预检（`PREFLIGHT_FAILURE`），不编造用例失败。报告保留声明、产品/驱动观察版本及 MATCHED/REJECTED；可选端点缺省表示无该端限制。旧队列输入没有此字段时使用安装版本的 manifest，不重新判定历史报告。声明通过不证明未测目标受支持，本轮不新增 GaussDB/SQL Server/MySQL/Oracle 的真实目标验证声明。

`cleanupEvidence` 是 protocol 2 / report schema 2.0.0 的增量字段：SUCCESS、FAILED、UNKNOWN、NOT_EXECUTED，以及阶段、尝试/确认删除数量、保留对象、命名空间处置与安全 SQLState/vendorCode。清理失败影响运行结论（`CLEANUP_FAILURE`），不改真实用例统计。用例/容器/预检失败保留主要原因并附加清理证据；超时、取消和崩溃保留既有主终态。清理在原执行线程及 Worker deadline 内进行，绝不对仍存活的中断 JDBC 调用另开线程并发清理；确认删除的对象不重复 DROP。声明保留的命名空间不算失败残留。

正常运行只有存在成功清理证据才可成功。REST/SSE（`run.cleanup`）、持久化详情和重建 JSON 保留一致证据，新 Server 应用上下文重读后仍一致。历史缺失字段显示 UNKNOWN/未记录，不能推断成功。这些字段不授权大范围恢复删除。
