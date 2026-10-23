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

| 数据库 | 驱动类 / URL 示例 | 必须授权的范围 |
|---|---|---|
| PostgreSQL | `org.postgresql.Driver` / `jdbc:postgresql://host:5432/test_db` | 数据库和已有 schema |
| MySQL | `com.mysql.cj.jdbc.Driver` / `jdbc:mysql://host:3306/test_db` | 数据库，即 JDBC catalog |
| Oracle | `oracle.jdbc.OracleDriver` / `jdbc:oracle:thin:@//host:1521/service` | 已有专用 schema；URL 选择服务而非 schema |
| SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerDriver` / `jdbc:sqlserver://host:1433;databaseName=test_db` | 数据库和已有 schema |
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

实测元数据为 **DM DBMS 8.1.3.162**、连接驱动 **8.1.2.192**、Driver API **8.1**、
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
DM 为候选适配，仅验证 DM DBMS 8.1.3.162＋驱动 8.1.2.192；独立复现不等于已确认厂商缺陷。
参见厂商 [DM8 JDBC 指南](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html)，
在线手册不是已留存的对应补丁版本手册。

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
