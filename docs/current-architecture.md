# Current Architecture Audit

## 当前架构：Phase 10 最终交接已验收

`jdbc-test-web/` 已建立为独立 React/strict TypeScript/Vite npm 工程，不加入 Maven
reactor。Java Core/Suite/Worker/Server 边界保持 Phase 7 稳定基线。开发链路是：

```text
React :5173 → Vite /api streaming proxy → Spring Boot :8080
                                       → Platform PostgreSQL
                                       → Scheduler → Worker JVM → external Driver → Target DB
```

Web 使用 TanStack Query 管理 REST/SSE 的同一 cache；TanStack Table 只展示服务端
分页；局部表单/wizard 状态留在 React 内存；ECharts 只使用实际 API outcome 数据。
Backend DTO/REST contract/schema 不变，未增加 Dashboard product API。前端
目录、页面、安全边界、开发要求和已知 API 限制见 `web-ui.md`；证据见
`phase8-verification.md`。Phase 8 已获用户正式 PASS；Phase 9 增加独立 Docker
生产构建、同源静态托管、Actuator health 与持久化 Compose，不改变 REST DTO/schema。

```text
Browser :8080 → jdbc-test container (Server + /app/web)
                    ├→ postgres service / postgres_data
                    └→ child Worker JVM → uploaded Target Driver → Target DB
                         /data/jdbc-test → jdbc_test_data
```

普通 Maven lifecycle 不执行 Node/npm。Server 保留普通 artifact，另外附加 `exec`
classifier，避免 Failsafe 将 Spring Boot nested jar 当作平面测试 classpath。
容器使用 `docker` profile、固定 Worker 路径、UID 10001 和 Compose init。
ContextClosedEvent 在 datasource 销毁之前关闭 dispatcher、记录 INTERRUPTED 与
清理临时凭据；异常退出仍由 startup recovery 处理，绝不自动 resume。
部署操作/安全/备份见 `deployment.md`，Phase 9/10 实际证据见
`phase9-verification.md` 和 `phase10-verification.md`。Run 状态筛选使用显式
status 查询并保留服务端分页统计；Phase 10 已完成本轮已确认 Bug 修复和交接核对。

以下 Phase 0 审计保留为历史基线，后续分阶段演进记录位于文末。

审计日期：2026-09-29\
审计范围：仓库当前提交内容（Phase 0，只读审计；未修改业务代码）

## 1. 仓库形态

当前仓库是一个单 Maven 项目，核心执行入口仍是 Python CLI，不是 Spring Boot/Web 应用。

```text
jdbc-test/
├── pom.xml                         单一 Maven artifact：com.jdbctest:jdbc-test
├── jdbc-test-core/src/main/java/   仅 PropertyGenerator
├── jdbc-test-core/src/test/java/   JDBC 测试、配置模型和 JUnit 扩展
├── ddl/<adapter>/                  各数据库 DDL 资产
├── dml/<adapter>/                  各数据库 DML 资产
├── pool/<adapter>.yaml             连接池参数
├── adapters/<id>/adapter.json      声明式数据库适配包和证据
├── compatibility/v1/               场景清单
├── configs/                        本地配置示例（被 .gitignore 忽略）
├── scripts/runner.py               init/run/compare/coverage/adapters CLI
├── scripts/adapter_runtime.py      适配包校验、运行时配置、Driver 解析
├── scripts/compatibility_v1.py     场景发现、报告模型、状态归类
└── Dockerfile                      JDK 21 + Maven + Python CLI 镜像
```

仓库当前已有 `mvnw`、`.mvn/` 和三个 Phase 1 Maven 模块，但模块中只有 core 承载现有源码，worker/server 暂为空壳边界；仍没有 Spring Boot 业务、React/Vite 工程、数据库迁移目录或 Docker Compose 文件。`dist/` 中存在已生成的 1.2.1 ZIP 发布包；`local-drivers/` 中有本地测试用 dual Driver JAR，但该路径已被 Git 忽略。

