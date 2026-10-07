# Migration Notes: Plan vs Current Repository

当前工作树已完成 Phase 10 的已知 Bug 修复与交接核对。下文的 Phase 0/早期阶段表格
保留当时的历史审计事实；当前实现以各阶段末尾的实际状态和
`docs/phase10-verification.md` 为准。

审计日期：2026-09-29\
对应阶段：Phase 0 — Audit / Baseline

## 1. 总体差异

计划描述的目标是 Spring Boot 4.1 + JPA/Flyway 平台、独立 Worker JVM、React Web 和 Docker Compose；当前仓库仍是单模块 Maven/JUnit + Python CLI 的兼容性测试框架。当前代码已经具备适配包、能力声明、命名空间保护和结构化报告等重要基础，但没有平台持久化/调度层。

## 2. 计划要求与实际状态

| 计划项 | 当前状态 | 迁移含义/风险 |
|---|---|---|
| `jdbc-test-core` | 不存在；测试领域分散在 `src/test`、`scripts/compatibility_v1.py`、SQL 资产 | 先提取测试目录/场景/报告的稳定模型，避免重写测试语义 |
| `jdbc-test-worker` | 不存在；`runner.py` 直接在当前 Maven JVM 运行测试 | 必须复用现有 runtime config 和 Surefire 解析，但以独立进程作为边界；不能把用户 Driver 加入 Server classpath |
| `jdbc-test-server` | 不存在 | 新增 Spring Boot/JPA/Flyway 时与旧 Maven artifact 解耦，平台 DB 不能接触 target DB |
| `jdbc-test-web` | 不存在 | 后置到 Phase 8；Phase 0 不创建前端 |
| Maven aggregator / Enforcer / Wrapper | 只有单 `pom.xml`；无 wrapper/enforcer | Phase 1 需要先确保旧测试仍可运行，再做模块迁移；当前环境 Maven 3.9.15 可作为 wrapper 基线 |
| Spring Boot 4.1.x / JPA / Flyway / PostgreSQL platform DB | 不存在 | Phase 4 才引入；不能让测试配置类误用 JPA |
| Driver Package 多 JAR storage | 当前 `driver.source` 支持 Maven 或本地文件/目录；未有上传、hash、持久 storage | 现有 `resolve_driver`/classpath 逻辑可作为 Worker 输入，需新增文件存储和 artifact fingerprint |
| Driver SPI 自动发现 | 未实现为独立能力；adapter.json 手工声明 Driver class | 可在 Phase 5 只读取 JAR 文本元数据，真实类加载仍交给 Worker |
| Server/Worker 边界 | 当前测试 JVM 直接 `Class.forName`/Hikari 加载 Driver | 是最高风险迁移点；`JdbcContext` 应迁入 worker/core 执行侧，Server 只读 metadata/启动进程 |
| Test Catalog / JUnit Tags | 有 202 项 YAML/源码 inventory 和 feature annotations；无 `@Tag` | 先建立兼容 catalog，保留 scenario ID、`@RequiresFeature` 和 include/exclude 语义，不立即改写全部测试 |
| Test Profile | Python `core/full` + include/exclude 方法模式 | 可直接映射为新 `TestSelection`；需避免改变 `full` 当前包含本地单测的行为，或明确记录行为变化 |
| Report | `compatibility_v1.py` 生成 `report.json` v1，runner 派生 MD/HTML | 必须保留 schema/字段和比较逻辑；Phase 3/7 做结构化 case/event 转换时提供兼容层 |
| Runner | `scripts/runner.py` 负责预检、临时配置、Maven、报告归档、退出码 | CLI 是必须保持兼容的现有资产；后续可由 Server 调用等效 Worker，不应在 Phase 1 删除 |
| Config | 当前主要是生成 JSON + 旧 YAML 兼容；密码通过环境变量 | 平台 Connection DTO 需要 write-only password + 加密存储；迁移文档应保留 CLI `password_env` 路线 |
| DB adapters/dialects | 5 个适配包，SQL 在顶层 `ddl/`、`dml/`，连接池在 `pool/` | 先保留目录/资产路径；不要在 Phase 1 为每个数据库过早拆模块 |
| Destructive protection | consent、随机 namespace、清理失败诊断已存在 | 迁移时必须映射到 Worker run workspace/run-specific namespace，不能弱化保护 |
| Docker | 只有 CLI Dockerfile，无 Compose | Phase 9 再添加 app/postgres/volume；当前 Dockerfile 不应被 Phase 0 改写 |
| Integration tests | 无 Testcontainers；真实 DB 需用户配置 | Phase 4/3 逐步新增平台 DB 和 Worker 集成测试；基线不能假设开发机有数据库 |

