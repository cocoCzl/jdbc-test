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
3. 在源码根目录执行 **`./jdbc-test dev`**。入口读取前保护当前所有者的本地 `.env`，先认证 PG、检查状态再生成 Key。全新空平台检查通过后自动保存 Key，无需交互确认，然后打开打印的就绪地址。缺 `.env` 时立即在 Java/构建前退出并提示复制位置，不生成文件。

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

独立32字节 Base64 Master Key 保存到同一 `.env`，不打印，用于加密已保存的目标库凭据，不是 PG 认证密码。正常启动复用原 Key；已有 schema/数据/旧配置却缺 Key 时停止，必须恢复原 Key。不改账号密码、不 DROP、不跑目标测试。

密码采用字面值规则：未加引号时保留 `=` 之后所有字符（空格、`$`、`#`、反斜杠、`=`、`${NAME}`、合法 UTF-8），不能行尾注释，不做 Shell 展开。以双引号开头必须是 JSON 字符串，按 JSON 转义；双引号起始密码也可用单引号包裹。控制字符/换行拒绝，不 trim。不能 source/eval `.env`，详见[配置规则](deployment.zh-CN.md#configuration-rules)。

同一命令构建当前 Java/Suite/Worker，按**当前 POM 精确版本**找 Worker（不随意选 glob/退回旧产物），首次或 lockfile/工具链变化时安装依赖，通过 **Maven spring-boot:run 启动源码主类**和 Vite。等待 health/Catalog/页面/代理 Catalog 后打印地址；dev 不构建生产前端。

默认页面 **http://127.0.0.1:5173**，后端 **http://127.0.0.1:8080**。修改 `.env` 的 `SERVER_PORT` 或可选 `JDBC_TEST_WEB_PORT` 后重跑，代理自动跟随。占用端口则退出，不杀其他服务。只有后端收到秘密，Vite 不读取 `.env`。数据默认源码根目录 **`data/`**，可设 `JDBC_TEST_DATA_DIRECTORY`；以后复用同一路径，配置/Key/数据一起备份。

### 以后日常开发

```sh
# 源码根目录，专用平台 PG 已运行：
./jdbc-test dev
```

PowerShell 用 `./jdbc-test.ps1 dev`。复用原 `.env`、Key 和数据，无需初始化参数或交互确认。每次入口检查构建当前 Java 模块，只在必要时重装依赖。运行中改前端通过 Vite HMR 生效，无需重构建 Java；改 Server 源码需要重启。修改 Worker/Suite 后，下次 dev 会重构建相关产物，不静默使用旧 Worker。Ctrl+C 停止本次前后端和所属子进程；一端失败会收尾另一端。PG、配置、Key 和数据保留。端口占用、构建失败、认证错误都中止，不留下半启动环境。

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
SHA-256 为 `f25e4a8d94f2b2d33dd943cb672f570b64fb89e680df63d3795ad244505d0d26`。
[java.xml](../code-style/java.xml) 仅提取其中 Java 设置，并明确补充阅读约定：
展开非空方法、代码块、lambda 和简单类，声明注解分行，record 参数采用续行缩进，
长资源列表及枚举常量换行，本地变量注解分行，保留 text block 空行中的空白。
基本缩进 4 空格、续行 4 空格、不使用 Tab，100 列为目标；长 SQL、URL 和字面量保留例外。
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
另接受开发工具的精确路径 `scripts/CheckJavaRules.java`；默认目录仍只有四模块，检查器自身需显式指定。
已在 IDEA 2026.2.3（IU-262.10968.63）验证。原生命令不会 Optimize Imports；
当前 import 块另外通过 Java 编译器源码位置重排，未增删导入，已有通配导入保留。
不替换格式引擎、不绕过正在运行的 IDE 实例锁，普通 Maven 构建不会自动改写源码。

### Java 规则与验证边界

格式以项目 `code-style/java.xml` 为准，其来源是保留的用户原始 XML；`.editorconfig`
只同步 Java 缩进与列宽。不得替换为 google-java-format、Spotless 或另一格式器，不将此 XML
用于其他语言。命名、异常、日志、集合、并发和安全采用阿里巴巴 Java 开发手册
**黄山版 1.7.1（2022-02-03）**的适用章节，来源为[官方 p3c 仓库](https://github.com/alibaba/p3c)。
项目明确约定优先，格式冲突按本项目 XML 处理。该版本用于人工审查，不表示安装插件或执行
下列检查就完整覆盖了手册。

审查命名与职责、具体异常与原因保留、有意义且不含凭据的日志、集合及泛型契约、线程与资源
归属、输入校验。JDBC 场景可以故意调用过时 API 或制造 SQL 异常，Worker stdout 承载 NDJSON
协议；不得因此禁止必要测试、将协议改成普通日志、吞掉失败或伪造兼容结论。
只删除有证据表明冗余的抑制；必要抑制应范围最小并说明原因。不得新增
`SuppressWarnings("all")`、`noinspection` 或关闭 inspection 来隐藏问题。核对实际源码，
不能根据 IDE 折叠显示判断排版。不得为通过语法检查给故意忽略的异常添加无意义日志或改变传播。

开发检查器仅使用 JDK 21 编译器公开树 API 解析语法，禁用注解处理，不进行类型归属/语义分析，
不加载应用类。在源码 checkout 中运行，扫描四模块全部 `src/main/java`、`src/test/java` 中的 `.java`，以及
`scripts/CheckJavaRules.java`，没有文件或目录排除机制。

| 规则 | 违规示例 | 合法处理与限制 |
|---|---|---|
| `wildcard-import` | `import java.util.*;` 或静态 `.*` import | 显式导入通过；已有通配导入逐项基线保留。不能判断无用 import，也不解析符号。 |
| `empty-catch` | catch 体没有 AST 语句，包括 `{ /* 说明 */ }` | 实际恢复、重新抛出或 return 语句通过本条语法检查，正确性仍需审查。确需忽略的异常逐项审查，注释不自动豁免。不能识别无意义语句或判断吞异常是否安全。 |
| `suppress-all` | `@SuppressWarnings("all")`、`value = "all"` 或数组中出现字面量 `"all"` | 不将 `"unchecked"`/`"deprecation"` 判为违规；冗余性需语义/IDE 审查。识别全限定 `java.lang.SuppressWarnings` 和带括号字面量；javac 在解析期也会折叠相邻字符串字面量。不解析命名常量或一般表达式；按语法名称匹配可能误报同名自定义注解。 |

注释、普通字符串、text block 中的伪代码不计为违规。无基线扫描发现任一问题即失败；正常 CI
拒绝新增、次数增加及已过期的基线。正式配置 `code-style/java-rules-baseline.json` 仅记录已审查
实例，格式为 `[相对路径, 规则, 所属类/方法签名, AST 内容, 次数]`，行号仅用于定位。
因此可以保留历史通配导入和空 catch，而不修改业务代码。同一声明增加一处会超过次数，移动到
另一声明会改变身份。修复后必须手动删除或减少对应基线。工具没有写基线模式；不得重新生成
基线或增加额度使失败变绿。新的有意例外必须对该具体实例及理由单独审查。

```sh
python3 -B -m unittest discover -s scripts -p 'test_java_rules.py' -v
python3 -B scripts/check_java_rules.py
# 只读盘点，忽略基线；存在任一问题时退出 1。
python3 -B scripts/check_java_rules.py --inventory
# 原生格式检查仅指定 Java 文件，先保存并正常退出 IDEA。
scripts/format_java.sh check scripts/CheckJavaRules.java
git diff --check
```

检查器退出码：**0** 表示没有新增/过期基线，**1** 表示规则或基线不匹配，**2** 表示输入无效、
解析失败、缺 JDK 工具、编译/执行错误或超时。八个源码根必须存在且包含 Java 文件；目录不可读、源码符号链接、
缺失路径、基线格式错误或重复条目、工具输出不一致均报错退出。编译临时产物在成功/失败后
删除；工具不进入 Maven 或应用运行时依赖。CI 实际执行检查器契约测试和上述全部范围扫描；
fixture 覆盖 record、text block、注释/字符串、注解形式、基线次数和执行异常。它们只提供语法
检查证据，不代表 JDBC 兼容性。当前 CI 未配置 IDEA 原生运行环境。

交付时分别报告：有限静态规则、检查器契约测试、IDEA 原生格式、`git diff --check`。
编译和语法检查均不能证明完整 Alibaba 合规、无用 import、unchecked 抑制冗余、命名/异常/
日志/并发正确性或全量格式。缺 IDEA 或实例锁阻塞时原生格式标记为 **BLOCKED**，不能写通过；
不强关 IDE、不删锁、不改用户配置、不反复重试。正常退出 IDEA 后补跑上述指定文件命令。
Optimize Imports 和 IDE inspection 仍需独立审查。

只提交经审查的代码、自动化测试、必要 fixture/检查配置及正式项目文档。任务书、执行日志、
审查报告、截图、证据和审查 ZIP 只留在现有忽略目录 `.local/` 或仓库外，不新增
`docs/quality-evidence`。本地 `AGENTS.md` 未经单独批准保持忽略、未跟踪。保留工作区修改、
配置、Key、数据和附件；验证失败时也清理本轮创建的资源，不删除未知归属资源。
未经授权不重写历史、不 push/PR/tag/release。

## Web UI

路由包含 Dashboard、`/drivers`、`/connections`、`/runs`、创建表单和 `/:id` 详情。使用流程：上传并验证驱动 → 保存并探测连接 → 核对场景选择与授权范围 → 同意并运行 → 查看实时结果与报告。

TanStack Query 管理服务端状态；表单和传输状态留在组件内。`src/api/client.ts` 处理 fetch、JSON/multipart/204、取消和 RFC Problem Details。资源 DTO 不使用统一成功包装。表格采用 TanStack Table，共享 CPA 风格组件使用 Radix 交互、Sass 视觉 token 和 Tailwind 布局工具类，结果图使用 ECharts；生产代码没有模拟 API。

任务详情先读取 REST，再为活动任务打开原生 `EventSource`。SSE 更新同一份 Query 缓存，终态事件关闭连接并刷新结果。重连不改变任务状态；10 秒 REST 轮询补偿丢失的终态事件。密码只保存在组件内存，请求后清除，不写入浏览器存储、URL 或查询缓存。诊断内容以文本显示。

Dashboard 的结果只代表加载的最近 8 个任务，目录数量只代表测试资产规模。没有全局趋势、模块聚合或安全诊断接口。任务列表按状态筛选；结果在数据库分页前组合状态与模块条件（AND），以场景 ID 作为稳定次级排序。UI 不生成缺少依据的统计或日志页。

### 语言与指标卡

右上角菜单在随构建打包的英文（`en`）与简体中文（`zh-CN`）间切换，不刷新整页。有效的 `jdbc-test.locale` 偏好优先，其次匹配受支持的浏览器语言，否则回退英文；存储不可用时也能正常运行。日期继续使用浏览器本地时区。稳定 ID、API 值、缓存数据和原始报告不翻译。

指标卡共用 CSS 色调 token 和浅色渐变，结果颜色保留原有语义。已完成包含明确跳过，不代表兼容性通过率。结果详情展示跳过用例的原始脱敏 `message`，当前 DTO 没有独立的结构化跳过来源/能力字段。技术场景名称及未知模块名称继续使用后端原文。

## 平台数据库

Flyway V1–V8 管理 schema；Hibernate 使用 `ddl-auto=validate`。记录使用 UUID，UTC `Instant` 存储为 `timestamp with time zone`。引用关系阻止删除历史任务使用的驱动与连接。

| 迁移 | 用途 |
|---|---|
| V1 | 驱动包与文件、连接配置、任务与模块及结果 |
| V2 | 驱动包指纹唯一约束 |
| V3 | 显式连接 `adapter_id` |
| V4 | 任务快照、已选场景、排队凭据加密 |
| V5 | 授权范围、冻结输入、证据版本、开始与完成及执行问题 |
| V6 | 驱动自身的 DataSource 提供者 |
| V7 | 清理证据和原 Worker 终止原因 |
| V8 | 产品/驱动观察元数据和预检结论 |

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

Oracle 驱动包可能需要配套组件。对 `oracle.jdbc.*` 驱动，验证会在**选中的隔离类加载器**中
检查字符集转换器和 XML 类及其方法签名；不可解析时，通过新增 `warnings` 返回
`ORACLE_I18N_MISSING` / `ORACLE_XML_MISSING`。警告不会将只使用内置字符集、不涉及 XML 的
驱动一律判为无效。页面展示本次响应，警告不持久化为兼容性结论。这是有限组件检查，
不解析完整依赖闭包，也不保证任意厂商版本可以混用。

请上传**新的多 JAR 包**，同时包含厂商兼容版本的 `ojdbc`、
`com.oracle.database.nls:orai18n`，SQLXML 还需 `com.oracle.database.xml:xdb` 和
`com.oracle.database.xml:xmlparserv2`。CLI 也可将全部 JAR 放在同一本地驱动目录中。
具体以所选驱动版本的厂商要求为准，不能仅凭文件名判定有效。这些 JAR 属于外部被测输入，
不加入应用 POM，不静默在线下载。

Oracle 执行预检在命名空间/夹具创建前，只读查询 `NLS_DATABASE_PARAMETERS`，并使用隔离驱动
验证数据库及国家字符集的实际转换。读取失败、信息缺失或转换失败以
`ORACLE_CHARSET_PREFLIGHT_FAILED` 停止并保留原因。XML 组件只在实际选中的 SQLXML 方法准备时检查，
缺少时该用例以 `ORACLE_XML_MISSING` 报告执行前提不足，不阻止其他标准用例。
旧 `preflight.require_sqlxml` 字段保留解析兼容，但不再触发全局 XML 拒绝；不依赖 `sqlxml` 能力布尔值。
普通连接探测继续不执行 SQL。这些检查针对 Oracle 的 `oracle.jdbc.*` 实现，其他驱动保留现有行为。
缺依赖导致的失败不能被解释为数据库兼容性失败。

### 适配注册与执行声明

`adapters/catalog.json` 是唯一的内置注册索引，只列 ID。名称、trust、版本、能力与驱动提示
仍属于各自的 `adapters/<id>/adapter.json`。Core 每个类加载器一次加载并完整校验索引，生成
不可变的 `AdapterRegistry`；`AdapterCatalog` 和 `AdapterExecutionConfig.load` 投影同一份定义。
缺资源、重复 ID、目录/声明 ID 不一致或声明损坏均明确失败，`contains` 也不会绕过校验。
Core 只校验元数据，不加载 Suite、SQL 或被测驱动；Suite 打包测试检查声明的 SQL 区域与连接池配置。
索引包使用现有 classpath 根 `ddl`、`dml`、`pool`，由 `assets.directory` 独立选择资产包。
CLI 使用同一个索引和严格元数据契约；明确指定的本地 manifest 不需要注册，并保留本地资产路径语义。

`DbType` 只表达已知产品的稳定 code，不是注册白名单。自定义适配可用可选 `db_type` 分类；
省略时，已知旧 ID 保留产品分类，未知 ID/分类保留原声明但没有已知分类。`adapterId` 和自由标签
`databaseType` 继续为字符串。执行以冻结的 `dialect` 为准，不读取展示标签或驱动 vendor 来猜测。
当前声明支持 `postgresql`、`mysql`、`oracle`、`sqlserver`；GaussDB 已声明 PostgreSQL 行为，
其身份约束和资产保持独立。Suite 原有 generic 回退仅用于旧配置，不能注册为新的 manifest 方言。
范围选择、Oracle 标识符规范化、引用及已拥有索引的清理使用这些有限行为。

Core 只依赖 `tools.jackson.core:jackson-core`，不引入 Boot Starter 或 databind。
现有 Boot BOM 基线为 3.1.4；本次仅将 Jackson 3 core 显式管理为仍维护的 3.1 LTS 分支
已发布补丁 **3.1.7**。依据为[官方维护说明](https://github.com/FasterXML/jackson/wiki/Jackson-Releases)
和 [Central 版本元数据](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-core/maven-metadata.xml)。
不升级 Spring Boot 或两端 databind。Server 只解析出一份 Jackson 3 core；Worker 暂时同时包含
原有 Jackson 2 与 Jackson 3 core，增加约 589 KiB。两者包名不同，Worker 协议序列化仍用 Jackson 2。
被测 JDBC 驱动继续作为外部输入，由 Worker 的隔离加载器加载。

声明使用 UTF-8 严格 JSON，拒绝重复键、YAML 语法、注释、尾随根、非有限数常量和尾逗号。
上限为 1 MiB、64 层容器及 128 字符数值 token。浮点扩展值须处于有限双精度范围，避免 Python
输出 Infinity。校验必需字段、类型、trust、完整布尔能力、
版本区间及资源路径。Java 不可变保留完整 JSON 树，包括未知扩展字段及 null；扩展不会自动成为执行覆盖值。
原来省略 revision、引用符及 pool 时的默认值仍为 `release`、`"`、`pool`；新增必填字段须另做兼容决策。
Java/Python 的正则引擎并不完全相同，身份表达式应保持可移植；fixture 不证明所有正则等价。

`adapterExecution` 原有传输字段与协议版本 2 不变。历史请求缺少执行快照时，继续回退到内置适配。
Worker 范围行为取自冻结快照；元数据身份/版本预检继续先于命名空间创建和 fixture 写入。

执行 `python3 -B scripts/run_contract_tests.py` 和 `./mvnw verify`。共享正反例位于
`jdbc-test-core/src/test/resources/adapter-contract`，两种语言均执行。检查覆盖声明、打包、冻结输入、
对象归属与既有安全回归，不证明真实数据库兼容性。对修改的 Java 文件运行原生格式检查，并分别报告
上文四项质量检查。调查日志与依赖证据只留本地。

### 新增适配与准备驱动

复用已有方言时，复制现有 `adapters/<id>/adapter.json` 到新目录（例如 `adapters/private-db/`），
设置唯一 `id`、名称、身份/版本约束及如实声明的能力。没有充分证据前使用 `trust: "candidate"`
或 `"local"`，不要把其他产品的已验证组合复制成自身证据。`dialect` 选择已实现行为；
`assets.directory` 可指向可复用资产包，也可补充所需的 `ddl/<asset-id>`、`dml/<asset-id>`
与 `pool/<asset-id>.yaml`。最后只在 `adapters/catalog.json` 登记一次 ID。
无需新增 `DbType` 常量、Java 产品分支或前端名单。`private-db` 仅为 fixture/示例身份，
不是正式支持产品；共享契约和 API fixture 已覆盖这一扩展路径。

真正的新方言须先实现最小的 `SqlDialect` 行为、对应资产与失败/清理测试，再登记声明。
只加产品枚举常量并不等于实现兼容。先在仓库根目录执行以下检查，再使用已授权的可丢弃目标验证：

```bash
python3 -B scripts/run_contract_tests.py
./mvnw verify
cd jdbc-test-web
npm run lint
npm test
npm run build
```

未注册的私有 CLI 包仍可通过显式 manifest 路径使用。现有生成器可在不修改内置目录的情况下准备本地包
（请在本地替换占位值）：

```bash
python3 -B scripts/runner.py init-custom --id private-db --dialect postgresql \
  --driver-class com.example.Driver --driver-dir /absolute/path/to/driver-jars \
  --url jdbc:postgresql://target.example/test_db --username test_user \
  --namespace authorized_test_schema
```

执行前审查生成的身份、版本及能力声明；这条命令只是准备，不证明兼容。生成配置、私有 manifest 与 JAR 保持本地。

manifest 中的 `org.postgresql:postgresql:42.7.2` 仅为声明示例，不是最新版本或安全推荐。
CLI 可通过 `db.driver` 指定其他固定版本（`kind: "maven"`、`coordinate: "group:artifact:version"`），
或使用本地 JAR（`kind: "local"`、`path: "/.../driver-jars"`）。当前 CLI Maven 解析仅将主 JAR
加入运行 classpath；下载到缓存的传递依赖不等于完整加载。多 JAR 驱动应使用现有本地目录路径，
或在 Web 中将所需 JAR 一并上传到同一驱动包。保留私有/离线路径，不提交驱动二进制。
更改推荐不会替换已安装包的 fingerprint 或历史运行。平台自身的 PostgreSQL 依赖用于平台存储；
被测驱动仍是隔离 Worker 加载的外部输入。没有新增在线 Web Maven 导入功能。

连接编辑器分别展示适配声明提示、声明的版本组合、已安装驱动验证状态及目标探测结果；
`databaseType` 保持为自定义显示标签。任务向导按适配筛选已保存连接，服务端先筛选再分页，
不改写连接适配，也不根据 vendor 限制驱动。深链接所选连接与筛选冲突时会明确保留并提示，
清除筛选或另选连接后才可继续。已保存连接探测区分未提供密码（回退已保存凭据）与显式 `""`；
空格及其他密码字符均原样保留。

### 连接

| 方法 | 接口 | 用途 |
|---|---|---|
| GET | `/api/adapters` | 内置适配包元数据 |
| POST / GET | `/api/connections` | 创建 / 列出连接 |
| GET / PATCH / DELETE | `/api/connections/{id}` | 读取 / 更新 / 删除 |
| POST | `/api/connections/test` | 探测未保存的草稿 |
| POST | `/api/connections/{id}/test` | 探测已保存设置 |

`databaseType` 是开放字符串，`adapterId` 独立选择 SQL 与能力。连接引用已验证驱动，保存 JDBC URL、用户名和授权执行范围。创建、更新、草稿探测和执行路径拒绝 Oracle Thin/OCI 内嵌用户名/密码片段及凭据参数（`password`、`passwd`、`pwd`、`secret`、`token`、`access_token`），返回 `CONNECTION_URL_CONTAINS_CREDENTIALS`。

密码只写不读，响应仅提供 `hasSavedPassword`。通过 `password` 和 `savePassword: true` 保存或替换；通过 `clearSavedPassword: true` 清除，不能同时执行两种操作。临时探测密码仅对本次请求覆盖已保存密码。保存与探测独立；探测持久设置前先保存修改。未提供/null 表示沿用已保存值（含已保存的空字符串）；没有保存值则返回 `CONNECTION_PASSWORD_REQUIRED`。显式 `""` 覆盖为原值空串，空格与 Unicode 原样保留。保存空串生成非空且可认证的 v1 密文，`hasSavedPassword: true`；更新未提交新密码保留原密文。清除与提供任何新密码（包括空串）冲突。前端必须明确勾选空密码，默认未填不代表此意图。CLI 密码环境变量同样区分未设置和已设为空。

探测通过 Worker 的 `Driver.connect` 建立连接，尽力读取元数据，然后关闭退出，不执行 SQL 或测试。默认超时 30 秒。已保存连接的探测更新最后测试时间与状态，草稿不持久化。连接返回 null 时为 `DRIVER_DOES_NOT_ACCEPT_URL`；已使用连接不能删除（`CONNECTION_IN_USE`）。排序字段：`name`、`databaseType`、`lastConnectionTestAt`、`createdAt`。

### 任务

| 方法 | 接口 | 用途 |
|---|---|---|
| GET | `/api/test-catalog` | 测试配置、模块与场景数量 |
| POST / GET | `/api/runs` | 创建 / 列出任务（可按 `status` 或 `statusGroup` 筛选） |
| GET | `/api/runs/{id}` | 快照与进度 |
| POST | `/api/runs/{id}/cancel` | 取消 |
| GET | `/api/runs/{id}/events` | SSE，首先发送 `run.snapshot` |
| GET | `/api/runs/{id}/results` | 分页场景结果 |
| GET | `/api/runs/{id}/report` | 汇总与失败结果 |
| GET | `/api/runs/{id}/report.json` | 原始 JSON 证据 |

模块与配置的场景数量以当前目录为准；CUSTOM 自选模块/场景。这些目录数量不是已验证通过的数量。新任务要求明确授权范围和 `destructiveConsent: true`。入队时冻结场景选择和非敏感执行输入，不自动重新验证驱动或探测连接。

历史列表支持 `statusGroup=WAITING`（QUEUED）、`IN_PROGRESS`（PREPARING、历史 PROBING、RUNNING、GENERATING_REPORT）、`COMPLETED`（SUCCEEDED）、`ABNORMAL`（FAILED、INTERRUPTED）、`CANCELLED`（CANCELLED）。两者都不传则查询全部；旧 `status` 精确查询继续有效。两者同时使用返回 HTTP 400 / `CONFLICTING_STATUS_FILTERS`；分组非法或为空返回 HTTP 400 / `INVALID_STATUS_GROUP`。分组条件在数据库分页及计数之前生效。响应保留内部状态，无需迁移历史数据。当前执行转换没有进入 PROBING 的路径，仍保留其历史显示与筛选兼容。

正常状态：`QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED`。失败、取消和恢复分别进入 `FAILED`、`CANCELLED`、`INTERRUPTED`。本地 FIFO 调度器限制并发 Worker，工作目录为 `<data-dir>/runs/<run-id>`。任务行锁防止迟到结果与取消竞态覆盖终态。超时为 `FAILED / RUN_TIMEOUT`，进程清理完成前仍占用调度名额。用户主标签使用生命周期分组，活动阶段及已记录的异常原因另行显示。SUCCEEDED 显示“已完成”，不表示所有用例通过：现有完成检查仍拒绝失败断言和非允许型中止，契约明确允许的不支持则可正常完成。结果统计和证据保留原意，异常结束也可包含部分有效结果。展示分组不参与状态转换、终态判断、取消、轮询或 SSE 控制。

Server 在成功前核对事件、报告条目和数量。报告无效或不一致时返回 `REPORT_INCONSISTENT`；除明确允许的不支持外，即使退出码为 0，FAILED/ABORTED 结果仍导致 `WORKER_EXECUTION_FAILED`。报告 DTO 为 `{summary, failures}`。协议 v2 可从持久证据重建原始 JSON，通过 `source` 和 `workerReportValidated` 标明边界。统计规则见[执行安全](execution-safety.zh-CN.md)。

### 测试范围与执行语义（选择版本 2）

实际链路为版本化场景清单 → Worker `--describe-catalog` → `RunCatalogService` → 目标目录 API → 新建任务 → `ScenarioSelection` 统一解析 → 冻结场景表和 executionInput → 隔离 Worker → JUnit 扩展 → CASE 事件 → 持久化与报告。Server 不加载测试类，目录扫描也不加载厂商类。

当前实现 270 项、17 个模块，均为 `STANDARD_JDBC`；覆盖现有连接/事务、Statement/PreparedStatement/CallableStatement、ResultSet、元数据、保存点、标准 LOB/XML/数组、RowSet、DataSource/池/XA 场景，**并非全部 JDBC 接口**。目前没有真正调用专有 API 的 `VENDOR_EXTENSION` 用例。扩展机制使用清单中的 `test_category` 和显式 `applicable_adapters`，通过测试夹具验证；不能从模块、方言或能力猜测。标准测试适用于所有适配器，Blob/Clob 仍属标准测试。`jdbc_version` 标注该测试的 JDBC 运行基线，`execution_prerequisites` 标注 Provider 与 SQL 资产，实际准备阶段会核实条件；驱动不实现可选 API 的响应仍需实测。

新建任务在选择连接、驱动后默认全选适用目录。模块默认折叠，复选框显示全部/部分选择，展开后按用例调整；支持名称/稳定 ID 搜索。全选、清空、恢复默认及模块复选框作用于全部用例，不仅是搜索结果。刷新目录保留部分选择；切换目标保留仍适用项，新出现的适用项默认选中，移除不适用项并提示。缓存键包含连接、适配器和驱动。核对页列出已选/排除数及已知 Provider 缺失项；其他 SQL/权限/JAR 条件在执行前检查。全不选无法提交。

兼容规则：旧 API 显式 CORE 保留原有 153 项清单，成员由清单的 `category: core` 显式维护，不再由注解推断；FULL 是全部适用测试。CUSTOM 用于编辑后的选择，不能三个选择列表全空。模块和 includeScenarios 取交集，再应用 excludeScenarios（排除优先）；FULL/CORE 中空列表表示不增加该过滤，CUSTOM 空模块但非空 include 表示精确用例集合。未知/已删除/目标不适用的 ID 均拒绝，最终空集合拒绝。页面全不选不会提交为 FULL。带旧 catalogVersion 的新建/复制/重跑请求返回 CATALOG_CHANGED；当前没有独立复制/重跑接口，调用方需重新核对当前目录及 ID。

新任务沿用 executionInput 和所选场景表：保存目录内容哈希、选择语义版本、原始选择依据、用户排除项、最终用例元数据（类别、版本、类/方法映射、契约）、适配器/驱动/Provider 和预算。Worker 执行冻结元数据，不用当前 CORE 成员重新裁剪；缺失的旧测试类/方法明确记录条件不足，不替换用例。旧任务缺少这些信息时不反推；协议仍为 2，Server/Worker 要配套部署。

能力审计：A 类为除 Provider 三项以外的全部现有 capability 声明（保存点、可调用语句、数组/REF/STRUCT/ROWID/XML/RowSet、LOB、参数元数据、可滚动/可更新结果、生成键、多结果、取消、超时、ClientInfo、request/sharding、java_time 等），保留声明作为证据，false 不再通用禁用。B 类为 data_source、connection_pool、xa_data_source 的实际 Provider 配置，以及 SQL 资产/对象形状、权限、Oracle XML 配套 JAR；缺失呈现 PREREQUISITE_UNMET，包括所选驱动包找不到已配置的 Provider 类；不把构造器、属性设置及实际接口调用的所有错误都包装成前提不足。C 类目前没有显式前置用例依赖图；已失败的共享 SQL 准备会阻塞依赖该资源的后续用例，并保留原始异常、准备路径及阻塞用例 ID。D 类改由显式扩展适用范围表达，目前无实际扩展用例。

`@UseSqlScripts` 延迟到选中方法的 beforeEach；同类共享成功准备，失败只影响依赖同一资产的用例。方法上的空注解取消类级准备；连接、元数据、内存 SQLXML API 等不需要表的方法不执行无关 DDL。数组夹具验证实际列形状，Oracle 以 JSON/VARCHAR 替代数组的旧 SQL 不会冒充数组 API 测试已运行。Kingbase 隔离目录保留原 PostgreSQL 基线资源及明确的验证范围；新增准备资源仍待针对性实库验证，失败保留 SQLState/厂商码并标明准备阶段。获取注入连接的实际认证或连接失败记录为 PREPARATION 阶段的 CONNECTION_FAILURE，保留原始错误，不归为平台前提不足；即使 JUnit 已发布执行回调，也不计契约调用。测试体中的 SQL 仍按原契约判定，不把所有 SQL 错误归为环境问题。公共断言未放宽；holdability 用例实测并记录 supports 声明是否一致。

执行状态 PASSED/FAILED/SKIPPED/ABORTED 与新增结论分开。CONFORMS、CONTRACT_VIOLATION、UNSUPPORTED_ALLOWED、UNSUPPORTED_REQUIRED、PREREQUISITE_UNMET、BLOCKED_PREREQUISITE、TIMEOUT、CONNECTION_FAILURE、PLATFORM_ERROR、NOT_EVALUATED 保留类别、阶段、耗时、脱敏异常/SQLState/厂商码、声明及观察。只在特定 API 调用处通过 `JdbcContracts.optional` 接受 SQLFeatureNotSupportedException：Connection.createBlob/createClob/createSQLXML 和 CommonDataSource.getParentLogger；后续对象操作仍使用原断言。允许不支持记录 ABORTED + contractConforms=true，不计 PASSED，并明确展示功能不支持。`JdbcContracts.required` 当前明确约束 Connection.getAutoCommit，不支持该必需 API 记录为违反契约。未经具体调用确认的 SQLFeatureNotSupportedException 保留 NOT_EVALUATED、原始错误及未确定结论，不自动算符合契约；普通 SQLException、SQLState 或错误文本也不能证明允许不支持。无明确前提标记的准备阶段 SQL 错误同样保留 NOT_EVALUATED。逐用例 phaseDurationsMs 记录准备（包含注入连接获取）、方法主体和连接清理耗时；类级对象清理另见 cleanupEvidence。旧报告不补造这些结论。

报告分别展示目录数、选中数、实际契约调用数、用户排除、未执行/未完成及各类结论；Provider 跳过/准备不足/超时不计通过。原始 JSON 保留冻结选择及逐用例证据，未开始项以任务终止原因解释。适配器状态表示平台适配成熟度，与驱动的契约结果独立；不因真实驱动缺陷自动降级适配器。

任务级 budgetSeconds 可选 60–7200 秒；页面默认 1800 秒，旧 API 未提供时沿用服务配置并冻结。准备、用例、清理共享总预算；JUnit 的单用例默认 60000ms 仅约束测试方法体，采用 SAME_THREAD，显式 @Timeout 优先；目录发现、前后回调、准备及清理不在该方法期限内，仍受任务总预算约束。方法到期后 JUnit 中断当前线程，但不能强制 JDBC 调用返回。若调用忽略中断，同一执行线程不会继续后续方法，也不会并发清理；Worker 在任务预算到期后退出，未完成的清理记录未知。Server 保留 5 秒退出余量，再按配置的终止宽限（默认 2 秒）结束 Worker，必要时强制终止该进程。这不是 60 秒后强杀单个用例，也不保证遇到无响应调用后继续执行。未到达的用例保留 NOT_RUN，已开始未结束保留 INCOMPLETE。CLI 的 timeout_ms 继续表示单方法默认期限，未迁移成任务预算；core/full 名称和类/方法过滤保留，同时接受稳定 ID，过滤后为空直接拒绝。CLI 执行和报告共用筛选函数，报告以 USER_EXCLUDED 区分未选用例，以 NOT_APPLICABLE 区分无关扩展；无 PyYAML 时仍从清单读取显式 CORE 成员，未来存在扩展时要求 PyYAML 解析适用范围。新 CLI 报告带 outcome_semantics_version=2，不再以 false 声明覆盖实际结果；旧 XML 缺少可靠阶段证据时，普通 SQL 错误保留 NOT_EVALUATED，不猜测为平台原因。历史 JSON 不重解释。

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

Server 在加载目录和每次执行前检查协议版本。版本不匹配时返回 `WORKER_PROTOCOL_MISMATCH`，不发送执行凭据。验证/探测模式使用各自的请求契约。执行字段见 [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java)：`protocolVersion`、`runId`、`driver`、`connection`、`selection`、`workspace`、`timeoutMs`、`scope`、`objectPrefix`、`capabilities`、`destructiveConsent` 及冻结的 `adapterExecution` 声明。请求必须提供版本 2、明确范围与同意、`j` 加 20 位小写十六进制数字的对象前缀、全部 34 项布尔能力声明，以及所选 `connection.adapterId`。

事件包括 `RUN_STARTED`、`MODULE_STARTED`、`CASE_STARTED`、`CASE_FINISHED`、`MODULE_FINISHED`、`CLEANUP_FINISHED`、`METADATA_VALIDATED`、`RUN_FINISHED`、`RUN_FAILED`。格式错误或非预期事件、错误任务 ID、未选择或重复结果导致 `WORKER_PROTOCOL_ERROR`。报告输出到 `<workspace>/report/report.json`，schema 为 `2.0.0`；退出时删除临时配置。

| 退出码 | 含义 |
|---:|---|
| 0 | 任务执行结束，Server 仍需验证结果与报告 |
| 2 | 请求或选择无效 |
| 3 | 驱动 / 连接初始化失败 |
| 4 | 执行或超时失败 |
| 5 | 目录、工作目录、报告或内部失败 |

## KingbaseES 接入

正式适配表示已验证的平台接入和可解释的结果，不要求被测驱动所有 JDBC 用例通过。PostgreSQL、MySQL、Oracle、Kingbase 使用一致标准：真实身份校验、授权范围内准备及执行、区分实际契约行为与平台前提不足、报告/统计一致、所有权清理及真实版本证据。声明解析成功不是实库验收，official 也不等于 v1 的 `target_compatible`。原三库的组合证据早于当前 270 项选择。

复核 2026-10-19 的真实 FULL 任务 `f48e3615-e4b7-4f63-8cc1-a81495728ce3` 后提升为 official：270 项有终态结果记录，261 项实际调用契约，另 9 项因准备直接缺失（4 项）或连带阻塞（5 项）未调用契约；原状态为 252 通过、9 失败、9 中止，清理 SUCCESS。历史未运行/未完成计数字段均为 0，不表示全部契约均已调用。验证仅限服务端 V009R002C016、oracle 模式、JDBC 数据库版本 12.1、驱动 V008R006B0001 及已记录 SHA256 的 JAR。其他版本、模式和驱动尚未验证。首次独立 Worker 子集 20 项通过；当时 CORE 选中 153 项，90 秒期限前完成 42 项通过，与子集重叠 7 项，不能相加。后续 FULL 在 423.4 秒完成，不改写旧记录。

`validated_combinations` 以 JDBC 元数据版本 12.1 登记，另记录服务端构建及模式，链接[脱敏证据](../adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)。原 ignored 报告目录目前已不存在；证据明确来自保留的任务工具输出，包含统计、全部 18 项非通过结果及清理观察，哈希只覆盖这些源摘录，不冒充原始 Worker 报告哈希或完整报告。未编造 v1 目标兼容结论。

新增驱动选择 KingbaseES，使用可编辑的 `Kingbase`、`com.kingbase8.Driver`、`jdbc:kingbase8:` 预设，上传自己的授权 JAR；保持 SPI 发现及隔离验证。新增连接选择 `KingbaseES · 官方`（正式状态）和实际驱动，填写自己的 URL、独立凭据及已授权的专用测试数据库/schema，明确破坏性测试授权。适配器使用已有 schema，只清理唯一前缀的自有对象，不自动删除 schema。先探测连接，再通过页面精确选择、模块选择或 API `CUSTOM` / `includeScenarios` 运行。

产品、驱动及方言职责保持独立：身份要求 `^KingbaseES$` / `^Kingbase8 JDBC Driver$`，使用观察到的双引号、小写标识符及 schema 处理，所以复用 PostgreSQL 基础设施，不因 oracle 模式替换为本项目 Oracle 方言。`driver.source=local-drivers/kingbase` 用于 CLI 私有 JAR 解析，Web/Server 使用上传包和隔离 Worker；不分发私有驱动。

数字版本区间无法表达该 Kingbase 构建/驱动声明，因此 `supported_versions` 仍为空；这不表示支持全部版本。构建/模式属于明确文档边界，当前不是自动运行门控，使用者须确认。能力布尔值不再禁用标准用例，本次不改变能力开关、公共断言或差异豁免。

九项中止来自缺失或阻塞的 `procedure_behavior_ddl.sql`、`ref_cursor_ddl.sql`、`rowid_behavior_ddl.sql`、`multiple_results_ddl.sql`，属于平台准备范围限制，不是通过或数据库不支持。实库中八项数组及全部数据库元数据用例已通过，不能继续笼统称为未实测。两项 XA 准备失败时 `max_prepared_transactions=0`，属于环境限制，不能当作驱动缺陷或不支持 XA；保留原状态/结论，另在证据评审中说明。LOB 边界/局部写入、DataSource logWriter 观察保留；REF 元数据、按列下标批量键、未实现的 holdability 仍需结合原契约/可选 API 解释，不笼统归为驱动缺陷。

此前“四库验收”检查点中 MySQL、PostgreSQL、Kingbase 已执行实际 FULL 及后续页面流程，Oracle 当时仅驱动/连接验证。后续 Oracle FULL 尝试及其未完成条件见下文，仍不能宣称四库完整验收完成。单元、模拟浏览器证据与实库证据分开。详见[执行安全](execution-safety.zh-CN.md#kingbasees-平台验证)。具体版本来源及单项超时诊断命令见[英文说明](development.md#kingbasees-setup)，无需重跑全部测试。

### 准备后续针对性验证（9 项）

历史 FULL 的 4 项直接准备缺失、5 项共享资源阻塞由四份 `ddl/kingbase/` 资源补齐：
IN/INOUT/OUT 算术过程、REF_CURSOR 输出过程、表级 ROWID、多结果及数据准备。
独立 DDL/DML/pool 目录中的 35 份原基线资源与 PostgreSQL 逐字节相同，共享 SQL 未改。
未改变标准断言、能力开关、全局参数或历史结果。

按用户明确授权创建随机唯一 schema；通过实际数据库查询确认新 schema OID/owner、连接用户/current_schema 及探针表归属。
目标仍限 V009R002C016、oracle 模式、驱动 V008R006B0001。九项分别独立运行，并通过正常
Server/Worker/报告链路执行精确九项组合任务 `30d16db6-4faa-4800-b6b1-0c9d50e8d5bf`。
两轮结果一致：9 项均实际调用契约，8 PASSED/CONFORMS、1 FAILED/CONTRACT_VIOLATION；
没有准备不足、阻塞、跳过、NOT_RUN、INCOMPLETE 或超时。独立和组合是重复执行，不能相加为
18 项独立覆盖，亦不能改写旧 FULL 的 270 项记录。

| 稳定用例 ID | 历史准备问题 | Kingbase SQL 资源 | 补充实测结果 |
| --- | --- | --- | --- |
| `callable_statement.procedure_parameter_metadata` | 直接缺失 | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.ref_cursor_output` | 直接缺失 | `ref_cursor_ddl.sql` | PASSED / CONFORMS |
| `result_set.batch_row_id_binding` | 直接缺失 | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |
| `statement.result_sequence` | 直接缺失 | `multiple_results_ddl.sql` | FAILED：未暴露预期更新计数 |
| `callable_statement.in_out_and_in_out` | 共享准备阻塞 | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.null_output` | 共享准备阻塞 | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `callable_statement.repeated_binding` | 共享准备阻塞 | `procedure_behavior_ddl.sql` | PASSED / CONFORMS |
| `result_set.row_id_round_trip` | 共享准备阻塞 | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |
| `result_set.update_and_delete_by_row_id` | 共享准备阻塞 | `rowid_behavior_ddl.sql` | PASSED / CONFORMS |

多结果用例准备成功、实际调用契约，失败发生在结果值和结果集关闭检查之后，序列没有暴露预期的
一行更新计数。服务端原生隐式结果机制与后续契约/驱动归因有关，仅凭这一观察不能认定驱动缺陷。
保留原断言及失败。四项缺失不再是这个已验证组合的准备缺口，五项连带阻塞均已解除；断言失败另列。

十个任务均为所有权清理 SUCCESS；组合任务共创建并删除 5 个根对象，验证共享准备没有重复冲突。
保存所有实际报告后，实查本轮 schema OID/owner 未变且关系、例程、类型均为空，再以不带 CASCADE 的
DROP 删除 schema。没有复用或清空已有 schema，没有重跑 Kingbase FULL；两项历史 XA 全局配置限制
属于范围之外，继续保留。逐项任务 ID、完整脱敏 Worker 报告、冻结选择、源码/二进制指纹及哈希位于
工作区外私密归档
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/original-229/`。
4 项直接准备缺失与 5 项连带阻塞均标记为**已解决**；剩余断言另列 OPEN，不再记作准备缺失。

### Kingbase 多结果更新计数待办

OPEN：`statement.result_sequence`（`MultipleResultsTest.testResultSequence`）。完整测试 SQL 见
[`ddl/kingbase/multiple_results_ddl.sql`](../ddl/kingbase/multiple_results_ddl.sql)，JDBC 调用为
`{call {{multiple_results}}()}`。任务自建表初值 `(id=1, value=0)`；过程通过原生
`dbms_sql.return_result` 返回 `SELECT 11`，执行
`UPDATE {{multiple_results_data}} SET value = value + 1 WHERE id = 1`，再返回 `SELECT 22`。

未改动契约预期结果值顺序 `[11, 22]`，至少一个更新计数 `1`，最终遍历终止计数 `-1`。
实际失败为 `Procedure must report its one-row update ==> expected: <true> but was: <false>`。
到达此断言说明此前 `[11, 22]` 及结果集关闭检查已通过，没有观察到计数 `1`。循环结束可推知
终止计数 `-1`，但后面的显式终止计数断言及数据值检查尚未执行。这些是报告结合源码控制流的推知，
不是逐次 JDBC 返回日志；没有保留完整交错序列及其他中间计数，不能补写不存在的记录。

独立任务 `9bf09a2e-779f-48cb-a0ed-bc6ea54a6af5` 和组合任务
`30d16db6-4faa-4800-b6b1-0c9d50e8d5bf` 均保留该失败。相对私密归档的证据路径为
`original-229/evidence/{任务ID}/worker-report-report.json.sanitized.json`，
`closeout/kingbase-result-sequence-followup.json` 保存 SQL、预期/观察值及边界。
后续先复核隐式结果与 JDBC 契约，尚不认定驱动缺陷；不改断言或历史结果，本轮收尾不追加实库测试。

### Oracle FULL 后续验证（2026-10-19）

真实页面创建任务 `200dfecd-7606-4e82-b499-25fe978133a1`，实测 Oracle 19.3.0.0.0、
ojdbc11 21.13.0.0.0，主 JAR SHA-256 为
`d5672fe8bb1403df85bfedd1d50a09dd10c9b0ae8527e67dd0e9a297ea98780d`。
配套 orai18n、xdb、xmlparserv2 均为 21.13.0.0，明确配置 Oracle DataSource、连接池及 XA Provider。
实际连接身份和 schema 已核对。页面请求、目录、冻结元数据、Worker 报告及分页结果
270 个 ID 完全一致，无排除项；预算 1800 秒、默认方法超时 60 秒、任务耗时 94.296 秒。

270 项终态记录为 251 PASSED、10 FAILED、9 ABORTED；实际调用契约 261 项，另 2 项准备不足、
7 项共享准备阻塞未调用。另一维度的契约结论是 251 CONFORMS、9 CONTRACT_VIOLATION、
1 PLATFORM_ERROR、2 PREREQUISITE_UNMET、7 BLOCKED_PREREQUISITE，不能将两套统计相加。
允许不支持、NOT_RUN、INCOMPLETE、超时均为 0；任务为 FAILED/WORKER_EXECUTION_FAILED。
这不是全部契约已执行或全部通过的验收。

Oracle 旧数组准备含 VARCHAR2 替代列，初始化报 ORA-00933（42000/933），8 项数组契约未运行；
`statement.result_sequence` 缺少 `multiple_results_ddl.sql`。实际失败涉及过程参数序号、锁等待/查询
超时与取消、ROWID 类型元数据、批量返回键和 SQLXML 流文本。按列名批量键出现的
NullPointerException 仍保留 PLATFORM_ERROR，缺少调用栈证据时不认定驱动缺陷。
未改写原始结果或断言，也没有在发现清理清单差异后追加可写补充任务。

原始 Worker 报告已在运行时目录清理前保存完整脱敏副本，另保留 API 下载内容
（`source=PERSISTED_WORKER_EVENTS`、`workerReportValidated=true`）、冻结输入、选择核对、
驱动指纹、执行源码快照、逐用例归因及 SHA-256 清单，位于工作区外私密归档
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/original-229/preserved-prior-evidence/evidence/200dfecd-7606-4e82-b499-25fe978133a1/`。
原始未脱敏字节哈希与脱敏副本哈希明确分开，不保存到 Git 的私密 JAR、凭据及业务数据。
较早 Kingbase FULL 原始报告仍不可获取。

Worker 归属记录报告 36 条任务根对象记录清理成功，只读检查无任务前缀残留。另有两条测试前已存在的
LOB 字典记录在后清单中缺失，均不在归属记录中；历史父映射和致因操作仍未知。任务归属清理成功不能
证明既有对象没有受到影响。Oracle 完整验收仍受此未解决差异、9 项未调用契约及终态页面/筛选/下载按钮
复核未完成限制。

### Oracle LOB 差异只读调查

状态：**原因未确定，等待额外历史证据；未解决。** 两条测试前存在的 LOB 字典记录在后清单中
缺失，既不能认定业务数据丢失，也不能宣称没有影响。真实内部对象名、连接身份、查询、清单和
诊断输出仅保存在私密归档，不在这里公开。

保留的前后 USER_OBJECTS 查询列、排序和筛选口径一致，实际连接 user/current_schema 检查一致。
缺少的是 OBJECT_ID 条目，不是业务行数；历史父表/列映射未采集。此前原始私密采集流已删除，
哈希无法重建原件，也不能证明历史 EOF、SCN、DBID/PDB/edition 一致。未保留采集错误不能排除采集缺口。

后续只执行 SELECT。当前字典、回收站、SQL 缓存和已有审计未建立父映射或致因操作。缓存不是完整
审计；根据未改动源码及归属记录重建的清理 SQL 与实际采集 SQL 明确区分。没有证明历史普通自有表
操作或后台回收的审计覆盖。审计/缓存为空、任务前缀无残留都不证明安全。

命名、显式占位符展开、连接范围及归属清理检查未确认公共缺陷。使用合成标识的
`OracleOwnershipIsolationTest` 仅验证父对象 DROP、依赖元数据只作证据、未知/失败 CREATE 保留；
它没有复现、解释或修复记录消失的根因。未修改生产清理逻辑，未在历史 Oracle 范围写入复现、恢复、
重新建表或清理。

进一步归因需要 DBA 提供**已有**事前 LOB—表/列映射或字典/DDL 备份、审计/redo/归档/后台诊断历史，
以及其他位置如仍保留的原始采集流、SCN 和数据库身份。没有新增证据时不重复调查，不为调查启用设置。
历史账号/schema 继续停止写入测试；后续实库写入验收改用独立专用测试账号/schema。

数组准备、多结果资源、批量键 NPE、页面复核各自保持 OPEN。旧临时管理库已删除，脱敏 JSON 仍在，
旧任务页面不可访问，不为截图重跑 FULL，不把离线复查写成页面点击。
完整私密证据位于工作区之外的
`~/Documents/jdbc-test-evidence/2026-10-19-oracle-kingbase-closeout/`：
`original-229/` 原样保存 229 个文件及原 SHA256 清单，`closeout/` 保存后续待办和本地提交来源记录。
这是已校验的本机归档，不宣称已异地备份。Git 仅保存正式脱敏说明和合成测试。

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
export JDBC_TEST_E2E_DATABASE='jdbc_ui'
export JDBC_TEST_E2E_SCHEMA='authorized_test_schema'
export JDBC_TEST_E2E_PASSWORD='your-target-password'
npm run test:e2e:real
```

真实项目需要运行中的 Server/Worker、干净的平台数据库与驱动存储，以及单独授权的目标 PostgreSQL。缺少输入会直接失败，不回退到模拟。测试会写入数据；每次使用新的可丢弃验收存储，不要连接共享或业务数据库。每轮结束后停止本轮服务，清理本轮创建的数据库、存储和临时配置；仅将用户要求的报告/截图保留在本地忽略路径。真实测试关闭 trace 采集。`PLAYWRIGHT_CHANNEL=chrome` 可使用已安装的 Chrome。生产模式检查见[部署指南](deployment.zh-CN.md)。

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
实现类须来自上传的驱动 JAR；对应接口用例始终在标准目录中默认选中；缺少配置呈现执行前提不足，不计为通过。
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


内嵌凭据、适配预检、清理证据及历史兼容见[执行安全](execution-safety.zh-CN.md)。本轮不改变三步源码启动、三态主题、Key 或目标认证配置。

## CLI 内部驱动升级回归

见[内部基线流程](internal-regression.zh-CN.md)：明确选择 local/private 基线、证据要求、
允许的驱动制品变化及 CI 退出码。已审查的 JDBC 断言、独立声明/实测证据和限定的方案要求
见[能力契约判定依据](jdbc-capability-contracts.md)。历史报告保持原样。

### Web 导入与冻结资源

[Web 适配包](web-adapters.zh-CN.md) 是在运行时接入包的入口，无需登记内置 ID。
Core 复用清单校验并约束可迁移 ZIP；Server 通过 V9 持久化当前数据，在任务创建时冻结完整包。
连接写入与归档引用检查串行化。仅 Worker 在自有目录写入冻结资源并执行原有 Suite 用例。
公开 `adapterSnapshot` 保留有效本地来源，原清单 trust 不会授予正式身份。
`adapterExecution` 字段保持不变；协议 2 增加可选包/来源快照，元数据握手声明
`adapterPackageVersion=1`，部署时需使用匹配构建。缺少历史快照仍为未知。
显式更新和保留边界见上述指南。

## SQL 功能测试包（格式 1）

见[格式、执行和验证](sql-packages.zh-CN.md)。V10 新增不可变 SQL 包存储，标准 JDBC 选择不变。
PostgresPackageMigrationTest 与 PostgresSqlFlowTest 只接受显式回环 JDBC_TEST_DISPOSABLE_PG_URL，
必须指向本轮新建的可丢弃 PostgreSQL，禁止现有平台或目标数据库。测试删除自有 schema/角色，
运行者须清理本轮 PostgreSQL 容器和卷。

SQL 任务完整执行且报告已校验、失败均为 SQL 断言时，Server 原因记录 `SQL_ASSERTION_FAILED`，
沿用 FAILED 状态；Worker 协议执行失败码为 4，SQL CLI 将已完成失败映射为 1。其他执行/平台问题仍独立保留，
不能把 CLI 与 Worker 退出码混用。参见[SQL 测试包](sql-packages.zh-CN.md)。

### 驱动验证元数据与版本来源

驱动验证仅在隔离 Worker 中加载所选类，读取 `java.sql.Driver.getMajorVersion()`、
`getMinorVersion()` 和 `jdbcCompliant()`，不连接数据库。登记版本是可编辑的人工标签，不是探测值。
自报主/次版本两个整数不要求与完整发行字符串一致；真实 `0.0` 保留并提示核对。
`jdbcCompliant()` 是驱动声明，不是平台测试或认证结论。
规范来源：[官方 Driver API](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Driver.html)。

`DRIVER_VALIDATED.loadedArtifact` 记录实际驱动类所在 JAR 的文件名和 SHA-256，及该制品清单中的
`Implementation-Version`（包级节优先，其次主属性）、`Bundle-Version`（主属性）。这些是独立声明，
不从文件名、登记标签、依赖包或厂商私有方法推测；缺少字段显示“未提供”。Worker 确认该 JAR 属于
所选制品列表；Server 不加载驱动类。旧 Worker 合法事件缺少制品证据时保持明确未知。

Server 对缺失、类型错误、负数、超出 int 范围的版本、非布尔合规性、驱动类不符、重复成功事件和
无效制品证据返回 `DRIVER_VALIDATION_PROTOCOL_ERROR`，不再默认成 0/false；前端也拒绝无效响应。
验证成功仅代表加载及元数据读取成功，不代表连接成功或完整兼容性通过。本次响应仍不持久保存。
更新后需部署/重启匹配的 Server/Worker；已经运行的后端代码需要重启才生效。
`DriverValidationProtocolTest` 以合成驱动调用真实 Worker 序列化和 Server 解析，驱动连接方法故意抛错，
覆盖类加载顺序及序列化后缺失/类型错误的事件，不需要真实数据库。