## 2. 构建与依赖

根 `pom.xml`：

- group/artifact/version：`com.jdbctest:jdbc-test:1.2.1-SNAPSHOT`。
- 编译 source/target 为 Java 21，但未设置 `maven.compiler.release`。
- JUnit Jupiter 5.10.2、Surefire 3.2.5。
- 测试期 JDBC 驱动：PostgreSQL 42.7.2、MySQL 8.3.0、Oracle 21.13.0.0（含 NLS/XDB/XML）、SQL Server 12.6.0.jre11。
- HikariCP 5.1.0、SnakeYAML 2.2、SLF4J NOP 1.7.36。
- `exec-maven-plugin` 在 `process-test-resources` 阶段运行 `PropertyGenerator`，从临时/运行时配置生成 `junit-platform.properties`。
- 没有 Maven Enforcer、Maven Wrapper、Failsafe、Spring、JPA、Flyway 或 Testcontainers 依赖。

当前环境基线：JDK 21.0.9、Maven 3.9.15（符合目标版本，但这是环境现状，不是仓库保证）。

## 3. Java 测试资产

`jdbc-test-core/src/test/java` 共 20 个 `*Test.java` 文件、约 4,900 行（含测试支持代码），其中 17 个数据库集成测试类使用 `JdbcTestExtension`，3 个纯本地单元测试类为 `ConfigLoaderTest`、`DriverManagerDataSourceTest`、`SqlSplitterTest`。主要测试模块和方法数如下：

| 模块/类 | 测试方法 | 主要依赖/资产 |
|---|---:|---|
| Connection | 17 | `connection_ddl/dml.sql` |
| Statement | 23 | `statement_ddl/dml.sql` |
| PreparedStatement | 22 | `preparedstatement_ddl/dml.sql` |
| ResultSet | 28 | `resultset_ddl/dml.sql` |
| ResultSetMetaData | 13 | `metadatatest_ddl/dml.sql` |
| DatabaseMetaData | 23 | `metadatatest_ddl/dml.sql` |
| CallableStatement | 7 | `callablestatement_ddl/dml.sql` + cleanup |
| Blob/Clob | 11 | `blobclob_ddl/dml.sql` |
| AdvancedType | 8 | `advancedtype_ddl/dml.sql` |
| ParameterMetaData | 8 | `parameter_metadata` 能力 + SQL 资产 |
| RowSet | 9 | `rowset_ddl/dml.sql` |
| Savepoint | 6 | `savepoint_ddl/dml.sql` |
| SQLXML | 7 | `sqlxml_ddl/dml.sql` |
| JDBC type coverage | 6 | 能力条件：array/sqlxml/rowid/ref/struct |
| Lifecycle | 3 | `request_boundaries` 能力 |
| DataSource | 7 | Hikari 或 DriverManager 数据源 |
| Wrapper | 4 | JDBC wrapper 契约 |
| 本地配置/工具单测 | 12 | 无目标数据库 |

测试使用 JUnit 5 `@Test`、`@TestMethodOrder(OrderAnnotation.class)`、`@ExtendWith(JdbcTestExtension.class)`、`@RequiresFeature` 和 `@UseSqlScripts`。没有使用 JUnit `@Tag`；profile/filter 由 Python runner 依据场景清单选择 Surefire 方法。

## 4. 配置、连接与数据库边界

- `ConfigLoader`（位于 `jdbc-test-core/src/test`）读取 `-Dconfig.yaml`、`CONFIG_PATH` 或工作目录中的 `config.yaml`；支持旧 YAML 和 runner 生成的 JSON 运行时结构。
- 密码由 `password_env` 在 Python 运行时注入；Java 运行时 `Config` 只存在进程内，不写报告。
- `JdbcContext` 在测试 JVM 中创建 HikariCP 或 `DriverManagerDataSource`，通过 `Class.forName`/Hikari `driverClassName` 加载 Driver，并直接连接目标数据库。
- `JdbcContext` 执行适配包 namespace 创建、权限预检、元数据校验、DDL/DML 资产和 namespace 清理；表名有基本 identifier quoting 和清理失败诊断。
- `DriverManagerDataSource` 为 DriverManager 连接模式，支持 vendor properties；其单元测试使用内存中的 `RecordingDriver`。
- 目标数据库完全不使用 JPA；当前也没有平台数据库。