## 3. 测试基线与可复现性

### Maven

执行：`mvn -q test`\
环境：JDK 21.0.9、Maven 3.9.15\
结果：命令失败，`Tests run: 29, Failures: 0, Errors: 17, Skipped: 0`。

17 个错误全部发生在使用 `JdbcTestExtension` 的数据库测试类，原因一致：默认配置解析到仓库根目录 `/Users/cococzl/Documents/github/jdbc-test/config.yaml`，该文件不存在。12 个不需要目标数据库的本地测试通过（`ConfigLoaderTest` 7、`DriverManagerDataSourceTest` 1、`SqlSplitterTest` 4）。这不是数据库连通性失败，而是运行配置缺失导致的条件评估错误。

重现：

```bash
mvn -q test
```

要运行数据库集成测试，需要先以 `scripts/runner.py init` 生成本地 JSON 配置、设置相应密码环境变量并提供可用目标数据库；本次审计没有向外部数据库发起连接或执行破坏性测试。

### Python

执行：`python3 -m unittest discover -s scripts -p 'test_*.py'`\
结果：`Ran 30 tests in 0.951s — OK`。

### 静态/功能盘点

- `python3 scripts/runner.py adapters` 成功列出 5 个适配包。
- `python3 scripts/runner.py coverage --profile full --format json` 成功生成 202 个场景清单。
- 当前工作树只有用户提供的未跟踪 `REFACTOR_PLAN_OPTIMIZED.md`；Phase 0 新增的两份 docs 是本次审计产物，`target/` 等构建产物被忽略。

## 4. 迁移顺序修正建议

1. Phase 1 不要先移动所有测试源码；先建立 aggregator 和最小 core/worker/server 边界，并以现有单模块测试作为回归基线。
2. 先把 `compatibility_v1.py` 的 scenario inventory/report schema 视为兼容资产，再设计 `TestCatalog`/`TestSelection`，否则 Web 选择会丢失现有 202 个稳定 scenario ID。
3. 将 `JdbcContext`、`Config`、`ConfigLoader` 和 SQL 资产执行逻辑按“Worker 执行侧”迁移；`src/test` 中的支持类不能直接成为 Server 代码依赖。
4. `runner.py` 当前支持 Maven/local Driver 来源，而计划优先上传多 JAR。先定义通用 run config 和 package fingerprint，再把 Maven/local 作为兼容输入迁移到 Worker；不要在 Phase 1 删除 CLI。
5. 当前 `full` 无过滤时包括本地配置/工具测试；计划中的 Web profile 应明确是“报告测试模块”还是“全部 Surefire 测试”，建议先保持现行为并在 Catalog 中区分 reportable scenario 与本地单元测试。
6. 计划要求的 Worker NDJSON 事件、取消、超时、孤儿 Run 恢复目前完全没有对应实现；这些属于 Phase 3/7 的新增风险，不应在 Phase 0 伪装成已有能力。
7. 平台密码加密、Driver storage、Flyway schema、SSE 和 REST DTO 均为全新能力；当前 Java 配置对象位于测试源码，不能直接暴露给 API。

## 5. Phase 1 实施记录

- 根 `pom.xml` 已改为 aggregator，模块为 `jdbc-test-core`、`jdbc-test-worker`、`jdbc-test-server`。
- 原有 Java 源码和测试整体迁入 `jdbc-test-core`，未进行 package rename，也未删除适配包、SQL、CLI 或报告实现。
- 17 个真实数据库测试类增加 JUnit `integration` tag；默认 Surefire 排除该 tag，`integration-tests` profile 显式启用。12 个本地单元测试仍属于默认构建。
- 已加入 Maven Enforcer（Maven `[3.9.0,)`、Java `[21,22)`）、统一 compiler release 21、Surefire/JAR plugin 管理和 Maven Wrapper 3.9.15。
- `runner.py` 继续保留原有 CLI 行为，运行 Maven 时优先使用 `./mvnw -Pintegration-tests`，并从 `jdbc-test-core/target/surefire-reports` 读取结果；场景选择和 report schema 未重写。
- Python 场景扫描兼容新 core 路径，同时保留旧路径回退；发布脚本的版本和 allowlist 已适配多模块布局。

## 6. Phase 1 结论

当前仓库的核心可迁移资产是：真实 JDBC 测试类与 SQL、适配包契约、能力声明/场景 inventory、namespace/权限安全流程、CLI 配置兼容性和 `report.json` v1。Phase 1 后，默认 Maven 基线在无本地配置时可稳定通过 12 个本地单元测试；17 个数据库测试仍可通过 integration profile 显式选择并在缺少配置时按预期失败。Python 套件 30/30 通过。

