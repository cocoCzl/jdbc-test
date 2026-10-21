# JDBC 修复验收摘要与剩余待办（2026-10-21）

本摘要覆盖本轮 JDBC 测试、准备资源及结果分类修复。可以提交这些代码，不代表四库全部验收通过。
原始报告、私密配置、驱动和逐项证据保留在本地私密归档，不进入版本控制；历史报告不修改。

## 版本与证据边界

实测基准为 `09facef995a2d9b898912286f78bcfed6e113a19` 加本轮工作区修改。
验收时70个修改文件的规范化清单 SHA-256 为
`f4d460361718c9b41aaa1ed5811563ca5f58fa27609416142defc36e123882e6`。
整理提交时逐文件核对一致；仅补充本文和文档导航，没有改变执行行为。
该清单标识本地验收快照，不是 Git tree 哈希，也不代表每个中间提交均单独跑过完整测试。
代码依赖顺序为：`cbb3a44`（分类）→ `7abfbb7`（共享准备）→ `6b34139`（Oracle专属准备）；
独立Web防重复提交修复为 `3fd7b62`。最终可执行代码与已验收代码逐文件一致。

| 数据库 / 任务 | 独立任务结果 | 验证边界 |
| --- | --- | --- |
| MySQL `05ec0ba2-fb48-4942-92c5-6cd97f8dcecb` | 定向12条：0通过、0失败、12中止 | 12条均实际调用前置标准API；精确记录 UNSUPPORTED_ALLOWED。功能未通过，不能推断下游方法不支持。 |
| Kingbase `73637018-fc67-43f8-ad48-10cad40d41de` | 定向19条：14通过、3失败、2中止 | ARRAY8、过程/元数据4、原生游标1、临时LOB1通过；ROWID3失败；XA2前提不足。 |
| PostgreSQL `34863c21-f79d-438a-ac0e-c81a02c5238e` | 原FULL 270条：242通过、6失败、22中止 | 目录全部形成终态，262条 contractInvoked=true；不等于270个契约或所有下游API都已验证。 |
| PostgreSQL `bdf903c7-ce15-4020-aebd-69b58e0a0b4e` | 后续定向14条：12通过、0失败、2中止 | 修复后的ARRAY/过程等定向验证及具体API允许不支持；不改写原FULL。 |
| PostgreSQL `f6e556cf-c0ad-4316-ae8a-36addedfda96` | 原生REF_CURSOR 1条通过 | 原整数重载注册、事务内11/22/EOF完整读取。 |
| Oracle | 新准备资源自动化验证完成，真实 Oracle 未验证 | 不使用其他数据库或Mockito结果替代Oracle实测。 |

MySQL为8.0.30 / Connector/J 8.3.0；Kingbase为12.1 Oracle模式 / V009R002C016；
PostgreSQL为15.2 / pgjdbc 42.7.11。结果仅适用于实际版本及测试条件。
MySQL与Kingbase新任务由真实隔离Worker执行；PostgreSQL上述任务曾验证真实Web创建、
冻结输入、Worker、报告与持久化对应。本轮提交整理不重复这些流程。
`contractInvoked` 是用例级调用证据，不是逐JDBC方法调用次数。

MySQL、Kingbase历史FULL保持各自记录；旧r2/r3原始证据仍缺失，不能独立重新核验其完整目录、
原始错误栈、调用及清理证据。会话摘录不是原始报告，未据推测认定缺失原因。
上述定向任务不与任何历史任务拼接成新的FULL通过率。

## 已完成的项目修复

