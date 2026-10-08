# 开发指南

[English](development.md) | 简体中文

本指南说明架构、本地开发、持久化、REST API 和 Worker 协议。运维见[部署指南](deployment.zh-CN.md)，授权范围、清理与结果统计见[执行安全](execution-safety.zh-CN.md)。

## 架构

```text
React / Vite → REST + SSE → Spring Boot Server
                                ├→ 平台 PostgreSQL
                                ├→ 驱动存储 / 任务工作目录
                                └→ Worker JVM → 外部 JDBC 驱动 → 目标数据库
```

| 模块 / 路径 | 职责 |
|---|---|
| `jdbc-test-core` | 场景目录、选择、请求与报告模型、共享工具 |
| `jdbc-test-suite` | JDBC 场景、JUnit 扩展、打包的 SQL 资源 |
| `jdbc-test-worker` | 驱动隔离、执行、验证、连接探测、NDJSON |
| `jdbc-test-server` | REST、持久化、调度、SSE、生产静态托管 |
| `jdbc-test-web` | 独立的 React 19 / strict TypeScript / Vite 7 npm 工程 |
| `scripts` | Python CLI、报告、回归与发布打包 |
| `adapters`、`compatibility/v1`、`ddl`、`dml`、`pool` | 适配能力、稳定场景 ID、SQL 与连接池资源 |

Server 依赖 Core，不加载 Suite 或上传的驱动类。Worker fat JAR 打包 Core、Suite、JUnit 和 SQL 资源，目标驱动由外部提供。CLI 保留 Maven/JUnit 执行链路，Web 使用 Server 调度的 Worker；两者共享场景与执行安全规则。平台 PostgreSQL 与目标数据库相互独立。

## 本地启动

需要 JDK 21、Maven >=3.9（Wrapper 3.9.15）、Node >=22.12.0 和 npm；CLI 工具需要 Python >=3.9。先准备平台 PostgreSQL、持久保存的 32 字节 Base64 master key 和可写数据目录。

在仓库根目录执行：

```sh
./mvnw -DskipTests install
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/jdbc_platform'
export SPRING_DATASOURCE_USERNAME='jdbc_platform'
export SPRING_DATASOURCE_PASSWORD='your-platform-password'
export JDBC_TEST_MASTER_KEY='your-32-byte-base64-master-key'
export JDBC_TEST_DATA_DIRECTORY="$PWD/data"
export JDBC_TEST_WORKER_EXECUTABLE="$PWD/jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar"
./mvnw -pl jdbc-test-server spring-boot:run
```

替换示例占位值，不要将凭据提交到 Git。另开终端：

```sh
cd jdbc-test-web
npm ci
npm run dev
```

打开 `http://localhost:5173`。Vite 将 `/api` 代理到 Server `:8080`，不对 SSE 设置超时。`VITE_API_BASE_URL` 可在构建时指定 API 基地址。生产环境由 Server `:8080` 托管构建产物；普通 Maven 生命周期不执行 npm。

## Web UI

路由包含 Dashboard、`/drivers`、`/connections`、`/runs`、创建表单和 `/:id` 详情。使用流程：上传并验证驱动 → 保存并探测连接 → 核对场景选择与授权范围 → 同意并运行 → 查看实时结果与报告。

TanStack Query 管理服务端状态；表单和传输状态留在组件内。`src/api/client.ts` 处理 fetch、JSON/multipart/204、取消和 RFC Problem Details。资源 DTO 不使用统一成功包装。表格采用 TanStack Table，UI 使用 Tailwind/shadcn/Radix，结果图使用 ECharts；生产代码没有模拟 API。

任务详情先读取 REST，再为活动任务打开原生 `EventSource`。SSE 更新同一份 Query 缓存，终态事件关闭连接并刷新结果。重连不改变任务状态；10 秒 REST 轮询补偿丢失的终态事件。密码只保存在组件内存，请求后清除，不写入浏览器存储、URL 或查询缓存。诊断内容以文本显示。