## 7. Phase 2 实施记录

- `jdbc-test-core` 新增 `TestCatalog`、`TestSelection`、`TestProfile`、`TestModule` 和 `TestCaseDescriptor`。
- Catalog 以现有 `scenario-inventory.yaml` 为稳定 ID/契约输入，并校验真实测试源码；Core/Full 分类从源码 `@RequiresFeature` 元数据派生，而不是重新手工维护分类表。
- 202 个 scenario 被映射到 17 个稳定 kebab-case module ID；没有修改既有 scenario ID。
- Selection 顺序固定为：profile → included modules → included scenarios → excluded scenarios；exclude 优先于 include，未知 module/scenario 立即报错。
- 新增 13 个 Java 自动测试，覆盖发现、唯一性、Core/Full、单/多模块、include/exclude、未知输入和 integration tag 保留。
- 未引入 JUnit Platform Launcher、Worker Process、Server API 或其他 Phase 3+ 能力；Java Catalog API 已足够作为未来 Worker 的选择输入。

## 8. Phase 3 实施记录

- 新增 `jdbc-test-suite`。兼容性测试类和 Worker 所需配置/扩展支持类从 Maven
  test source 迁移到 suite main artifact；原有 12 个本地工具单测保留在 suite
  test source。suite 资源包含 inventory、DDL、DML 和 pool。
- `TestCatalog.loadDefault()` 从 classpath inventory 加载并通过反射读取
  `@RequiresFeature`，生产 runtime 不再要求 Java source root。显式 Path API
  仍保留给 build-time validation。
- Worker 使用 Shade 生成 `jdbc-test-worker-1.2.1-SNAPSHOT-all.jar`，stdin
  JSON、stdout NDJSON、workspace report 和独立 Driver URLClassLoader 已建立。
  为保持 Python Runner 的 Maven 兼容入口，integration profile 在 suite
  artifact classes 上执行 JUnit；默认 verify 仍只执行本地单测。
- Worker 的 config 文件只是短生命周期兼容桥接，位于 run workspace/temp，
  尽可能设置 0600，执行结束删除。Worker 不注册全局 DriverManager，不缓存
  Driver 或 ClassLoader。

## 9. Phase 3 已知限制

Phase 3 初次验收时 Docker/Testcontainers 不可用，`WorkerPostgresIT` 曾记录为
verification debt。本阶段在 Docker 恢复后重新执行 `./mvnw -Pworker-e2e verify`，
真实 PostgreSQL Driver 作为外部 artifact 动态加载、Connection/Statement 场景选择、
NDJSON、report 和凭据脱敏均已通过。Worker report 仍是保持稳定 scenario ID 的最小
v1-compatible JSON，Python Runner 现有 report pipeline 没有切换到 Worker。

## 10. Phase 4 实施记录

- `jdbc-test-server` 已成为 Spring Boot 4.1.0 应用，仅依赖 `jdbc-test-core`；没有
  把 suite 或 Worker 加入 Server runtime classpath。
- 新增 Flyway `V1__init_schema.sql`，建立 driver package/artifact、connection
  profile、test run/module/case result 六张平台表。Hibernate 使用
  `ddl-auto=validate`，并通过显式 Flyway wiring 确保 migration 先于 JPA schema
  validation 执行。
- 实体统一使用 UUID、Instant 和 STRING enum；外键保持保守，不级联删除历史 Run
  或结果。Repository 仅提供当前持久化测试和后续阶段需要的基础 CRUD。
- `CredentialEncryptionService` 使用 AES-256-GCM 和版本化 envelope，master key
  只从 `JDBC_TEST_MASTER_KEY` 读取；`JdbcUrlSanitizer` 用于 snapshot/诊断脱敏。
- 默认 `./mvnw clean verify` 不需要 PostgreSQL、目标数据库、config.yaml 或密钥。
  `-Pserver-integration` 通过 Testcontainers 验证真实 PostgreSQL/Flyway/JPA 链路。

## 11. Phase 5 实施记录

- `V1__init_schema.sql` 保持不变。`V2__driver_package_fingerprint_unique.sql` 为 immutable binary fingerprint 增加数据库唯一约束，防止并发重复上传。
- 新增 RESTful Driver Package management、multipart streaming storage、SHA-256、SPI detection 和 Worker-only driver validation。Server 没有引用 Worker/Suite runtime classes，也不加载上传 JAR。
- Spring Boot 4.1 当前 starter dependency set 不提供 Jackson 2 API；为 Worker JSON process protocol 显式加入 `jackson-databind`。现有显式 Flyway configuration 仍保留，因为该 starter set 的 auto-configuration 未提供确保 Flyway 先于 Hibernate validate 的等价 wiring；真实 PostgreSQL integration profile 是该决定的验证入口。

