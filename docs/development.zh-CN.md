# 开发指南

[English](development.md) | 简体中文

修改源码使用下面的统一开发命令；只运行网站请选择[Docker 或非 Docker 部署包](deployment.zh-CN.md)。Native 是部署包运行，不是 IDEA 源码启动。

<a id="local-startup"></a>
<a id="local-source-startup"></a>
## 本地源码启动

### 第一次准备：三步

安装 **JDK21（含 javac）**、**Node >=22.12/npm**。仓库 Maven Wrapper 提供 Maven3.9.15，首次依赖下载需要网络，dev 不需要 Python。先准备专用空的开发**平台 PG** 数据库/账号及建表/migration 权限；正式 Server 启动会执行 Flyway migration。平台保存状态和加密凭据；目标库另在 UI 配置，打开页面/Catalog 不需要目标库，不使用现有 QA 或历史副本。

1. 在**源码根目录**，**仅在 `.env` 不存在时将 `.env.example` 复制为 `.env`**。已有 `.env`、Key 和数据不能覆盖。源码开发只有这一份模板，不需再选模板。
2. 用本地编辑器打开**源码根目录实际 `.env`**，填写 `SPRING_DATASOURCE_URL`（完整地址，含数据库名，不含凭据）、`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD`（PG 现有密码）。本机 PG 示例 `jdbc:postgresql://localhost:5432/jdbc_test`，填写实际主机/端口/库名/用户名。全新平台 `JDBC_TEST_MASTER_KEY` 留空；已有平台保留原 Key 和数据路径。秘密不写入终端历史、共享 IDEA 配置或 Git。
3. 在源码根目录执行 **`./jdbc-test dev`**。入口读取前保护当前所有者的本地 `.env`，先认证 PG、检查状态再生成 Key。仅全新空平台确认 `NEW`，然后打开打印的就绪地址。缺 `.env` 时立即在 Java/构建前退出并提示复制位置，不生成文件。

**终端命令 — Linux/macOS，源码根目录（复制后先编辑）：**

```sh
umask 077
cp -n .env.example .env
# 在本地编辑器打开这个实际 .env，填写数据库三项。
./jdbc-test dev
```

**PowerShell 终端替代命令 — 源码根目录：**

```powershell
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
# 先在本地编辑实际 .env，然后启动。
./jdbc-test.ps1 dev
```

**交互输入 — 实际新平台确认：**

```text
For a confirmed NEW empty platform type NEW; for existing data/recovery press Enter: NEW
```

独立32字节 Base64 Master Key 保存到同一 `.env`，不打印，用于加密已保存的目标库凭据，不是 PG 认证密码。正常启动复用原 Key；已有 schema/数据/旧配置却缺 Key 时停止，必须恢复原 Key。取消不生成替代 Key。不改账号密码、不 DROP、不跑目标测试。