Dashboard 的结果只代表加载的最近 8 个任务，目录数量只代表测试资产规模。没有全局趋势、模块聚合或安全诊断接口。任务列表按状态筛选；结果支持状态或模块筛选，状态优先。UI 不生成缺少依据的统计或日志页。

### 语言与指标卡

右上角菜单在随构建打包的英文（`en`）与简体中文（`zh-CN`）间切换，不刷新整页。有效的 `jdbc-test.locale` 偏好优先，其次匹配受支持的浏览器语言，否则回退英文；存储不可用时也能正常运行。日期继续使用浏览器本地时区。稳定 ID、API 值、缓存数据和原始报告不翻译。

指标卡共用 CSS 色调 token 和浅色渐变，结果颜色保留原有语义。已完成包含明确跳过，不代表兼容性通过率。结果详情展示跳过用例的原始脱敏 `message`，当前 DTO 没有独立的结构化跳过来源/能力字段。技术场景名称及未知模块名称继续使用后端原文。

## 平台数据库

Flyway V1–V5 管理 schema；Hibernate 使用 `ddl-auto=validate`。记录使用 UUID，UTC `Instant` 存储为 `timestamp with time zone`。引用关系阻止删除历史任务使用的驱动与连接。

| 迁移 | 用途 |
|---|---|
| V1 | 驱动包与文件、连接配置、任务与模块及结果 |
| V2 | 驱动包指纹唯一约束 |
| V3 | 显式连接 `adapter_id` |
| V4 | 任务快照、已选场景、排队凭据加密 |
| V5 | 授权范围、冻结输入、证据版本、开始与完成及执行问题 |

已保存密码使用 AES-256-GCM，每次生成新的 12 字节 nonce，格式为 `v1:<base64 nonce>:<base64 ciphertext>`。`JDBC_TEST_MASTER_KEY` 提供持久保存的 32 字节密钥。实体、快照和诊断信息不包含明文。排队凭据存放在单独的加密表中，交接 Worker 及终态/恢复时删除。旧任务保留原有证据，V5 不推断其开始记录。

## REST API

列表采用有界 `page`/`size` 分页（默认 20，最大 100）、排序白名单，响应为 `{items, page, size, totalElements, totalPages}`。错误使用 RFC Problem Details，并包含稳定的 `code`。创建返回 `201` 和 `Location`，删除返回 `204`；存在历史引用时以 `409` 拒绝删除。

### 驱动

| 方法 | 接口 | 用途 |
|---|---|---|
| POST / GET | `/api/drivers` | 上传 / 列出驱动包 |
| GET / PATCH / DELETE | `/api/drivers/{id}` | 读取 / 修改元数据 / 删除 |
| POST | `/api/drivers/{id}/validate` | 通过 Worker 验证 |

上传 multipart `metadata` JSON 和重复的 `files` 字段。默认上限：20 个文件、每个 100 MiB、总计 250 MiB。二进制不可替换，更新时需上传新包。Server 仅读取 JAR 元数据/SPI；SPI 不存在或有多个候选类时必须明确指定 `driverClass`。

文件存放在 `<data-dir>/drivers/<driver-id>/<artifact-id>.jar`，用户文件名仅供显示。每个文件记录大小和 SHA-256；包指纹对排序后用换行连接的文件摘要求哈希。重复包返回 `DRIVER_PACKAGE_ALREADY_EXISTS`。修改类名会重置验证状态。Worker 验证加载驱动类但不连接数据库，默认超时 30 秒。删除已使用包返回 `DRIVER_IN_USE`。排序字段：`name`、`vendor`、`version`、`status`、`createdAt`。

### 连接

| 方法 | 接口 | 用途 |
|---|---|---|
| GET | `/api/adapters` | 内置适配包元数据 |
| POST / GET | `/api/connections` | 创建 / 列出连接 |
| GET / PATCH / DELETE | `/api/connections/{id}` | 读取 / 更新 / 删除 |
| POST | `/api/connections/test` | 探测未保存的草稿 |
| POST | `/api/connections/{id}/test` | 探测已保存设置 |