- MySQL SQLXML补齐实际表、XML初始数据和流表，保留真实JDBC API及原值断言；取消SQL按SLEEP语义修正。既有真实定向证据复用，未重复FULL。
- LOB locator使用与目标实际类型等价的资源；PostgreSQL OID精确归属清理与MySQL依赖索引随父表清理保留失败证据。Kingbase locator原成功证据与未变方法体对应；新临时LOB绑定/回读已通过。其他适配器资源不因此宣称已实测。
- 延迟准备保留原缓存、形状预检、SQL异常和归属清理。MySQL 7条ARRAY读取停在`ResultSet.getArray(SQL NULL)`、1条写入停在`Connection.createArrayOf`、3条ROWID停在`ResultSet.getRowId(SQL NULL)`、1条游标停在SQLType重载注册；均为精确允许不支持中止。
- 上述MySQL记录只关闭平台前置API覆盖及映射问题。Array实例方法、setArray、setRowId、原生游标输出尚未执行，不能宣布功能通过或整个接口不支持。
- PostgreSQL补齐原生IN/OUT/INOUT过程和REF_CURSOR准备；只在XA prepare相关分支读取环境前提，不改全局设置。
- Worker、前端详情/统计、持久化/下载及内部比较区分通用JDBC违规、具体方法允许不支持、准备/环境问题、证据不足和SUITE额外要求。额外要求仍可失败，但不计入通用JDBC违规；允许不支持仍中止，不计功能通过。分类修正不改写旧报告。

## 剩余待办与责任

| 项目 | 当前结论 | 完成条件 / 责任 |
| --- | --- | --- |
| PostgreSQL `statement.result_sequence` | 确定的平台覆盖缺口，主契约未执行；不是驱动失败 | 平台开发者确认目标版本原生结果序列语义并补等价准备；厂商可提供标准JDBC示例。不以多语句或游标名列表替代。 |
| PostgreSQL、Kingbase两阶段XA | 环境前提不足，当前`max_prepared_transactions=0` | DBA提供已启用预备事务的授权专用环境，再定向验证prepare/recover等行为；不修改当前全局设置。 |
| Oracle数组、多结果准备及嵌套依赖清理 | 自动化验证完成、真实 Oracle 未验证 | 提供可达PDB服务的完整JDBC URL、PDB内专用本地账号/有效密码、默认schema/表空间额度及必要权限，包括CREATE SESSION/TABLE/PROCEDURE/TYPE和DBMS_SQL.RETURN_RESULT执行条件。已有XML配套JAR不重复索要；不重试CDB$ROOT或历史DEVELOP范围。 |
| Kingbase ROWID 3条 | 疑似厂商NULL映射缺陷，保留原断言和失败 | 真实ROWID类型的SQL NULL也返回非空KbRowId（wasNull=true，getBytes抛NPE）；需厂商确认/修复，再验证未到达的绑定、更新、删除和批量分支。 |
| PostgreSQL `datasource.log_writer` | 疑似厂商问题，保留契约断言失败 | 平台开发者与厂商核对具体DataSource契约及最小复现，不以修改断言消除失败。 |
| PostgreSQL holdability、按索引请求生成键 | 证据不足，保持NOT_EVALUATED | 核对具体API契约及原异常；生成键的PSQLException/SQLState0A000不能直接当作SQLFeatureNotSupportedException式允许不支持。 |
| REF/ROWID类型目录、自动LOB写回、错误后状态恢复、过程内部更新计数要求 | 额外方案要求，未满足仍失败 | 独立保留SUITE范围及原始证据，不并入通用JDBC违规统计。 |
| 历史r2/r3原始证据 | 缺失边界保持 | 只有新线索才继续查找；不重建为原始报告，不据此认证历史FULL。 |
| IDEA原生格式检查 | BLOCKED：已有实例锁 | 用户正常关闭占用IDE后再执行原生检查；不删除锁、杀IDE或改配置。 |

## 复用的检查与清理

- Java构建及单元测试：core30、suite90、worker41通过；Oracle资源测试包含于自动化范围。
- 报告链路：复用r4的Web相关34条、Python契约74条及Server/Worker针对性验证；Server初次进程监督受限的2条后已单独补跑通过，不将受限运行记作全绿。
- Java静态语法规则：255文件、224个既有项，无新增/过期基线；不等同完整Alibaba语义检查。
- Java规则检查器自测：23条通过。
- IDEA原生格式：仍BLOCKED，不视为其他检查已替代。
- 差异空白检查：通过；提交整理仅再检查暂存范围与内容一致性，不重复实库或完整测试矩阵。

本轮真实测试5个专用范围均已归属核验后删除，既有namespace/relation清单前后一致；
构建、Worker及诊断进程已退出。后续提交整理不创建数据库、服务或浏览器，也不删除既有私密归档。
原始报告保持原字节，逐项对应表和校验清单留在固定本地归档。
