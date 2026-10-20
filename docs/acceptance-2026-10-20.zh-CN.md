# 开发检查点验收摘要（2026-10-20）

[English](acceptance-2026-10-20.md) | 简体中文

本摘要整理前三阶段的集成验收结果，区别真实系统、夹具和未验证范围。
它不是 JDBC 认证或产品兼容性证明。自定义 PostgreSQL 适配包仅验证自助接入机制，
未使用真实第三方自研数据库或私有驱动。

## 版本与隔离环境

验收基于 `2eb69675bbff347b243ad5a758637fce2407fba0` 及当时未提交的前三阶段修改。
验收最终逐文件源码清单的 SHA-256 为
`055e744bd3213cc1e0f15492f768ffa6c15513391e2eeff9b38ec232421336b1`。
本检查点随后仅整理说明文档；可执行代码与该验收状态一致。Git 历史记录完整实现和文档，
该清单标识用于关联本地原始证据，不代表本次提交的 Git tree 哈希。

| 项目 | 实际验证范围 |
| --- | --- |
| 平台管理库 | 独立临时 PostgreSQL 16.15；平台库与目标库分开 |
| 目标库 | 同轮独立临时 PostgreSQL 16.15、MySQL 8.4.11，限定专用 schema/库及账号权限 |
| 运行时 | Zulu JDK 21.0.9、Node 24.11.1、真实 Chrome、前端、HTTP Server、隔离 Worker |
| 驱动 | pgjdbc 42.7.11（Web/SQL），42.7.2 → 42.7.11（JDBC A/B）；Connector/J 8.3.0（MySQL SQL） |
| 适配包 | `vendor-int-20261020`，1.0.0/revision 1 → 1.1.0/revision 2，postgresql 方言，IMPORTED/LOCAL |
| SQL 包 | `vendor-sql-int-20261020`，1.0.0 → 1.1.0；`mysql-basics` 1.0.0 |

没有访问业务、共享、远端或历史验收数据库。验收使用的容器、临时卷、服务、Worker、
测试浏览器、凭据和上传驱动副本均已清理；预先存在的资源及共享基础镜像保留。
平台持久化仍要求 PostgreSQL，H2 仅用于部分自动化测试。

### 关键内容指纹（SHA-256）

| 内容 | 指纹 |
| --- | --- |
| pgjdbc 42.7.2 JAR | `0c244ac7d02cf89d8e29852eace6595d75bc4d78581b85b2768460081646a57b` |
| pgjdbc 42.7.11 JAR | `1981b31d3993c58702783c1cddf10a34e48c1f413d70ff1cb6def0a143484647` |
| Connector/J 8.3.0 JAR | `94e7fa815370cdcefed915db7f53f88445fac110f8c3818392b992ec9ee6d295` |
| 自定义适配包 1.0.0 | `9582db7a313f2089c95cc45c7b5fc4c4ccc0186bfd8bbbe1a5db82a1fc78b7a8` |
| 自定义适配包 1.1.0 | `a9b4a21e4590843e39f05b86dd13f0cc1522a8d63164cd19baa9ae71b75dbb49` |
| 自定义 SQL 包 1.0.0 | `eb7384f13488c98c89b3e7e501bfb2727eb580ec8efb5602a6dda4ec73a5f6ca` |
| 自定义 SQL 包 1.1.0 | `d3f18f8ee0b3c0e4c028546bdb16a4a708832792f2042891b52858f99249c332` |
| MySQL 示例包 1.0.0 | `76cd0e86ae4e3be59895c4282b770018ebe530df96534d89bb684d2c09e79a76` |

## 真实系统流程与结果

真实浏览器验收未使用 mock API 或夹具 Worker：

1. 页面上传并校验 pgjdbc，导入独立 ID 的 local 适配包，创建并测试连接，导入 SQL 包，选择版本和用例。
2. 以持有任务 `73e597da-88a5-4587-a5e0-ee53e7767b05` 占用 Worker，确认主任务排队；
   排队期间将适配包和 SQL 包均更新到 1.1.0。
3. 原任务 `ad15e3d2-b6b9-43c0-b734-90f4eb99b022` 仍执行 1.0.0 定义：
   `basic.query`、`null.parameter`、`join.aggregate`、`expected.error` 四项通过；
   另一个故意失败项预期 1、实际 999，结论为 `SQL_ASSERTION_FAILED`，清理 SUCCESS。
   这是平台判定验证，不是数据库缺陷。
4. 页面查看真实 SSE 进度、逐步 SQL、参数、预期/实际值、清理和冻结指纹。
   正常停止并重启 Server 后，页面仍能读取同一任务、历史和报告；定义、指纹、结果及有序数组保持一致。
   跨 JVM 的 JSON 对象字段顺序可能改变，不声称报告字节完全相同。
5. 新任务 `bafb4fd4-a176-49f6-8009-3258ad59f521` 使用 1.1.0 指纹和 SELECT 2，2/2 通过，清理成功。
   最终主要浏览器流程没有浏览器错误或 HTTP >=400 错误。

同一适配包经 CLI/Web 解释的核心执行声明及指纹相同，SQL 模板和包指纹相同；
来源分别保留 CLI_LOCAL/LOCAL 与 IMPORTED/LOCAL，没有提升为 official。

