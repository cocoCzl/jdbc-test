# 使用指南

[English](usage.md) | 简体中文

先按[部署指南](deployment.zh-CN.md)或[本地开发](development.zh-CN.md#local-source-startup)启动网站。
操作流程：**上传驱动 → 配置连接 → 选择标准 JDBC 测试 → 执行 → 查看结果和报告**。

## 上传驱动

在**驱动管理 → 添加驱动**中，可选厂商预设，将驱动及所需依赖 JAR 一起上传为一个包。
预设的厂商、驱动类和 URL 前缀均可修改。加载检查在隔离 Worker 中进行，不连接数据库。
Driver API 主/次版本、Manifest 发行版本、JAR SHA-256 和连接元数据是独立来源；
文件名或可编辑的包版本标签不能证明实际发行版本。

制品不可原地替换，更新二进制或依赖需上传新包；修改驱动类会重置验证状态。
Server 将包保存在 `<data-dir>/drivers/`，不加载上传的类。
私有驱动不随项目打包、不提交，也不会静默下载。仅上传可信驱动；Worker 隔离不能防御恶意代码。

## 配置连接

在**连接管理 → 添加连接**中选择数据库和已验证驱动，填写真实 JDBC URL、独立用户名/密码及
授权范围，分别执行**测试连接**和**保存**。连接测试不等于保存；测试已保存设置前应先保存编辑。
探测会打开/关闭连接，尽可能读取元数据及配置，不执行测试 DDL/DML。
探测成功不代表具有写权限或用例兼容性。

保存的密码由平台 Master Key 加密。未提供密码时复用已保存密码；显式选择空密码才发送空字符串，
空格原样保留。禁止在 URL 中嵌入凭据。不要在 URL、连接属性、任务名称或报告中放秘密。
分享前检查诊断内容，文本脱敏不能识别所有任意秘密。

<a id="database-configuration"></a>
## 数据库配置

使用实际地址和独立凭据字段。database/catalog 与 schema 含义不同；下列授权范围要求用于写入任务，
不是仅建立连接的必要条件。

SQL Server 已停止新建、连接探测和执行支持，已有连接和历史任务/报告继续可读。

| 数据库 | 驱动类 / URL 示例 | 必须授权的范围 |
|---|---|---|
| PostgreSQL | `org.postgresql.Driver` / `jdbc:postgresql://host:5432/test_db` | 数据库和已有 schema |
| MySQL | `com.mysql.cj.jdbc.Driver` / `jdbc:mysql://host:3306/test_db` | 数据库，即 JDBC catalog |
| Oracle | `oracle.jdbc.OracleDriver` / `jdbc:oracle:thin:@//host:1521/service` | 已有专用 schema；URL 选择服务而非 schema |
| GaussDB | 使用所提供驱动要求的类名和 URL | 数据库和已有 schema；内置 PostgreSQL 基础设施 |
| KingbaseES | `com.kingbase8.Driver` / `jdbc:kingbase8://host:4321/test_db` | 数据库和已有 schema |
| 达梦 / DM | `dm.jdbc.driver.DmDriver` / `jdbc:dm://host:5236?schema=TEST_SCHEMA` | 大小写准确的已有 schema；不要求 database/catalog |

内置支持指平台适配，不代表完整 JDBC 兼容。版本、模式边界和未解决项见[已知限制](development.zh-CN.md#known-limitations)。
私有驱动可显式选择兼容方言，复用执行规则而不冒认其他数据库身份；所需资源由项目源码维护，
没有适配包或 JSON 导入入口。

### PostgreSQL 与 KingbaseES

PostgreSQL 原生过程测试需配置连接属性 `escapeSyntaxCallMode=callIfNoReturn`，可放在 URL 或属性中；
REF_CURSOR 必须在事务保持打开时读完。两阶段 XA 分支需要 DBA 在专用环境启用预备事务，
测试不会修改全局 `max_prepared_transactions`。

Kingbase 已验证适配范围仅为服务端 V009R002C016、oracle 模式、JDBC 产品版本 12.1 和驱动
V008R006B0001。数据库身份为 KingbaseES，SQL 基础设施及带引号名称采用 PostgreSQL 方言。
运行前自行核对构建号和模式；数字版本范围为空不代表支持所有版本。
详见[维护中的组合证据](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)。
账号需有 CONNECT 及 schema USAGE/CREATE，所选特性可能另需权限。

### Oracle

使用可达的 PDB 服务、专用本地测试账号/schema 和表空间额度，按用例提供
CREATE SESSION/TABLE/PROCEDURE/TYPE 权限。隐式结果准备还需访问 `DBMS_SQL.RETURN_RESULT`。
不要复用历史 Oracle 写入范围；未归因的 LOB 清单差异见[已知限制](development.zh-CN.md#known-limitations)。

普通标识符遵循 Oracle 大写语义。按字符集需求将兼容版本的 `orai18n` 与 `ojdbc` 一同上传，
SQLXML 还需 `xdb` 和 `xmlparserv2`。隔离加载警告 `ORACLE_I18N_MISSING` /
`ORACLE_XML_MISSING` 只是依赖提示，不是完整依赖解析。执行在写入前检查字符集；
XML 依赖缺失只阻塞相关用例，不阻塞独立测试。

### 达梦 DM8

选择**达梦**，不要选择 Oracle。可编辑预设提供 `dm.jdbc.driver.DmDriver` 和 `jdbc:dm:`。
连接格式为 `jdbc:dm://host:5236?schema=TEST_SCHEMA`；DM URL 路径也表示 schema，
不是 MySQL 的数据库路径。不要填写无实际作用的 database/catalog。
填写 schema 的实际存储大小写：未加引号创建的 `develop` 通常存储为 `DEVELOP`，
带引号创建的混合大小写名称保留原大小写。输入名称时不带引号分隔符。
当前范围校验仅支持字母开头、ASCII 字母/数字/下划线、最多 63 字符；其他名称暂不支持。
执行时每次取连接都会设置并核对 schema，DM 不套用 Oracle 的大写折叠。

账号需有已有专用 schema 的访问权，以及所选表、过程、类型的创建权限。
取消/超时准备需要可访问的 `DBMS_LOCK.SLEEP`；平台不会安装系统包或修改实例兼容模式、全局参数。
可选 Provider 类为 `dm.jdbc.driver.DmdbDataSource`、`dm.jdbc.driver.DmdbConnectionPoolDataSource`
和 `dm.jdbc.driver.DmdbXADataSource`，默认 Bean 属性为 `URL`、`user`、`password`。
类存在不代表相应测试通过。

历史基线（驱动 8.1.2.192）的元数据为 **DM DBMS 8.1.3.162**、连接驱动 **8.1.2.192**、Driver API **8.1**、
JDBC **4.0**，catalog 为 null；实例 `COMPATIBLE_MODE=0`、`CASE_SENSITIVE=1`。
驱动 `compatibleMode` 未显式设置，其有效默认值未核实，与实例兼容模式是不同概念。
已在专用 schema 验证真实 Web 上传/加载、连接探测/保存/编辑、执行、结果筛选、报告下载及后端重启后的持久化读取。
实测 FULL 完成全部 270 个已选用例：249 通过、21 失败，无跳过或中止；41 个本轮对象全部删除。
原失败中已有五项修复并分别真实复验：两项锁错误识别、NULL 返回准备、SQLXML 生命周期和 RowSet
更新验证。SQLXML 5/5 与原失败仅重叠 `sqlxml.set_string`；RowSet 9/9 按数据库精确存储值验证更新，
不要求不敏感游标立即反映更新。这些复验不改写原 FULL 统计。当前剩余十六项：十一项已有独立 JDBC
复现的兼容疑点，以及五项额外套件要求。
实测限制包括数组/空集合、Blob `InputStream.readAllBytes` 截断（长度/getBytes/小块读取完整）、开启
自动提交后的延迟提交、Provider 行为、过程元数据序号、按列序号获取批量生成键、`absolute(0)` 及
`DBMS_LOCK.SLEEP` 中断。数组类型命名/支持和该过程的取消支持仍待厂商确认，不能据此宣称普遍 JDBC
违约。四项类型元数据要求及过程内部更新计数暴露要求仍未满足；页面、报告和内部回归比较将其与
JDBC 违约分开跟踪。
上述历史证据仅适用于 DM DBMS 8.1.3.162＋驱动 8.1.2.192；独立复现不等于已确认厂商缺陷。
参见厂商 [DM8 JDBC 指南](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html)，
在线手册不是已留存的对应补丁版本手册。

独立的 **2026-11-04** 验收使用 **DM DBMS 8.1.3.162＋驱动 8.1.3.62**，
JAR SHA-256 为 `327bade775f4f15b97618bc9736a4625def52701cf53f5ef5ad72580797e6e45`，
Java 21、Driver API 8.1 / JDBC 4.0、`COMPATIBLE_MODE=0`、`CASE_SENSITIVE=1`、catalog 为 null。
本次不需要额外连接 Properties；驱动实际 `compatibleMode` 默认值仍未独立确认。
大小写错误的 schema URL 在 `ESTABLISHING_CONNECTION` 阶段返回 `3F000/-2103`；
只读元数据确认真实名称后使用精确拼写，应用不会自动折叠大小写或新建替代模式。

本次验证真实上传、隔离加载、草稿/已保存/编辑后的探测、密码加密保存、重启读取、
SSE 进度、结果分页和筛选、JSON 报告及 Chrome 中英文连接/报告页面。
CORE 选择 153：**151 通过、2 失败**；新的 FULL 选择 270：
**254 通过、16 失败、0 跳过、0 中止**。互斥分类为 254 项符合契约、8 项契约失败、
3 项其他 JDBC 结果未评估、5 项额外 suite requirement 未满足；原始 `NOT_EVALUATED=8` 包含这五项。
明确允许 unsupported、准备/环境问题及 Provider 缺失均为 0。
三项未评估异常为 setArray（`22000/-3301`）、absolute(0)（`HY107/6020`）和按列索引批处理生成键（`HY096/6007`），
尚未确认厂商归因。五项套件要求为 ARRAY/REF/STRUCT/ROWID 类型元数据存在性及内部过程更新计数暴露。
任务结束和 254 项通过不代表完整 JDBC 兼容，旧 FULL 及独立复测仍保留原统计。

cleanupEvidence 记录 **CORE 18/18、FULL 41/41** 次已登记创建均确认删除，没有遗留任务对象，保留已有模式。
912 个已有对象的名称、类型、对象 ID 和最后 DDL 时间与初始清单完全一致。
清理只操作 journal 精确登记对象，不执行 DROP SCHEMA、CASCADE、前缀扫描或业务对象 DDL/DML。
再次重启 Server 后仍可读取连接、历史任务和报告。验收临时平台与服务在验证结束后清理，保留用户提供的驱动和已有资源。

达梦仍为 **candidate**。运行前数据库版本边界仍精确为 8.1.3.162；驱动数值范围
8.1.2.192–8.1.3.62 保留旧端点并准入本次已观察的新版本，区间内其他驱动、其他 DM8 补丁、
兼容模式及驱动/JDK 组合均**未验证**。manifest 的 qualification 记录该边界，不提升状态或重写历史报告。
Provider 必须是公共类、匹配接口并提供公共无参构造器；缺少类、接口或构造器明确属于 prerequisite，
实际 Provider 的 JDBC 调用失败继续保留。

下面逐项核对本次组合的 capability。数值仍是历史声明，不是通过保证，也不会自动跳过标准调用。
关联用例数量有重叠，不能相加。三个 Provider 的默认声明为 false，本次通过显式配置使运行时标志为 true；
未通过修改 capability 隐藏失败。

| Capability | 历史声明 | 本次观察边界 |
|---|---|---|
| `array_type` | `true` | 6 通过；setArray、空集合失败；类型元数据为套件额外要求 |
| `ref_type` | `true` | 仅检查类型元数据（套件要求失败）；REF 对象行为未验证 |
| `struct_type` | `true` | 仅检查类型元数据（套件要求失败）；Struct 对象行为未验证 |
| `rowid_type` | `false` | 类型元数据套件要求失败；独立 ROWID 往返、绑定和更新用例通过 |
| `sqlxml` | `true` | 10 个关联用例通过，不推广到所有版本 |
| `rowset` | `true` | 9 个关联用例通过 |
| `lob_objects` | `true` | 10 个关联用例通过；Blob readAllBytes 往返失败 |
| `lob_locator_updates` | `true` | 1 个关联定位器更新用例通过 |
| `callable_inout` | `true` | 3 个关联用例通过；过程参数序号断言失败 |
| `ref_cursor` | `true` | 1 个原生 OUT 游标用例通过 |
| `batch_generated_keys` | `false` | 2 个关联用例通过；按列索引请求生成键失败 |
| `generated_keys_by_index` | `true` | 按列索引批处理路径失败（HY096/6007），不推广到其他路径 |
| `multiple_result_sequences` | `true` | 内部过程更新计数暴露属于未满足的额外套件要求 |
| `data_source` | `false` | 显式配置公共 Provider：8 个关联用例通过，getParentLogger 返回 null 断言失败 |
| `connection_pool` | `false` | 显式配置公共 Provider：1 通过，替换句柄后旧句柄失效断言失败 |
| `xa_data_source` | `false` | 显式配置公共 Provider：4 个关联用例通过 |
| `statement_cancel` | `true` | DBMS_LOCK.SLEEP 中断断言失败，其他 SQL 形态未验证 |
| `query_timeout` | `true` | 2 个关联用例通过；实际 DBMS_LOCK.SLEEP 超时断言失败 |
| `network_timeout` | `true` | getter/setter 往返通过；真实网络阻塞截止时间未验证 |
| `parameter_metadata` | `true` | 9 个关联用例通过；Callable 参数序号另有失败 |
| `updatable_result_set` | `true` | 当前目录没有直接的可更新 ResultSet 契约覆盖，未验证 |
| `scrollable_result_set` | `true` | 2 个关联滚动窗口用例通过；absolute(0) 另有失败 |

### 已记录 FULL 的逐项归因

以下是对上述 254/270 FULL 的**独立复现与人工审阅**，不替换原始失败、分类或统计，
也不是新一轮 FULL。CORE 的两项失败 `connection.enable_auto_commit_commits_pending_work` 和
`result_set.absolute` 都包含在下面。范围仍为 DM DBMS 8.1.3.162、驱动 8.1.3.62、Java 21。
独立连接的公开驱动配置及连接字段均观察到 `compatibleMode=0`；这补充了原 FULL 未采集的证据。
当前在线达梦指南描述 `autoCommitSpecCompliant`，但该 JAR 的公开 Configuration 集合未提供该参数；
不能把在线手册的默认值直接套用到本补丁，也不能假设传入未知属性就修复了事务行为。

表中的“契约失败”“未评估”均指**历史自动分类**；人工定位到具体 API 不会回写它。

| Case ID | 测试方法 | 预期与独立观察 | 归因及仍缺证据 |
|---|---|---|---|
| `connection.enable_auto_commit_commits_pending_work` | `TransactionBehaviorTest.testEnableAutoCommitCommitsPendingWork` | 切换 true 应立即提交；实际标志 true、独立连接不可见，写连接再执行 SELECT 后才可见 | 契约失败；延迟提交的驱动疑点，需对应补丁事务说明 |
| `statement.actual_query_timeout` | `ExecutionControlTest.testActualQueryTimeout` | 1 秒超时应约束 execute；5 秒 SLEEP 正常完成（5007 ms） | 契约失败；该 SQL 形态的执行控制疑点，需系统包可中断性及补丁说明 |
| `statement.cancel_running_statement` | `ExecutionControlTest.testCancelRunningStatement` | 原测试要求中断；508 ms 时调用 cancel 并正常返回、执行仍至 5013 ms | 契约失败；cancel 契约以 DBMS/驱动支持中断为条件，尚缺 SLEEP 的对应版本支持证据，不宣称通用违规 |
| `datasource.get_parent_logger` | `DataSourceTest.testGetParentLogger` | JDBC 4.1 应返回 Logger 或明确可选不支持；实际返回 null | 历史契约失败；当前归因未评估：该 API 始于 Java 7/JDBC 4.1，驱动仍宣称 4.0。null 不符合 4.1 返回要求，但本补丁的 4.1 适用性/支持承诺未确认 |
| `datasource.pooled_replacement_invalidates_previous_handle` | `PooledConnectionBehaviorTest.testPooledReplacementInvalidatesPreviousHandle` | 新逻辑句柄应关闭旧句柄；实际两次返回同一代理、旧句柄未关闭且仍能查询 | 契约失败；连接池句柄驱动疑点，规范依据为 JDBC §11.4 |
| `blob_clob.blob_stream_round_trip` | `BlobObjectTest.testBlobStreamRoundTrip` | 完整读取 307200 字节；readAllBytes 仅 16384，随后仍可读取 | 契约失败；已定位 DmdbInputStream 的零长度 read 错返 -1（应为 0）；getBytes/小块读取完整，不能替换原断言绕过 |
| `callable_statement.procedure_parameter_metadata` | `ProcedureBehaviorTest.testProcedureParameterMetadata` | 参数序号应为 1/2/3；实际为 0/1/2，方向仍为 IN/INOUT/OUT | 契约失败；实际过程输出 17/14 正确，疑点是元数据序号，不能自动 +1 |
| `advanced_type.empty_array` | `AdvancedTypeTest.testEmptyArray` | 非 NULL 空集合应物化为空 Java 数组；包装 Array 非空、wasNull=false，但 getArray 返回 null | 契约失败；驱动物化疑点；数组 ResultSet 为零行并不能代替 Java 数组契约 |
| `advanced_type.set_array` | `AdvancedTypeTest.testSetArray` | 原调用在 createArrayOf(INTEGER) 即报 22000/-3301，未到 setArray | 历史未评估；setter fixture 已修复，正式 CUSTOM 通过绑定、写入 1 行及精确读回 [10,20,30]；内置类型工厂/元数据命名疑点仍保留 |
| `prepared_statement.batch_keys_by_column_index` | `BatchBehaviorTest.testBatchKeysByColumnIndex` | 指定 ID 应返回其键；批处理成功却返回 NAME/VARCHAR，getLong 才报 HY096/6007 | 未评估；已定位返回列疑点，按名称 ID 对照正确；索引基准的内部原因仍需确认，不改成列名或索引 0 绕过 |
| `result_set.absolute` | `ResultSetTest.testAbsolute` | actual type=1004 且声明支持滚动；仅 absolute(0) 报 HY107/6020 | 未评估；无表的字面量查询也重现，排除 fixture 与 forward-only；标准要求返回 false 并移至 beforeFirst |
| `jdbc_type.array_type_metadata` | `JdbcTypeCoverageTest.testArrayTypeMetadata` | 套件要求 getTypeInfo 包含 2003；实际缺失 | 未评估/SUITE；类型存在性是额外要求，不是所有数据库必须支持 ARRAY |
| `jdbc_type.ref_type_metadata` | `JdbcTypeCoverageTest.testRefTypeMetadata` | 套件要求包含 2006；实际缺失 | 未评估/SUITE；不能据此否定全部 REF 行为 |
| `jdbc_type.struct_type_metadata` | `JdbcTypeCoverageTest.testStructTypeMetadata` | 套件要求包含 2002；实际缺失 | 未评估/SUITE；不能据此否定全部 Struct 行为 |
| `jdbc_type.row_id_type_metadata` | `JdbcTypeCoverageTest.testRowIdTypeMetadata` | 套件要求包含 -8；实际缺失 | 未评估/SUITE；独立 ROWID 行为用例通过不改变类型元数据要求 |
| `statement.result_sequence` | `MultipleResultsTest.testResultSequence` | 两个结果 11/22、存储值 1、遍历终止均通过，但 updateCounts=[] | 未评估/SUITE；额外要求过程内部更新计数 1 可见，JDBC 不保证每条内部语句暴露计数 |

上述三项普通 SQL 异常的自动未评估原因是：分类器不能仅凭 SQLState 断言厂商违约。
现已补齐失败 API、游标类型、类型名及返回键列元数据；仍缺对应补丁的厂商说明/确认，
不通过全局 SQLState 规则强行改变归因。其他八项断言也不自动等于已确认厂商缺陷。
本次独立可写复现仅创建五个 journal 对象，全部确认删除；912 个既有对象元数据前后一致。
只读复现没有创建测试对象；未重跑 FULL，也未修改 capability、放宽断言或跳过用例。

项目修复：达梦 setter 用例先准备原生集合，再向标准工厂传入精确引用、限定的 fixture 类型名；
无论 execute 是否抛错都读取取消线程结果，保留原始主异常与 phase，取消/线程退出失败作为附加证据。
两处线程等待上限分别为 5 秒。Worker 遍历 cause、nextException、suppressed，以对象身份去重，
最多输出 10 条、队列最多 16 项，消息/SQLState 先脱敏再限制为 2000 字符；附加分支不提供主 SQLState。
getParentLogger 只修正 API 引入版本为 4.1，保留驱动实际 JDBC 4.0 和可选不支持策略，不按版本跳过。
中文把 suite requirement 明确称为“套件要求”。UI、JSON 报告和回归比较继续保留 FAILED、
NOT_EVALUATED 与 SUITE 标记；5 是 8 个未评估中的子集，不能重复相加，也不算 JDBC 违规或通过。
已有通用回归及新增五项双语呈现测试核对这一边界。

正式 setter 定向复测单独记录为 `f816ac85-c189-47c9-90df-4a799fc01d89`：CUSTOM 选中 1、通过 1，
无失败/跳过/中止；cleanup SUCCESS 3/3（两个类型、一个表），912 个既有对象元数据一致。
原正式路径在 deferred fixture 之前传入字面量 `INTEGER`，不是名称生成器丢失 Schema、引号或大小写。
此前独立程序使用一个 VARRAY、简单 id/array 表和精确引用集合名，未读回新写入值；正式路径仍使用
既有两个集合类型、identity/name 表、数据和全部断言，两条路径都未改变自动提交或事务模式。
仍调用 createArrayOf 与 setArray，其他适配器保留工厂先于 fixture 的路径。
这是 setter fixture 修复，不能证明集合名符合 SQL 元素类型语义：Java 21 Connection 的 typeName
契约描述元素 SQL 类型并引用 Array.getBaseTypeName；INTEGER 失败及驱动返回未引用集合名仍是
独立疑点，需要匹配补丁的资料，不能用 setter 通过消除这些证据。

当前归因与历史标签分开：set_array 的 fixture 已修复并正式复测；get_parent_logger 的 4.1 适用性
仍未评估；cancel 仍缺包/驱动中断支持证据。其余驱动行为疑点，包括纯查询 absolute(0) 和错误键列，
仍不是已确认的厂商缺陷；五项套件要求不变。本次收尾没有正式复跑其他用例，人工观察不替代正式结果，
也不形成新的汇总通过率。

精简证据与完整本地材料保存在被忽略的 `.local/qa/dm-attribution/`：`INDEX.json` 记录 SHA-256；
`attribution.json` 逐项分开原始异常/分类、当前归因、项目修复、正式复测与剩余不确定性；
`formal-report.json`、`formal-object-ledger.yaml` 保留新结果与对象登记；`previous/` 原样保留此前复现；
`acceptance/` 保留历史报告；`REPRODUCTION.md` 描述只从私密环境读取配置、限制专用作用域的复现方法。
这些本地材料不随 Git 分发，正式文档只保留已审阅结论及官方规范引用。

契约依据为 Java 21 的 [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html)、
[Statement](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html)、
[ResultSet](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html)、
[DatabaseMetaData](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html)、
[Array](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Array.html)、
[CommonDataSource](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/CommonDataSource.html)、
[InputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStream.html) 和
[JDBC 4.3 规范 §11.4](https://download.oracle.com/otn-pub/jcp/jdbc-4_3-mrel3-spec/jdbc4.3-fr-spec.pdf)。
当前在线 DM 指南不是本次 DBMS/驱动补丁的固定版本手册；尚未取得该组合的完整发行说明及厂商归因。
达梦继续保持 candidate，以上结果不推广到其他版本或兼容模式。

## 创建与执行任务

在**新建任务**选择已保存连接，设置可编辑任务名称，查看当前目录并选择用例。
FULL 为当前全部适用用例，CORE 为维护中的子集，CUSTOM 用于缩小选择。
可以按模块、单项和搜索选择；数量来自当前目录，不固定为历史总数。
能力声明为 false 不会取消标准测试的选择。

核对数据库/schema、排除项、Provider 和时间预算后，明确授权破坏性测试。
登录用户名本身不构成范围授权。SQL 权限、依赖 JAR 和准备资源在准备阶段检查；
准备不足不等于驱动缺陷。入队后冻结输入，后续修改连接不改变该任务。
某特性前提不足也不能证明其他独立特性不支持。

驱动自身的 DataSource、连接池和 XA 测试需在连接中配置实际 Provider 类名及公共 Bean setter。
Provider 缺失属于执行前提不足，不算通过。预算覆盖准备、执行和清理；
取消或 JDBC 调用不响应可能留下未完成工作，应检查终止原因与清理证据。

<a id="results"></a>
## 结果与报告

任务完成描述执行和报告处理结束。普通用例失败应显示**已完成 · 存在未通过用例**；
执行、报告异常或清理失败属于异常结束，并保留具体原因。列表、详情、筛选和报告使用相同执行结论。
进度全部处理完不等于兼容性通过率。

| 用例状态 | 含义 |
|---|---|
| PASSED / FAILED | 已记录完成的用例，仍需查看独立契约结论 |
| SKIPPED / ABORTED | 显式跳过/中止，包括精确允许的不支持；不算功能通过 |
| NOT_RUN / INCOMPLETE | 任务终止时，已选但未开始 / 已开始但未完成 |

按状态和模块筛选结果，查看阶段、SQLState/厂商码、观察值及要求，并下载 JSON 报告。
`completed` 包含显式跳过，`started` 仅计算真实开始；终态记录不等于标准 API 已调用。
JDBC 结论、SUITE 额外要求、准备和环境问题分别保留，见 [JDBC 判定参考](jdbc-capability-contracts.zh-CN.md)。
持久化重建下载会标明来源及 `workerReportValidated`。历史报告不追溯改写，
后续复验也不与原报告拼接成新的 FULL 通过率。

<a id="cleanup"></a>
## 授权范围与清理

仅使用明确授权的专用数据库/schema。范围名称须以 ASCII 字母开头，仅含字母、数字、下划线，最多 63 字符。
Web 不创建测试 schema 或 Oracle 用户。每次取连接核对范围，对象名称加引号并限定所属范围。
每个任务使用随机前缀和归属日志，只删除确认由该任务创建的对象；
不预删冲突对象、不遍历清空 schema、不仅凭前缀删除，也不强制 CASCADE。
隐式依赖由父对象管理，元数据发现本身不构成独立删除授权。

检查 `cleanupEvidence` 的 **SUCCESS、FAILED、UNKNOWN、NOT_EXECUTED** 及残留/不确定对象。
强制终止可能留下中间状态日志；无证据不等于清理成功，任务对象清理成功也不单独证明既有对象未受影响。
没有自动宽泛恢复删除。手工删除前需核实精确归属。
已有授权 schema 会保留；CLI 创建的命名空间也会在对象清理后保留，另行检查为空再删除。
不要修改全局模式或已有业务对象。
