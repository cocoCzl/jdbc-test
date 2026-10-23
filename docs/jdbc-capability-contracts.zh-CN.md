# JDBC 判定参考

[English](jdbc-capability-contracts.md) | 简体中文

规范依据为 **Java SE 21 / JDBC 4.3** API Javadoc 及对应 JDK `java.sql` 源码。
本文记录已复核的判定，不代表已逐项审计目录全部断言，也不构成产品认证。
仍需遵守用例 JDBC 基线，例如 SQLType 重载要求 JDBC4.2。
结果阅读见[使用指南](usage.zh-CN.md#results)，未解决准备、环境和驱动问题见[已知限制](development.zh-CN.md#known-limitations)。

## 执行、契约与证据分别记录

PASSED/FAILED/SKIPPED/ABORTED 是执行状态，JDBC 结论、调用证据和 SUITE 额外要求是独立维度。
准备失败不是驱动缺陷，终态不证明契约已调用。元数据布尔值、适配声明和实际操作分别保留；
false 不取消标准操作，也不产生异常豁免。

| 证据 | 判定边界 |
|---|---|
| 已复核操作和断言成功 | 在实际调用范围内 CONFORMS |
| 已复核规范断言被违反 | CONTRACT_VIOLATION，保留观察值和异常 |
| 具体已复核方法允许 SQLFeatureNotSupportedException，且该调用真实抛出 | ABORTED / UNSUPPORTED_ALLOWED，不算功能通过，也不推断后续 API |
| 已复核必需方法拒绝该异常 | UNSUPPORTED_REQUIRED，例如 Connection.getAutoCommit |
| SQL、依赖、Provider 或权限前提缺失 | 准备/环境证据，不构成合法不支持 |
| 普通 SQLException、未限定不支持、未知执行/准备错误 | 保留错误、SQLState/厂商码/原因及 NOT_EVALUATED，除非有具体证据归因 |
| 额外方案特性断言未满足 | FAILED / NOT_EVALUATED，requirementScope=SUITE，不等于通用 JDBC 违规 |

不能按 SQLState0A000、厂商异常名称/消息、能力标志或前置失败推导合法不支持。
`contractInvoked` 是用例级证据，不统计全部下游方法。容器/准备失败保留影响范围，不伪造子用例调用。
历史报告不追溯重新分类。

## 已复核元数据与可选操作

| API | 前提及断言 | 官方方法来源 |
|---|---|---|
| supportsTransactions | 连接和元数据可用，接受 true/false；false 时默认隔离须为 TRANSACTION_NONE。 | [事务声明](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsTransactions())、[默认隔离](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getDefaultTransactionIsolation()) |
| supportsBatchUpdates、supportsSavepoints | 原样保留 true/false；读取 SQLException 仍是错误，不当作 false。查询成功不证明功能可用。 | [批处理](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsBatchUpdates())、[保存点](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#supportsSavepoints()) |
| Connection.setSavepoint() | 非 XA、关闭自动提交、准备完成；非空保存点及回滚成功，或仅 setSavepoint 调用处可选异常。元数据、setAutoCommit、rollback、清理不在豁免内。 | [setSavepoint](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html#setSavepoint()) |
| Connection.createBlob/createClob/createSQLXML | 仅具体工厂调用允许可选异常，后续对象操作保留原断言。 | [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html) |
| SQLXML.setString | 写入可使原对象变为不可读。使用 setSQLXML 绑定，再从数据库取得新 SQLXML 并精确断言内容；不要求读取已写对象，也不省略内容验证。 | [SQLXML 生命周期](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/SQLXML.html) |
| JdbcRowSet.updateRow | 通过独立连接按主键精确核对存储值，失败时也恢复同一记录。ownUpdatesAreVisible 为 false 时，不要求不敏感游标反映自身更新。 | [ResultSet.updateRow](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html#updateRow()), [ownUpdatesAreVisible](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#ownUpdatesAreVisible(int)) |
| CommonDataSource.getParentLogger | 已复核的 logger 可选调用，不扩大至 log-writer 方法。 | [getParentLogger](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/CommonDataSource.html#getParentLogger()) |
| Clob.setCharacterStream(long) | 有效 Clob 和一基位置，返回覆盖字符的 Writer 或该调用抛可选异常；流写入/关闭、提交、访问和清理错误不豁免。 | [setCharacterStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html#setCharacterStream(long)) |
| Blob.position(byte[],long) | 有效 Blob、模式及起点，返回准确位置/-1 或该调用抛可选异常；之前局部写入/长度/字节断言仍执行。 | [position](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html#position(byte%5B%5D,long)) |

仅已复核调用点使用可选包装器。其他批处理/事务/保存点操作保留各自契约，
单方法异常不证明整个接口不支持。声明 false 但实际成功记录为 `declaration_mismatch`，不隐藏后续失败。

## 额外方案要求

[DatabaseMetaData.getTypeInfo](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/DatabaseMetaData.html#getTypeInfo())
描述数据库支持的类型，不要求所有数据库都有 ARRAY/REF/ROWID/STRUCT。
类型存在性测试仍真实调用，缺少方案要求类型仍失败，另记所需类型码、存在值和 SUITE 范围；读取错误仍是错误。

以下要求继续保留，但不声称是通用 JDBC 义务：

- LOB 自动持久化：精确本地修改、提交及新连接精确读取。默认 locator 描述不保证物化对象自动写回，
  不得增加显式 UPDATE 隐藏失败。见 [Blob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Blob.html)
  和 [Clob](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Clob.html)。
- 非法 LOB 调用后恢复：指定 SQLException 属规范要求，后续原内容/状态恢复另判；错误异常类仍违反规范断言。
- 过程内部更新计数可见性：遍历暴露结果/计数至终止，关闭前结果并验证值/副作用；JDBC 不承诺暴露每条内部语句计数。
  见 [getMoreResults](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getMoreResults(int))
  和 [getUpdateCount](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html#getUpdateCount())。

`SuiteRequirementAssertionError` 保留 FAILED/NOT_EVALUATED 与 `requirementScope: SUITE`
（CLI 为 `requirement_scope`）。`SUITE_REQUIREMENT_UNMET` 是明确标记的 NOT_EVALUATED 子集，不能重复相加。
Worker、Web、持久化、下载及 CLI 报告均保留；未知错误不添加该标记，旧无标记报告不推断。

## 准备必须保留被测语义

| 范围 | 必要准备 / 边界 |
|---|---|
| LOB locator | PostgreSQL getBlob/getClob 需 OID，不能用 BYTEA/TEXT；事务内分配并登记精确 OID。free 释放 locator 而非库内对象，仅 unlink 日志所记 OID。Kingbase Oracle 模式以自身 EsBlob/EsClob 操作其 BYTEA/TEXT 资源，依实际列类型而非共享方言推断。流/回滚资源保持独立。 |
| LOB 清理 | 提交前登记归属，保留不确定结果及附加清理异常。隐式依赖随父管理。MySQL 外键依赖索引仅在自有父表成功 DROP 后确认移除，不能禁约束。 |
| SQLXML | 必须有真实表、列和 XML 数据，纯注释资源不算准备。MySQL LONGTEXT/utf8mb4 仍调用 getSQLXML/setSQLXML，精确检查结构/中文/转义；不要求原生 XML 存储，不用字符串绑定替代。 |
| ARRAY | SQL NULL getArray 与 createArrayOf 是独立初始观察；接受 NULL 不证明 Array 方法/存储。使用真实 SQL 数组和原顺序/值/空数组与 NULL 断言，不用 JSON。Oracle 使用自有集合类型、构造器、嵌套表，数值数组可为基本/包装/BigDecimal 但须精确整数值，基础类型名依厂商而异。 |
| ROWID | SQL NULL getRowId 不证明绑定/更新/删除/批量；普通整数键不能代替真实 ROWID。 |
| REF_CURSOR | 原生完整往返保持 registerOutParameter(int,Types.REF_CURSOR)，不额外要求可选 SQLType 重载。MySQL 标量 OUT 资源只观察 SQLType 重载，不执行为游标替代，也不推断整数重载/读取。 |
| PostgreSQL 过程 | 原生 IN/OUT/INOUT 和游标资源需 escapeSyntaxCallMode=callIfNoReturn，事务内读完游标。原生隐式结果序列准备仍缺失，不能用客户端多语句/游标名代替。 |
| Oracle 隐式结果 | 准备 SYS_REFCURSOR 和 DBMS_SQL.RETURN_RESULT；新资源仍需真实 PDB 验证，单测或其他数据库不证明 Oracle 支持。 |
| XA | 仅 prepare 相关 PostgreSQL 分支检查 max_prepared_transactions；0 是环境前提，不改全局、不阻塞其他单阶段/回滚。 |

MySQL 取消使用 `SELECT 1 WHERE SLEEP(n) = 0`：独立 SLEEP 中断可能返回1不报错，
放入查询中断则报错。仍保留时限、异常、取消线程完成和连接恢复断言。
见 [SLEEP](https://dev.mysql.com/doc/refman/8.0/en/miscellaneous-functions.html#function_sleep)。
MySQL [CALL](https://dev.mysql.com/doc/refman/8.0/en/call.html) 不必逐条暴露内部 UPDATE 计数。
这些语义不豁免任意取消成功或任意 SQL 异常。

## 证据、计划要求与比较

Suite 用 `jdbc.capability.metadata.*`、`jdbc.capability.behavior.*` 独立 JUnit 条目记录，
Worker 保留观察；CLI 保存 `capability_evidence` 及含来源、JDBC 版本、前提、结果、理由的
`contract_basis`。适配布尔值仍是声明。

```json
{"plan_requirements":{"required_capabilities":["transactions","batch_updates","savepoint"]}}
```

代表性操作成功满足该窄计划要求；合法不支持使要求未满足，无可靠调用则 not_evaluated。
应同时选择代表操作与元数据，不能仅凭 metadata true。计划要求不新增通用 JDBC 义务。
旧 target_outcome 是方案评估，不是认证，也不是 SQL/产品/厂商兼容综合分。

内部比较把已完成的 `suite_requirements` 与 JDBC 新增/修复/未解决失败分别列出。
新增未满足额外要求仍返回1；原有已完成要求失败可入基线，比较为0且 all_selected_passed=false。
准备/未知错误、执行不完整或计划未评估会以2阻止比较。曾通过 API 变为合法不支持属于支持退化，
不算功能通过。详见[驱动回归](internal-regression.zh-CN.md)。