## 5. 适配包与测试选择

当前适配包：PostgreSQL、MySQL、Oracle（`official`，有证据）、GaussDB、SQL Server（`candidate`）。每个 `adapter.json` 声明：Driver 类和来源、数据库/驱动身份正则、能力布尔值、SQL 资产区域、namespace 生命周期、支持版本、最小权限和已验证组合。

`adapter_runtime.py` 负责：适配包发现/校验、能力字段完整性、DDL/DML 资产存在性、官方证据存在性、运行时配置构建、Driver 来源解析（Maven/local）。

`compatibility_v1.py` 从 `src/test/java` 和 `compatibility/v1/scenario-inventory.yaml` 构建 202 个 reportable scenario，按源类/方法、JDBC domain、所需 capability 关联 Surefire XML。`runner.py` 的 `core/full/include_tests/exclude_tests` 最终转换为 Surefire `-Dtest=Class#method` 选择器；无过滤的 `full` 会运行全部测试（含本地单元测试）。

## 6. Runner 与报告

`scripts/runner.py` 是当前唯一的编排入口：

1. 读取 JSON/兼容旧 YAML 配置。
2. 预检 Python/JDK/Maven、适配包和 Driver artifact。
3. 生成权限为 0600 的临时 runtime JSON。
4. 调用 `mvn -q test -Dconfig.yaml=<temp> -Dsurefire.additionalClasspath=<driver classpath>`。
5. 解析 `target/surefire-reports/TEST-*.xml` 和 JUnit properties。
6. 由 `compatibility_v1.build_v1_report` 生成权威 `report.json` 模型，再派生 Markdown、HTML 和本地 `diagnostics.log`。
7. 根据兼容性失败、预检失败、清理失败决定进程退出码。

报告当前包含 schema/baseline 版本、执行时间、目标数据库和 Driver 元数据、适配包、场景结果、覆盖汇总、能力缺口、已知差异、清理/预检问题以及 Git commit（可读取时）。`report.json` 是比较和 CI 的权威格式，`compare` 按 scenario ID 比较 baseline/current。

## 7. 运行安全与现有约束

- `destructive_consent` 未确认时，运行时拒绝执行变更。
- PostgreSQL/MySQL/GaussDB 适配包使用随机 namespace；Oracle/SQL Server 使用既有 schema/namespace。
- 配置文件和本地 Driver 被 `.gitignore` 忽略；Driver 不安装到 Maven 本地仓库。
- 共享报告默认脱敏，完整排障信息只在本地 diagnostics 文件。
- 当前 Driver 会在执行测试的 Maven/JUnit JVM 中加载；尚不存在 Server JVM，因此 Phase 3 必须把这一边界迁移为独立 Worker，而不是照搬到 Spring Boot。

## 8. Phase 1 迁移输入

应优先保留并封装的资产：测试类和 SQL 资产、`JdbcContext` 的 namespace/权限/元数据行为、适配包 schema 与能力声明、202 个场景 ID、`report.json` v1 兼容性、CLI init/run/compare 的配置兼容性。应从测试源码迁移出的领域边界：Test Catalog/Selection、Driver artifact 元数据、Worker runtime config、Report model。

## 9. Phase 2 Core Catalog

`jdbc-test-core` 现已包含 `TestCatalog`、`TestSelection`、`TestProfile`、`TestModule` 和 `TestCaseDescriptor`。Catalog 读取根目录版本化 `compatibility/v1/scenario-inventory.yaml`，并校验每个 scenario 的真实 Java source class/method；Core/Full 分类以测试源码上的 `@RequiresFeature` 元数据为准，保持现有 Python 语义。

## 10. Phase 3 Worker