`databaseType` 是开放字符串，`adapterId` 独立选择 SQL 与能力。连接引用已验证驱动，保存 JDBC URL、用户名和授权执行范围。持久保存的 URL 拒绝凭据参数（`password`、`passwd`、`pwd`、`secret`、`token`、`access_token`），返回 `CONNECTION_URL_CONTAINS_CREDENTIALS`。

密码只写不读，响应仅提供 `hasSavedPassword`。通过 `password` 和 `savePassword: true` 保存或替换；通过 `clearSavedPassword: true` 清除，不能同时执行两种操作。临时探测密码仅对本次请求覆盖已保存密码。保存与探测独立；探测持久设置前先保存修改。

探测通过 Worker 的 `Driver.connect` 建立连接，尽力读取元数据，然后关闭退出，不执行 SQL 或测试。默认超时 30 秒。已保存连接的探测更新最后测试时间与状态，草稿不持久化。连接返回 null 时为 `DRIVER_DOES_NOT_ACCEPT_URL`；已使用连接不能删除（`CONNECTION_IN_USE`）。排序字段：`name`、`databaseType`、`lastConnectionTestAt`、`createdAt`。

### 任务

| 方法 | 接口 | 用途 |
|---|---|---|
| GET | `/api/test-catalog` | 测试配置、模块与场景 |
| POST / GET | `/api/runs` | 创建 / 列出任务（可按 `status` 筛选） |
| GET | `/api/runs/{id}` | 快照与进度 |
| POST | `/api/runs/{id}/cancel` | 取消 |
| GET | `/api/runs/{id}/events` | SSE，首先发送 `run.snapshot` |
| GET | `/api/runs/{id}/results` | 分页场景结果 |
| GET | `/api/runs/{id}/report` | 汇总与失败结果 |
| GET | `/api/runs/{id}/report.json` | 原始 JSON 证据 |

共有 17 个模块，CORE 选择 136 个场景，FULL 选择 202 个，CUSTOM 自选模块/场景。这些是目录数量，不是已验证通过的数量。新任务要求明确授权范围和 `destructiveConsent: true`。入队时冻结场景选择和非敏感执行输入，不自动重新验证驱动或探测连接。

正常状态：`QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED`。失败、取消和恢复分别进入 `FAILED`、`CANCELLED`、`INTERRUPTED`。本地 FIFO 调度器限制并发 Worker，工作目录为 `<data-dir>/runs/<run-id>`。任务行锁防止迟到结果与取消竞态覆盖终态。超时为 `FAILED / RUN_TIMEOUT`，进程清理完成前仍占用调度名额。

Server 在成功前核对事件、报告条目和数量。报告无效或不一致时返回 `REPORT_INCONSISTENT`；即使退出码为 0，FAILED/ABORTED 结果仍导致 `WORKER_EXECUTION_FAILED`。报告 DTO 为 `{summary, failures}`。协议 v2 可从持久证据重建原始 JSON，通过 `source` 和 `workerReportValidated` 标明边界。统计规则见[执行安全](execution-safety.zh-CN.md)。

## Worker 协议

Server 和 Worker 必须配套部署。每个 Worker 从 stdin 读取一个 JSON 请求，向 stdout 输出 NDJSON。密码通过 stdin 传递，不进入 argv。用户驱动的 stdout 不会破坏协议，stderr 诊断有容量限制且脱敏。每次任务用独立 `URLClassLoader`、以平台类加载器为父加载器加载选定驱动，直接调用 `Driver.connect`，不向全局 `DriverManager` 注册。

