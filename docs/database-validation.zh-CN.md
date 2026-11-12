# 数据库验证记录与限制

[English](database-validation.md) | 简体中文

[文档导航](README.md) · [中文首页](../README.md)

目录：[组合与公开证据](#recorded-combinations) · [当前限制与未解决事项](#known-limitations) · [达梦历史观察、当前实现与复测](#dameng)

本页集中维护历史组合、当前限制和后续定向复测；不重写报告，不按每轮或每种数据库增设记录页。
操作步骤见[使用指南](usage.zh-CN.md#database-configuration)，判定口径见[JDBC 判定参考](jdbc-capability-contracts.zh-CN.md)。

<a id="recorded-combinations"></a>
## 组合与公开证据

活动索引为 [catalog.json](../adapters/catalog.json)。以下均为限定范围的记录，不是当前全部用例通过证明。

| 数据库 | 状态 | 记录范围 / 来源 |
|---|---|---|
| PostgreSQL | [official](../adapters/postgresql/adapter.json) | 15.2 / 42.7.2；[evidence](../adapters/postgresql/evidence/postgresql-15.2-driver-42.7.2.json) |
| MySQL | [official](../adapters/mysql/adapter.json) | 8.0.30 / 8.3.0；[evidence](../adapters/mysql/evidence/mysql-8.0.30-driver-8.3.0.json) |
| Oracle | [official](../adapters/oracle/adapter.json) | 19.3.0.0.0 / 21.13.0.0.0；[evidence](../adapters/oracle/evidence/oracle-19.3-driver-21.13.json) |
| KingbaseES | [official](../adapters/kingbase/adapter.json) | V009R002C016 / oracle / JDBC 12.1 / V008R006B0001；[evidence](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json) |
| GaussDB | [candidate](../adapters/gaussdb/adapter.json) | 无 validated_combinations；PostgreSQL 基础设施复用，不推广至全部 GaussDB/openGauss 产品 |
| 达梦 / DM | [official](../adapters/dm/adapter.json) | 8.1.3.162 / 8.1.3.62 / 模式 0 / Java 21；[脱敏证据](../adapters/dm/evidence/platform-8.1.3.162-driver-8.1.3.62.json)；[接纳范围](#dm-platform-acceptance) |

`official` 仅为项目内适配状态，不是厂商认证；`supported_versions` 只约束准入，capability 是声明。
Kingbase 的服务端构建号、模式与 JDBC 产品版本是不同字段；未设置数字版本界限不代表全版本支持，构建/模式也不是运行时强制检查。
Kingbase 的 manifest `qualification.asset_policy` 记载后续九例定向复测：8 项符合契约、1 项过程更新计数断言失败。
公开 FULL evidence 的 `preparation_followups` 仍保留当时的 `pending`；它不是后续复测的原始报告，不用后续记录重写 FULL。
早期 evidence 摘要中的 `target_outcome` 不代表当前套件或认证，不能跨数据库排名；部分原始报告已缺失，详见下表。

<a id="known-limitations"></a>
## 当前限制与未解决事项

以下是仍有效的边界，不是完整认证矩阵。保留原断言和历史报告，定向复验不改变原 FULL 通过率。

| 范围 | 当前边界 / 后续条件 |
|---|---|
| PostgreSQL 结果序列 | 平台准备缺口，尚无满足原生过程隐式结果序列契约的等价资源，不能用游标名列表或客户端多语句替代。 |
| PostgreSQL / Kingbase 两阶段 XA | 实测 max_prepared_transactions=0 阻塞 prepare 相关分支；需 DBA 提供已启用的专用环境，其他独立分支继续。 |
| Kingbase 元数据耗时 | 早期 getTypeInfo 调用耗尽任务预算，后续更广任务完成，原因仍未知；归因驱动挂起前，仅在有界隔离进程复现该调用。 |
| Kingbase ROWID | 疑似驱动 NULL 映射问题：SQL NULL 返回非空 KbRowId，wasNull=true，getBytes 抛 NPE。未到达的绑定/更新/删除/批量仍未验证。 |
| PostgreSQL DataSource log writer | 疑似驱动行为，保留断言和最小证据，等待具体方法契约及厂商复核。 |
| PostgreSQL holdability、按索引生成键 | 证据不足，NOT_EVALUATED；PSQLException/SQLState0A000 本身不构成允许不支持。 |
| Kingbase / MySQL 过程更新计数 | 内部语句计数可见性是套件额外要求而非通用 JDBC 保证，未满足仍保留 SUITE 范围失败。 |
| MySQL 可选路径 | 已观察到的 ARRAY/ROWID/SQLType 注册调用允许不支持，不验证下游 Array 方法、绑定和原生游标输出；保留具体方法证据，不宣称整个接口支持情况。 |
| Oracle 已记录执行失败 | 参数序号、取消/查询超时、批量生成键及 SQLXML 流文本仍需逐方法归因/复验，新增准备资源不解决这些既有观察。 |
| Oracle 资源 | 原生数组、多结果准备及嵌套依赖清理已有自动化检查，尚未真实 Oracle 复验。按列名批量键 NPE 归因、终态页面/筛选/下载按钮复核仍待办；需可达授权 PDB、额度与权限。 |
| Oracle LOB 清单 | 历史运行后两个既有字典条目缺失，原因与历史父映射未知；既未证实业务数据丢失，也未证实无影响。合成归属测试不解释或修复差异。该写入范围保持关闭，新写入需独立专用账号/schema。归因需已有运行前映射/备份/审计/redo/采集身份，不能盲目重跑 FULL。 |
| 历史证据 | 部分早期 MySQL/Kingbase 原 FULL 报告已不可得；脱敏摘录不等于原报告，不重建或认证缺失历史。adapters 内维护的证据保留。 |
| 达梦 | official 的精确范围见[接纳记录](#dm-platform-acceptance)；历史 DM DBMS 8.1.3.162＋驱动 8.1.2.192 的 FULL 保留 249/21 及独立复测。2026-11-04 驱动 8.1.3.62 的独立 CORE 为 151/2、FULL 为 254/16：八项契约失败、三项其他 JDBC 结果未评估、五项额外套件失败。任务对象清理为 18/18、41/41；912 个已有对象元数据前后一致。驱动区间内其他版本和其他模式/补丁未验证；逐项能力审计见[达梦记录](#dm-capabilities)，独立观察不等于已确认厂商缺陷。 |
| 平台启动验证 | Windows ACL/PowerShell 进程行为和 IDEA UI 未验证；标准 Docker 源码构建在镜像仓库认证超时后没有成功验证记录，替代本地镜像运行不证明源码构建通过。 |

准备不足不等于驱动缺陷，允许不支持不算功能通过。REF/ROWID 类型存在、LOB 自动持久化、
非法操作后恢复和内部更新计数要求分别定界，见 [JDBC 判定参考](jdbc-capability-contracts.zh-CN.md)。

SQL Server 已停止新建连接、探测和执行；只保留历史连接、任务、原始报告与结论的读取。

<a id="dm-platform-acceptance"></a>
## 达梦平台适配接纳（2026-11-12）

**结论：正式接纳为 official，仅登记 DM DBMS 8.1.3.162＋驱动 8.1.3.62。**
边界为 `COMPATIBLE_MODE=0`、`CASE_SENSITIVE=1`、Java 21、JDBC 声明 4.0；驱动 SHA-256 为
`327bade775f4f15b97618bc9736a4625def52701cf53f5ef5ad72580797e6e45`。
`official` 表示项目正式维护的平台适配，不代表厂商认证、所有用例通过或其他版本/模式获准。
[脱敏证据摘要](../adapters/dm/evidence/platform-8.1.3.162-driver-8.1.3.62.json)分别保留原报告统计、来源哈希、人工审阅和后续 CUSTOM。

### 规则与证据质量

此前 candidate 的实际状态是：已有实库记录，但未登记正式组合，也没有已公开、可核对的接纳摘要；
数组 setter 准备已修复，契约前提仍需准确表达。不能把这些事实简化为“没有实测”或“必须修复全部失败”。
[开发指南](development.zh-CN.md#adapter-acceptance)区分已有规则、代码最低校验和本次明确的维护标准。
本轮未改变通用资格规则、capability、用例选择、SQL 或 JDBC 断言。

本地 INDEX 的 79 项材料均按 SHA-256 核对，无缺失/不匹配。可取得 8.1.3.62 CORE/FULL 的持久化
Worker 报告、set_array CUSTOM 原始报告及 journal；公开文件是经过审阅的脱敏摘要，**不是原始报告**。
原材料含私密连接环境，不随 Git 分发；公开来源标识仅用于核对，不制造本地下载链接。
8.1.2.192 的 249/21 FULL 原件在本轮审阅材料中不可得，保留历史文字记录，不重建，也不登记为正式组合。
8.1.3.62 的 JAR 当前不在本地，指纹来自留存记录；本轮没有声称重新计算了该制品的哈希。

### 平台链路验收

| 链路 | 可核对依据与结果 | 边界 |
|---|---|---|
| 上传、依赖、隔离及身份 | 历史 Web 操作记录；CORE/FULL 元数据 MATCHED，真实产品/驱动独立；本轮所给旧 JAR 的隔离验证和只读探测成功 | 没有新做 Web 验收；隔离不是恶意代码沙箱 |
| 探测、保存、编辑及重启读取 | 历史操作记录与持久化报告；ConnectionService 的 DM 生命周期测试覆盖精确 schema 与加密凭据 | 本轮未重启已有网站 |
| database/schema 与授权 | ExecutionScope/ScopedDataSource 保留精确名称、不要求 catalog；已有模式不删除 | 本轮实测实际 schema 为 DEVELOP；不自动把输入折叠为大写 |
| 选择、准备及标准调用 | FULL 270 项全部开始/完成；数组 fixture 回归与独立 CUSTOM 验证 setter 写入及精确读回 | setter 成功不证明 createArrayOf 的元素类型名契约成立 |
| 超时、取消、异常和终态 | 有界 Worker、取消线程观测及原异常保留；清理失败/未知不能产生成功清理证据；本轮针对性修复契约适用性表达 | DM SLEEP 可中断性未确认，调用/断言仍执行，失败不变通过 |
| 页面、报告及持久化 | 原 FULL source=PERSISTED_WORKER_EVENTS，workerReportValidated=true；逐项记录、汇总、分页/报告历史验收与呈现测试 | 本轮新增分类用 fixture 验证，不宣称新实库 FULL |
| 归属与清理 | CORE 18/18、FULL 41/41、CUSTOM 3/3 分别成功；仅反序清理 journal 中确认创建的精确对象 | 不 DROP SCHEMA、不 CASCADE、不扫描前缀；未知 CREATE 保持可见 |
| 版本与声明 | 仅登记有原始报告的 8.1.3.62 组合，revision 更新为 1.2.0 | 数字准入区间不等于全部已验证 |

后续归因和 CUSTOM 各有一对 912 项既有对象元数据清单，本轮实际比对均相同；它们不是原 FULL
运行前清单的替代品，也不证明表内所有数据相同。FULL 的 41/41 清理由其独立报告/journal 支撑。
本轮还以用户提供的旧 JAR（SHA-256 `afc60f332c8b79d514c1c19d2b58164c3e42112fea455eae68c9d56716834a97`）
只读核对 DM DBMS 8.1.3.162、驱动 8.1.2.192、JDBC 4.0、实际 schema `DEVELOP`；未执行新 DDL/DML。

### 未解决事项与是否阻止接纳

| 类别 / 事项 | 证据、影响范围与下一步 | 阻止本次接纳？ |
|---|---|---|
| 平台缺陷：前提不明仍直接判违规 | 原 FULL 的 logger/cancel 标签不变；当前新增显式适用性失败，保留原断言为 cause，FAILED / NOT_EVALUATED；针对性测试覆盖 Worker 和 CLI | 已修复，不阻止；不追溯重分类 |
| 准备/契约：数组工厂 typeName | INTEGER 工厂失败与集合名工厂/基础类型名疑点独立保留；setter 已完成单独 CUSTOM 1/1 | 不阻止限定的 setter 验收；工厂语义仍待匹配补丁资料，不宣称该工厂符合契约 |
| 契约适用性：getParentLogger | JDBC 4.1 引入；实际声明 4.0、返回 null；本补丁的高版本承诺未知 | 不阻止；继续调用且失败/未评估，不判合法 unsupported；4.1+ null 仍是规范断言失败 |
| 契约/环境：cancel 与 timeout | SLEEP(0) 仅证明包/权限可用；cancel 需证明可中断性，timeout 正常完成仍保留执行控制疑点 | 不阻止；前提未知单列，不能宣称所有 SQL 均无法取消/超时 |
| 被测驱动行为疑点 | 自动提交、连接池句柄、Blob、参数序号、空数组、生成键及 absolute(0) 保留原结果和独立观察，详见逐项归因 | 不阻止；不是已确认厂商缺陷，不补偿行为；未证明的归因不提升为确定结论 |
| 套件额外要求 | 四项类型存在性及过程内部更新计数；原失败保留 SUITE，5 是 NOT_EVALUATED=8 子集 | 不阻止；额外要求未满足不等于通用 JDBC 违规或通过 |
| 环境前提 | Provider 公共类/构造器、schema 权限、可用系统包；其他模式、驱动区间中间版本未验证 | 登记范围外不阻止；新环境须先满足授权和准备 |
| 证据不足 | 旧 249/21 FULL 原件缺失；8.1.3.62 没有本轮再实测；部分补丁资料未知 | 不阻止已核对的历史组合；不登记旧版本，也不声称当前实库全链路复验 |

### 资格与后续验证

CLI 新运行在 official、正式类别、运行时身份符合 manifest 正则且完整版本标记匹配后才能 formal_eligible；本次仅 8.1.3.62
组合满足登记条件，所给 8.1.2.192 仍可普通执行，不能因适配晋级而取得正式组合资格。
版本可位于产品描述中，但不接受数字、字母、点、连字符或加号扩展出的其他版本标记。

| 维度 | 正式维护的验证范围 | 程序强制检查 |
|---|---|---|
| 数据库版本 | 8.1.3.162 | Java 准入范围检查；CLI 与登记组合的完整版本标记匹配 |
| 驱动版本 | 8.1.3.62 | Java 准入范围仍允许 8.1.2.192–8.1.3.62；CLI 正式资格仅匹配登记的 8.1.3.62 |
| 驱动 SHA-256 | 本节所列指纹 | 采集实际制品指纹，但 formal_eligible 不与登记指纹比对 |
| COMPATIBLE_MODE | 0 | formal_eligible 不校验实例模式；回归比较的模式证据来自操作者声明 |
| CASE_SENSITIVE | 1 | formal_eligible 不核验实例标志；精确 schema 校验不等于实例标志校验 |
| Java | 21 | Maven 要求 JDK 21；formal_eligible 不与组合的 Java/补丁版本比对 |

因此 `formal_eligible=true` 不证明整个精确组合已由程序核验。报告保留组合声明、运行时身份/版本、
Java 信息及制品证据；声明不冒充已强制核验的观测。操作者须核对未强制的边界，比较另需匹配的版本化环境证据。
旧报告和旧 adapterSnapshot/资格原样保留，不能把状态变化当作追溯升级。
剩余工作是定向确认上述 API 前提/行为、补丁资料，或在另行明确授权的专用环境扩充新组合；
不是要求再跑一次 FULL 或等待全部失败消失。

<a id="dameng"></a>
## 达梦历史观察、当前实现与复测

以下历史结论迁自使用指南。公开入口是上述 manifest 及本文审阅结论；提及的本地 QA 材料不在仓库内，不能作为可下载的原始报告。

下文“本次”“当前”等措辞以各段原记录时间为准；当前修复与后续 CUSTOM 复测单独列出，不回填历史 FULL。

### 驱动 8.1.2.192 历史基线

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

<a id="dm-independent-full"></a>
### 驱动 8.1.3.62 的独立验收

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
3 项其他 JDBC 结果未评估、5 项套件额外要求未满足；原始 `NOT_EVALUATED=8` 包含这五项。
明确允许 unsupported、准备/环境问题及 Provider 缺失均为 0。
三项未评估异常为 setArray（`22000/-3301`）、absolute(0)（`HY107/6020`）和按列索引批处理生成键（`HY096/6007`），
尚未确认厂商归因。五项套件要求为 ARRAY/REF/STRUCT/ROWID 类型元数据存在性及内部过程更新计数暴露。
任务结束和 254 项通过不代表完整 JDBC 兼容，旧 FULL 及独立复测仍保留原统计。

cleanupEvidence 记录 **CORE 18/18、FULL 41/41** 次已登记创建均确认删除，没有遗留任务对象，保留已有模式。
912 个已有对象的名称、类型、对象 ID 和最后 DDL 时间与初始清单完全一致。
清理只操作 journal 精确登记对象，不执行 DROP SCHEMA、CASCADE、前缀扫描或业务对象 DDL/DML。
再次重启 Server 后仍可读取连接、历史任务和报告。验收临时平台与服务在验证结束后清理，保留用户提供的驱动和已有资源。

2026-11-04 验收时达梦仍为 **candidate**（当前状态见[接纳记录](#dm-platform-acceptance)）。当时运行前数据库版本边界仍精确为 8.1.3.162；驱动数值范围
8.1.2.192–8.1.3.62 保留旧端点并准入本次已观察的新版本，区间内其他驱动、其他 DM8 补丁、
兼容模式及驱动/JDK 组合均**未验证**。manifest 的 qualification 记录该边界，不提升状态或重写历史报告。
Provider 必须是公共类、匹配接口并提供公共无参构造器；缺少类、接口或构造器明确属于 prerequisite，
实际 Provider 的 JDBC 调用失败继续保留。

<a id="dm-capabilities"></a>
### 历史 capability 审计

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

<a id="dm-attribution"></a>
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

<a id="dm-current-implementation"></a>
### 当前实现与后续定向复测

项目修复：达梦 setter 用例先准备原生集合，再向标准工厂传入精确引用、限定的 fixture 类型名；
无论 execute 是否抛错都读取取消线程结果，保留原始主异常与 phase，取消/线程退出失败作为附加证据。
两处线程等待上限分别为 5 秒。Worker 遍历 cause、nextException、suppressed，以对象身份去重，
最多输出 10 条、队列最多 16 项，消息/SQLState 先脱敏再限制为 2000 字符；附加分支不提供主 SQLState。
此前 getParentLogger 只修正 API 引入版本为 4.1，保留驱动实际 JDBC 4.0 和可选不支持策略，不按版本跳过。
中文把 suite requirement 明确称为“套件要求”。UI、JSON 报告和回归比较继续保留 FAILED、
NOT_EVALUATED 与 SUITE 标记；5 是 8 个未评估中的子集，不能重复相加，也不算 JDBC 违规或通过。
已有通用回归及新增五项双语呈现测试核对这一边界。

<a id="dm-set-array-retest"></a>
#### set_array CUSTOM 复测

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
以上为晋级前归因记录；当前正式接纳见[接纳记录](#dm-platform-acceptance)，结果不推广到其他版本或兼容模式。