## Phase 6 — Connection Management

`V3__add_connection_adapter.sql` adds `database_connection_profile.adapter_id` and its index; V1 and V2 remain historical and unchanged. The migration assigns `postgresql` to pre-existing rows only as a safe non-null migration default; newly created resources always submit and validate their explicit adapter ID.

Server REST moved from the explicit deprecated Jackson 2 MVC converter to Spring Boot 4.1's Jackson 3 default stack. The Worker keeps Jackson 2 independently for its private stdin/NDJSON protocol. No connection-properties map was added: the existing product contract needs only JDBC URL, username, and password, and a generic map would permit unmodelled plaintext secrets.
# Phase 7

`V4__test_run_orchestration.sql` adds the immutable adapter snapshot, `test_run_selected_scenario`, and encrypted short-lived `test_run_credential`, plus run lookup indexes. V1, V2 and V3 remain unchanged.

The October 1, 2026 deterministic verification is complete; see `phase7-verification.md`. No new schema migration is required for the orchestration fixes. Run writes/cancel use pessimistic row locking rather than changing associations to EAGER. H2 is test-scoped only; PostgreSQL/Flyway remains the production platform database. This is the accepted Phase 7 baseline.

## Phase 8 — React Web

- Added `jdbc-test-web/` as an independent npm project, **not** a Maven module.
  Java dependency/module boundaries, schema, REST DTOs and scenario semantics
  remain unchanged. The Python CLI remains supported.
- React/strict TypeScript/Vite/Tailwind/shadcn UI, Router, Query/Table and ECharts
  provide genuine Driver/Connection/Run flows. SSE incrementally updates the
  same Query cache loaded by REST; native reconnect is not a Run failure.
- Dashboard deliberately uses a bounded eight-Run page plus actual catalog and
  pagination totals. No fake global statistics or synthetic module chart; no
  unrequested backend aggregate or diagnostics endpoint is added.
- Passwords stay in local transient memory and write-only POST/PATCH bodies,
  never browser storage/URLs/resource cache. No raw HTML rendering. Tests and
  their fixture/transport mocks are separated from production code.
- npm lockfile and Node >=22.12.0 baseline are documented in `web-ui.md`.
  `phase8-verification.md` distinguishes unit/component tests, API-mocked browser
  smoke and the real PostgreSQL/external-Driver/Worker/SSE browser acceptance.
- Phase 9 static hosting, Docker/production packaging and routing integration are
  implemented and accepted after Phase 8 authorization; Phase 10 remains outside
  this checkpoint.

## Phase 9 — Production Packaging (authorized after Phase 8 PASS)

- No schema/REST DTO/scenario changes; Flyway V1–V4 stays authoritative with
  Hibernate validate. No frontend Maven module or default npm Maven execution.
- Multi-stage build generates `/app/web` outside the source tree, packages the
  independent Worker and Server, and selects a minimal `docker` Spring profile.
  Server executable uses `exec` classifier; replacing the main artifact with a
  nested Boot jar broke Failsafe class discovery and was corrected before the
  successful Server integration run.
- Conditional static resolver separates HTML navigation from API/actuator/error/
  missing assets. Root only forwards to index; hashed assets are immutable and
  index is revalidated. Development Vite proxy stays unchanged.
- Actuator health only, Platform DB/readiness, non-root runtime, stable volume
  layout and explicit persistent deployment key are documented.
- A proven shutdown race previously produced FAILED when Worker termination
  preceded interruption persistence. Context-close handling now locks unfinished
  runs and records INTERRUPTED / SERVER_SHUTDOWN before terminating/reaping
  Workers and waiting for cleanup. Crash recovery still uses SERVER_RESTART.
  Focused red/green and real child-JVM SIGTERM evidence are recorded separately.
- A second deterministic shutdown race reproduced SUCCEEDED being overwritten
  by INTERRUPTED from an already-loaded stale entity. Shutdown now queries only
  candidate IDs, then locks/loads each current row before testing terminal status.
  A completed run winning that lock race remains SUCCEEDED; no REST/schema change.
- Docker runtime-image acceptance and executable-JAR same-origin acceptance both
  passed. Phase 10 fixed Run status filtering with an explicit repository query and
  added PostgreSQL/React regressions. No release publishing or authentication was added.