| 模式 | 用途 |
|---|---|
| 无参数 | 执行单个任务 |
| `--describe-catalog` | 输出包含 `protocolVersion: 2` 的 `CATALOG_DESCRIBED`，不加载驱动 |
| `--catalog-smoke` | 检查已打包目录，不依赖工作目录 |
| `--validate-driver` | 加载并实例化驱动，返回 JDBC 元数据，不连接数据库 |
| `--probe-connection` | 建立并关闭连接，不执行测试 SQL |

```sh
java -jar jdbc-test-worker/target/jdbc-test-worker-1.2.1-SNAPSHOT-all.jar --describe-catalog
```

Server 在加载目录和每次执行前检查协议版本。版本不匹配时返回 `WORKER_PROTOCOL_MISMATCH`，不发送执行凭据。验证/探测模式使用各自的请求契约。执行字段见 [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java)：`protocolVersion`、`runId`、`driver`、`connection`、`selection`、`workspace`、`timeoutMs`、`scope`、`objectPrefix`、`capabilities`、`destructiveConsent`。请求必须提供版本 2、明确范围与同意、`j` 加 20 位小写十六进制数字的对象前缀、全部 25 项布尔能力声明，以及所选 `connection.adapterId`。

事件包括 `RUN_STARTED`、`MODULE_STARTED`、`CASE_STARTED`、`CASE_FINISHED`、`MODULE_FINISHED`、`RUN_FINISHED`、`RUN_FAILED`。格式错误或非预期事件、错误任务 ID、未选择或重复结果导致 `WORKER_PROTOCOL_ERROR`。报告输出到 `<workspace>/report/report.json`，schema 为 `2.0.0`；退出时删除临时配置。

| 退出码 | 含义 |
|---:|---|
| 0 | 任务执行结束，Server 仍需验证结果与报告 |
| 2 | 请求或选择无效 |
| 3 | 驱动 / 连接初始化失败 |
| 4 | 执行或超时失败 |
| 5 | 目录、工作目录、报告或内部失败 |

## 测试

```sh
./mvnw clean verify
./mvnw -Pworker-e2e,server-integration verify
npm --prefix jdbc-test-web ci
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web run test
npm --prefix jdbc-test-web run build
python3 scripts/run_contract_tests.py
```

默认 Maven 测试不需要用户数据库。Docker profile 使用可丢弃的平台/目标 PostgreSQL 和 MySQL，Docker 不可用时跳过相关集成测试。`DeterministicOrchestrationTest` 使用测试专用 H2 和受控 Worker 制品检查调度、REST/SSE、取消与持久化；生产环境仍使用 PostgreSQL 和真实 Worker。

浏览器检查分为模拟 API 和真实系统两个项目：

```sh
cd jdbc-test-web
npx playwright install chromium
npm run test:e2e
export JDBC_TEST_E2E_DRIVER_JAR='/absolute/path/to/postgresql.jar'
export JDBC_TEST_E2E_DRIVER_VERSION='your-driver-version'
export JDBC_TEST_E2E_TARGET_URL='jdbc:postgresql://127.0.0.1:55433/jdbc_ui'
export JDBC_TEST_E2E_USERNAME='jdbc_ui'
export JDBC_TEST_E2E_PASSWORD='your-target-password'
npm run test:e2e:real
```

真实项目需要运行中的 Server/Worker、干净的平台数据库与驱动存储，以及单独授权的目标 PostgreSQL。缺少输入会直接失败，不回退到模拟。测试会持久保存数据；重跑时使用新的验收存储，不要连接共享或业务数据库。真实测试关闭 trace 采集。`PLAYWRIGHT_CHANNEL=chrome` 可使用已安装的 Chrome。生产模式检查见[部署指南](deployment.zh-CN.md)。

构建后执行 `JDBC_TEST_E2E_PREVIEW=true PLAYWRIGHT_CHANNEL=chrome npm --prefix jdbc-test-web run test:e2e`，可基于最新构建资源进行浏览器检查。仅运行模拟 API 项目，截图明确标注 fixture，并核对浏览器加载 bundle 与 `dist` 的 SHA-256。该检查不启动 QA，也不执行目标数据库操作。