[MySQL 示例](../examples/sql/mysql-basics.json)四项经真实驱动和 Worker 全部通过，覆盖建表/准备、
参数绑定、JOIN/聚合、保留重复行的多重集、NULL、Decimal scale、更新计数及预期错误。
两张归属表确认删除，最终表数 0。MySQL 显式使用 SIGNED/CHAR、InnoDB 和重复主键错误
（SQLState 23000、错误码 1062、SQLIntegrityConstraintViolationException）；
没有将 PostgreSQL 除零错误静默改写或弱化为通用断言。依据见 [SQL 文档](sql-packages.zh-CN.md)。

真实 JDBC A/B 仅选择以下六项，两驱动各六项通过，其余 264 项排除：

- `database_metadata.supports_transactions`
- `database_metadata.supports_batch_updates`
- `database_metadata.supports_savepoints`
- `savepoint.set_savepoint`
- `connection.rollback`
- `statement.execute_batch`

A 运行、选内部基线、B 运行、内部比较均返回 0，local 来源及 `formal_eligible=false` 保留。
单次报告仍为 `evaluation_inconclusive`，不能从六项推导完整 JDBC 兼容性。
比较的 0 表示无新增回归，允许存在旧失败；SQL 执行的 0 表示选中项全部通过且清理确认成功。
真实 A/B 只验证了比较 0；比较 1/2、既有失败、覆盖减少、缺证据及环境差异由合成测试覆盖。
SQL 报告建立 JDBC 基线实际被拒绝（退出 2，未产生基线）。比较密钥仅用于配置 HMAC，
未写入报告或 Worker 环境；本轮临时密钥已销毁，未来运行须用自己的持久密钥重新选基线。

## 迁移及其他证据的准确边界

| 验证 | 证据及范围 |
| --- | --- |
| PostgreSQL 迁移 | 复用前三阶段真实 `PostgresPackageMigrationTest`：1 项通过、未跳过；空库 V1–V10、合成旧数据 V8→V9→V10，原驱动/连接/任务/结果/报告可读，新包资源及任务快照重开后恢复。本轮真实 Web 的空平台也执行 V1–V10；未重复完整升级矩阵 |
| PostgreSQL SQL | 真实 `PostgresSqlFlowTest` 1 项通过、未跳过；示例、故意失败、超时、协作取消、后续执行和清理；CLI 实测 0/1，无效输入 2 离线验证 |
| 真实浏览器 | 自定义全链路、重启与新版本两个测试分别通过；测试入口见 [custom-integration.real.spec.ts](../jdbc-test-web/e2e/custom-integration.real.spec.ts)，必须显式启用独立环境 |
| Java 定向测试 | Core SQL 5、凭据 8、SQL executor 7、WorkerMain 9、REST handler 1，相关运行通过 |
| 调度/强制结束 | 3 项边界测试，另补 1 项 UNKNOWN 证据测试通过；强制结束使用夹具 Worker，保留 CANCELLED、报告未校验、清理 UNKNOWN |
| Python | regression 17、SQL 4、adapter archive 3、distribution 12，全部通过 |
| Web | 相关 71 项、lint、TypeScript/Vite build 通过；既有 chunk-size 提示保留；未重复前三阶段全部矩阵 |
| Java checker 单测 | 23 项通过 |
| Java 语法规则 | 257 文件、224 个既有项，无新增/失效基线项；不等同语义或完整 Alibaba 规范检查 |
| IDEA 原生格式 | **BLOCKED**：既有 IDEA 实例锁访问受限；未关闭 IDE、删除锁或修改配置，其他检查不视为等价替代 |
| diff 空白检查 | `git diff --check` 通过 |

## 三处问题与修复

1. **SSE 断连二次异常**：通用 REST handler 向已关闭的事件流写 JSON。
   现在识别不可写异步响应和 Spring 断连异常；真正平台错误仍返回 INTERNAL_ERROR。
   `RestErrorHandlerTest` 验证两类行为。
2. **SQL 输出中已知连接密码遮蔽不全**：安全投影补齐 SQL、模板、参数、结果行及输出快照，
   不修改实际执行输入或协议身份。`CredentialOutputTest` 增加合成秘密回归；
   保留证据的本轮凭据扫描未命中。这不代表可以识别任意作者嵌入的秘密。
3. **已完成 SQL 断言失败被误标为 Worker 故障**：有效且一致的失败报告现在保留
   SQL_ASSERTION_FAILED 及中英文说明，状态仍为 FAILED。Worker 协议退出码 4 与 SQL CLI
   退出码 1 分开，真实 PostgreSQL及定向调度测试复验通过。

## 交付与限制

版本控制保存此脱敏摘要、实现、测试、示例和使用文档。完整原始验收记录、逐文件清单、
脱敏日志/截图/报告和清理结果保留在工作区忽略目录 `.local/integration-20261020/`，
没有将原始日志、配置、驱动 JAR 或截图纳入提交。本摘要的结论不要求读者访问该本地目录。

尚未验证：真实第三方自研数据库/私有驱动、其他产品版本或兼容模式、完整 JDBC 矩阵、连接池变体、
真实不响应驱动强杀后的清理、生产/公网/多租户安全。SQL 包和驱动须可信，数据库权限必须独立限制范围；
关键词约束不构成安全沙箱。内部 JDBC 基线目前仅 CLI；SQL 基线不支持。
历史 Oracle/Kingbase 验证是独立记录，不并入本轮结论。

**可交付结论**：外部团队自助导入、连接、SQL 执行与逐项报告、排队冻结、更新和重启读取
已有上述真实系统证据；驱动回归有六项真实 CLI A/B 证据。
这些证据不证明任意 JDBC 数据库兼容、完整 JDBC 认证或完整产品兼容性。