Phase 3 增加 `jdbc-test-suite` 作为正式 runtime artifact：原本位于
`src/test/java` 的 17 个 JDBC compatibility test classes、配置/扩展支持类和
SQL 资产现在进入 suite JAR；Catalog inventory 也以
`/compatibility/v1/scenario-inventory.yaml` 进入 classpath。Worker 的 fat JAR
同时包含 core、suite 和 JUnit Platform Launcher，因此不读取 source root 或
`target/test-classes`。

`jdbc-test-worker` 从 stdin 读取结构化 `WorkerRunRequest`，通过独立
`URLClassLoader` 加载本次 run 的 Driver，并直接调用 `java.sql.Driver#connect`。
Worker 使用 Core 的 Catalog/Selection 生成 JUnit Platform discovery request，
stdout 只输出 NDJSON，report 写入 run workspace。Python Runner 仍使用 Maven
兼容路径；Phase 3 没有引入 Server 调度、REST、JPA 或平台 PostgreSQL。

当前稳定 module ID 共 17 个：`advanced-type`、`blob-clob`、`callable-statement`、`connection`、`database-metadata`、`datasource`、`jdbc-type`、`lifecycle`、`parameter-metadata`、`prepared-statement`、`result-set`、`result-set-metadata`、`rowset`、`savepoint`、`sqlxml`、`statement`、`wrapper`。

## 11. Phase 4 Server Persistence Foundation

`jdbc-test-server` 现为 Spring Boot 4.1.0 应用，依赖 `jdbc-test-core`，但不依赖
`jdbc-test-suite` 或 `jdbc-test-worker`。Server 的 PostgreSQL 驱动只用于平台数据库；
用户 target Driver 仍只在独立 Worker JVM 中加载。平台 schema 由 Flyway
`V1__init_schema.sql` 管理，Hibernate 使用 `ddl-auto=validate`。

平台持久化模型位于 feature-oriented packages：driver package/artifact、connection
profile、test run/module/case result。实体使用 UUID、Instant、STRING enum 和保守
foreign key 关系，不建立级联删除历史数据。凭据通过 AES-256-GCM 加密保存，JDBC URL
在 snapshot/诊断前统一脱敏。默认构建不启动 Server 或连接数据库；
`-Pserver-integration` 使用 Testcontainers PostgreSQL 验证 Flyway、Hibernate 和
repository 持久化。

## 12. Phase 5 Driver Management

`jdbc-test-server` 现在提供 `/api/drivers` REST resource。上传的多 JAR package 先流式写入可配置 data directory 的 staging area，Server 仅以 `JarFile` 读取 ZIP metadata/SPI，不加载任何 user Driver class。SHA-256 和顺序无关的 package fingerprint 被保存到 Platform PostgreSQL；`V2` 为 fingerprint 建立唯一约束。

真正加载、初始化和实例化 `java.sql.Driver` 的行为只发生在短生命周期 Worker JVM。Worker 的 protocol writer 保留启动时 stdout，并在 user Driver 代码运行时将全局 `System.out` 重定向到有上限的 diagnostics sink，防止第三方输出污染 NDJSON。

## 13. Phase 6 Connection Management

`DatabaseConnectionProfile` now persists explicit `adapter_id` with the open `database_type`; Flyway `V3` adds the column. Connection profiles reference a validated Driver Package and never copy its binary metadata. Core packages the existing five adapter manifests as read-only metadata resources for Server REST.

Connection CRUD and draft/saved connection probes are REST resources. Passwords are write-only and persist only as AES-256-GCM envelopes. The Server passes target credentials on stdin to a short-lived Worker `--probe-connection`; the Platform PostgreSQL driver remains restricted to Server persistence and never services a target connection.
# Phase 7 execution boundary

The Spring Server persists and schedules Runs but never loads a target JDBC Driver or opens a target JDBC connection. A separate Worker JVM owns driver loading, JDBC and JUnit execution. Browser clients observe persisted state through REST and optional MVC SSE.