密码采用字面值规则：未加引号时保留 `=` 之后所有字符（空格、`$`、`#`、反斜杠、`=`、`${NAME}`、合法 UTF-8），不能行尾注释，不做 Shell 展开。以双引号开头必须是 JSON 字符串，按 JSON 转义；双引号起始密码也可用单引号包裹。控制字符/换行拒绝，不 trim。不能 source/eval `.env`，详见[配置规则](deployment.zh-CN.md#configuration-rules)。

同一命令构建当前 Java/Suite/Worker，按**当前 POM 精确版本**找 Worker（不随意选 glob/退回旧产物），首次或 lockfile/工具链变化时安装依赖，通过 **Maven spring-boot:run 启动源码主类**和 Vite。等待 health/Catalog/页面/代理 Catalog 后打印地址；dev 不构建生产前端。

默认页面 **http://127.0.0.1:5173**，后端 **http://127.0.0.1:8080**。修改 `.env` 的 `SERVER_PORT` 或可选 `JDBC_TEST_WEB_PORT` 后重跑，代理自动跟随。占用端口则退出，不杀其他服务。只有后端收到秘密，Vite 不读取 `.env`。数据默认源码根目录 **`data/`**，可设 `JDBC_TEST_DATA_DIRECTORY`；以后复用同一路径，配置/Key/数据一起备份。

### 以后日常开发

```sh
# 源码根目录，专用平台 PG 已运行：
./jdbc-test dev
```

PowerShell 用 `./jdbc-test.ps1 dev`。复用原 `.env`、Key 和数据，不再 `--new-install`。每次入口检查构建当前 Java 模块，只在必要时重装依赖。运行中改前端通过 Vite HMR 生效，无需重构建 Java；改 Server 源码需要重启。修改 Worker/Suite 后，下次 dev 会重构建相关产物，不静默使用旧 Worker。Ctrl+C 停止本次前后端和所属子进程；一端失败会收尾另一端。PG、配置、Key 和数据保留。端口占用、构建失败、认证错误都中止，不留下半启动环境。

### 进阶：IDEA Debug 或手工源码启动

默认不用 IDEA。若希望**继续使用同一 `.env` 和自动 Worker/代理流程**调试，在 IDEA 用 JDK 21 导入根 `pom.xml`，运行：

```sh
./jdbc-test dev --debug
```

在 **Run → Edit Configurations → + → Remote JVM Debug** 新建：

| 设置 | 值 |
|---|---|
| Project SDK / Maven importer | JDK 21 |
| Debugger mode | Attach to remote JVM |
| Host / port | `127.0.0.1` / `5005`（必须空闲） |
| Module sources | `jdbc-test-server` |
| 被调试的源码主类 | `com.jdbctest.server.JdbcTestServerApplication` |

点击 Debug 连接，不另开第二个后端。调试端口只绑定 loopback。IDEA attach 不自动更新外部 Worker，修改 Worker/Suite 后停止并重跑 dev。这是源码主类，不是部署 Server JAR。**尚未实际操作 IDEA UI Run/Debug**，表格依据代码核对。

完全手工方式仍兼容旧 Maven/IDEA Application：先 `./mvnw -DskipTests install`，把 Spring 数据库/Key 配置提供给**实际后端进程**，将 `JDBC_TEST_WORKER_EXECUTABLE` 指向 `jdbc-test-worker/target/jdbc-test-worker-<当前 POM 版本>-all.jar`，运行 `./mvnw -pl jdbc-test-server spring-boot:run`。IDEA Application 使用 JDK 21、上述主类、`jdbc-test-server` 运行 classpath、源码根 working directory，无需 profile。秘密不能写入 Program arguments、VM options 或共享运行配置。统一入口读取 `.env`，**手工 Maven/IDEA 不自动读取**；终端 export 也不会更新已打开的 IDEA。两种后端启动方式任选一种。

手工前端首次 `cd jdbc-test-web && npm ci`，再 `npm run dev`；日常只需 `npm run dev`，依赖/lockfile 变化后再安装。后端端口改变时在该 Vite 进程设置非秘密 `JDBC_TEST_DEV_API_URL`（默认代理 localhost:8080）。两个手工进程分别在各自终端停止。文件、Shell 环境和 IDEA 环境不是自动共享的配置；统一流程无需同步维护多份秘密文件。

.env 字面值规则、旧 properties/环境兼容、构建/运行依赖区别和验证边界见[部署指南](deployment.zh-CN.md#configuration-rules)。启动验收不执行目标库探测或测试。

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

## Java 格式

原始来源为未改动的 [intellij-java-google-style.xml](../code-style/intellij-java-google-style.xml)，
SHA-256 为 `1f3578c03470362b9ad7c11cf9748308d3d362bcacbff4e04edd3b216565383f`。
[java.xml](../code-style/java.xml) 仅提取其中 Java 设置，并明确补充阅读约定：
展开非空方法、代码块、lambda 和简单类，声明注解分行，record 参数采用续行缩进，
长资源列表及枚举常量换行，本地变量注解分行，保留 text block 空行中的空白。
基本缩进 2 空格、续行 4 空格、不使用 Tab，100 列为目标；长 SQL、URL 和字面量保留例外。
保留来源 XML 的控制语句强制大括号规则。import 按静态导入、空行、普通导入分组。
命名、异常、日志和安全继续遵守项目约定及阿里巴巴开发规范。

IDEA 中通过 Settings → Editor → Code Style → Java → Scheme → Import Scheme →
IntelliJ IDEA code style XML 导入 `code-style/java.xml`，选择 `JdbcTestGoogleJava`。
仅对四个 module 的 Java 源码目录执行 Reformat Code；Optimize Imports 单独执行，使用同一方案。
`.editorconfig` 仅为 Java 同步缩进和列宽基础设置。

命令行使用前先保存编辑并正常退出 IDEA：

```sh
scripts/format_java.sh apply
scripts/format_java.sh check
# 可设置 IDEA_HOME 指向包含 bin/format.sh 的安装目录（macOS 为 app/Contents）。
# 两个命令也支持指定 module/src/main/java/...java 或 src/test/java/...java 文件。
```

apply/check 使用同一 IDEA 原生引擎和 XML；check 使用不写文件的 `-d`。
已在 IDEA 2026.2.3（IU-262.10968.63）验证。原生命令不会 Optimize Imports；
当前 import 块另外通过 Java 编译器源码位置重排，未增删导入，已有通配导入保留。
不替换格式引擎、不绕过正在运行的 IDE 实例锁，普通 Maven 构建不会自动改写源码。

## Web UI

路由包含 Dashboard、`/drivers`、`/connections`、`/runs`、创建表单和 `/:id` 详情。使用流程：上传并验证驱动 → 保存并探测连接 → 核对场景选择与授权范围 → 同意并运行 → 查看实时结果与报告。

TanStack Query 管理服务端状态；表单和传输状态留在组件内。`src/api/client.ts` 处理 fetch、JSON/multipart/204、取消和 RFC Problem Details。资源 DTO 不使用统一成功包装。表格采用 TanStack Table，共享 CPA 风格组件使用 Radix 交互、Sass 视觉 token 和 Tailwind 布局工具类，结果图使用 ECharts；生产代码没有模拟 API。

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

共有 17 个模块，CORE 选择 153 个场景，FULL 选择 270 个，CUSTOM 自选模块/场景。这些是目录数量，不是已验证通过的数量。新任务要求明确授权范围和 `destructiveConsent: true`。入队时冻结场景选择和非敏感执行输入，不自动重新验证驱动或探测连接。

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

Server 在加载目录和每次执行前检查协议版本。版本不匹配时返回 `WORKER_PROTOCOL_MISMATCH`，不发送执行凭据。验证/探测模式使用各自的请求契约。执行字段见 [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java)：`protocolVersion`、`runId`、`driver`、`connection`、`selection`、`workspace`、`timeoutMs`、`scope`、`objectPrefix`、`capabilities`、`destructiveConsent`。请求必须提供版本 2、明确范围与同意、`j` 加 20 位小写十六进制数字的对象前缀、全部 34 项布尔能力声明，以及所选 `connection.adapterId`。

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


## 统一前端 UI

CPA Keeper 白色 UI 基础层固定引用上游 [`2c09d65d6a90b1dc9802fe7b26704a1d95d0c909`](https://github.com/Willxup/cpa-usage-keeper/tree/2c09d65d6a90b1dc9802fe7b26704a1d95d0c909)，MIT © 2026 Will。完整许可、来源路径和适配说明随构建分发：`jdbc-test-web/public/third-party-notices.txt`。

- 直接移植：`web/src/styles/menu.scss` → `src/styles/upstream/_menu.scss`。
- 适配：白色主题 token、Button/Input/Card/Select/Modal/EmptyState 展示、顶栏和 DashboardToolbar 布局 → `src/styles/foundation.scss` 及共享组件。使用命名空间、统一蓝色主操作、现有 Radix 交互、语义表格与 React Router，替换上游业务依赖。
- 本地补充：JDBC URL 查看/复制、稳定 ID 运行标题和快照分组、指标与执行语义、Radix Tooltip。未引入 CPA 账号/Token/成本/认证、Chart.js 或 Zustand。

控件、卡片和弹窗统一使用 `components/ui`，指标/加载/空/错误态使用 `components/common`，表格分页使用 `components/data-table`。Select 保留选项原始值，展开后使用 portal Radix listbox；不新增原生 select 或页面专用菜单皮肤。Tailwind 负责布局，`foundation.scss` 统一视觉 token 和控件表面，`index.css` 将 token 映射到 Tailwind 并保留指标、选中态语义。Sass 固定为 1.99.0，中英文资源仍本地打包。

使用 `npm run lint`、`npm run test`、`npm run build` 和 mocked `npm run test:e2e` 验证。显式选择的 `history-readonly` Playwright 项目仅读取保全历史，不创建运行或测试连接；需已核验的历史副本后端、`JDBC_TEST_HISTORY_READONLY=true` 和当前构建 preview。原有 `real` E2E 仍需独立可写验收数据库，禁止用于历史 QA。

弹窗内菜单、tooltip/toast 的测试专用页面使用 `PLAYWRIGHT_CHANNEL=chrome npx playwright test --project=primitives`（Vite dev，非 preview），入口不进入生产路由或构建。

### 测试驱动自身的 DataSource 实现

上传驱动及“编辑元数据”中可配置 DataSource、ConnectionPoolDataSource、XADataSource 的实现类。
实现类须来自上传的驱动 JAR；配置后启用 FULL 配置中的对应接口用例，留空则明确跳过，不计为通过。
平台连接包装层仅由框架单元测试验证。任务入队时冻结实现类配置，报告包含此配置，不根据厂商推断类名。

CLI/运行 YAML 的 `db.data_sources` 对应 API 的 `dataSources`：

```yaml
db:
  data_sources:
    dataSource:
      className: org.postgresql.ds.PGSimpleDataSource
      urlProperty: URL
      userProperty: user
      passwordProperty: password
    connectionPoolDataSource:
      className: org.postgresql.ds.PGConnectionPoolDataSource
    xaDataSource:
      className: org.postgresql.xa.PGXADataSource
```

属性名对应公开的单参数 JavaBean setter，默认 `URL`、`user`、`password`。
附加 `db.properties` 使用 String/int/long/boolean 类型的 setter；不允许覆盖连接凭据属性。
这些配置填写属性名，不填写凭据。XA 用例还要求数据库满足 XA 的运行条件。
MySQL 实现示例为 `com.mysql.cj.jdbc` 包的 `MysqlDataSource`、`MysqlConnectionPoolDataSource`、
`MysqlXADataSource`；Oracle 为 `oracle.jdbc.pool.OracleDataSource`、
`oracle.jdbc.pool.OracleConnectionPoolDataSource`、`oracle.jdbc.xa.client.OracleXADataSource`。

Java 运行配置的 `db.password` 是字面字符串，保留 `${...}`、反斜杠和空白字符。
需要环境变量时显式写 `db.password_env: MY_DB_PASSWORD`，仅读取该变量，变量值不会再次展开；
变量未设置或同时配置 `password` 与 `password_env` 时直接报错。
CLI 用户配置使用 `password_env`，生成私有的字面密码运行配置。凭据不得提交到 Git。
