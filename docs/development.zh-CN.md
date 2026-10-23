# 开发指南

[English](development.md) | 简体中文

用户操作见[使用指南](usage.zh-CN.md)，运行配置、备份和升级见[部署指南](deployment.zh-CN.md)。
本文覆盖源码架构与维护规则。

<a id="local-startup"></a>
<a id="local-source-startup"></a>
## 本地源码启动

需要 JDK 21、Node >=22.12/npm 和 Maven Wrapper。准备具有迁移权限的专用平台 PostgreSQL
数据库及账号，与被测目标库分开。在源码根目录执行：

```sh
umask 077
cp -n .env.example .env
# 启动前编辑实际 .env 中的数据源 URL、用户名和密码。
./jdbc-test dev
```

PowerShell 中使用 `if (-not (Test-Path '.env')) { Copy-Item .env.example .env }` 复制，编辑后执行
`./jdbc-test.ps1 dev`。不要覆盖已有配置。启动器按字面值读取 `.env`，验证平台认证和状态，
仅对全新空平台生成持久 Key；已有状态必须使用原 Key。禁止 source/eval `.env`，
语法见[配置规则](deployment.zh-CN.md#configuration-rules)。

命令构建 Java/Suite/Worker，定位当前 POM 精确版本的 Worker，按需安装前端依赖，
通过 Maven 启动源码后端及 Vite 并等待就绪。默认页面为 `http://127.0.0.1:5173`，
后端为 `http://127.0.0.1:8080`。`SERVER_PORT` 和 `JDBC_TEST_WEB_PORT` 可调整端口，
Vite 代理跟随后端。仅后端接收凭据/Key。数据默认为源码根目录 `data/`，
可用 `JDBC_TEST_DATA_DIRECTORY` 指定。端口占用会退出，不终止无关服务。

日常复用 `./jdbc-test dev` 及原配置/Key/数据。前端支持 HMR，Server 修改需重启，
Suite/Worker 在下次启动重建。Ctrl+C 停止所属子进程，一端失败会停止另一端，
平台 PG 和保存数据保留。启动不执行目标测试。

### 调试与手工启动

`./jdbc-test dev --debug` 在 `127.0.0.1:5005` 提供仅 loopback 调试端口。
IDEA 用 JDK 21 导入 `pom.xml`，配置 Remote JVM Debug / Attach 和 `jdbc-test-server` 源码，
不要再启动第二个后端。主类为 `com.jdbctest.server.JdbcTestServerApplication`；IDEA UI 流程尚未实测。

手工启动先执行 `./mvnw -DskipTests install`，向实际后端进程提供数据源/Key，
将 `JDBC_TEST_WORKER_EXECUTABLE` 指向当前版本 Worker `-all.jar`，再运行
`./mvnw -pl jdbc-test-server spring-boot:run`。手工 Maven/IDEA 不读取 `.env`。
手工前端在 `jdbc-test-web` 执行 `npm ci`、`npm run dev`，非默认后端可在 Vite 进程中设置
非秘密 `JDBC_TEST_DEV_API_URL`。秘密不能进入共享 IDE 配置或参数，手工启动的进程分别停止。

## 架构

```text
React / Vite → REST + SSE → Spring Boot Server
                                ├→ Platform PostgreSQL
                                ├→ Driver storage / Run workspaces
                                └→ Worker JVM → external driver → Target database
```

| 路径 | 职责 |
|---|---|
| `jdbc-test-core` | 目录、选择、请求/报告模型及公共工具 |
| `jdbc-test-suite` | JDBC 场景、JUnit 扩展及打包 SQL |
| `jdbc-test-worker` | 隔离加载/执行、验证、探测及 NDJSON |
| `jdbc-test-server` | REST、持久化、调度、SSE 及生产静态资源 |
| `jdbc-test-web` | React/TypeScript/Vite 前端 |
| `adapters`、`ddl`、`dml`、`pool`、`compatibility/v1` | 内置声明、SQL/连接池资源和用例清单 |
| `scripts` | CLI、报告、回归和打包 |

Server 依赖 Core，不加载 Suite 或上传驱动类。Worker 打包 Core/Suite/JUnit/SQL，目标驱动仍为外部输入。
CLI 采用 Maven/JUnit，共享场景及安全契约。Flyway 管理平台 V1–V12 迁移，Hibernate 只校验不建表，
禁止修改历史迁移。密码信封使用 AES-256-GCM 和持久32字节 Master Key；队列凭据单独加密，
在交接、终态和恢复路径删除。驱动位于 `<data-dir>/drivers/`，任务工作区位于 `<data-dir>/runs/`。

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
原生命令不会 Optimize Imports，需单独执行；逐项基线中的既有通配导入保留。
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


## 增加数据库支持

`adapters/catalog.json` 是唯一内置索引；`adapters/<id>/adapter.json` 分别维护名称、信任状态、
身份/版本规则、能力、命名空间和资源。Java/Python 严格校验 UTF-8 JSON、重复键、类型、路径、
有限数值、大小和深度。Core 构建不可变注册表，不加载 Suite 或驱动。
共享正反例在 `jdbc-test-core/src/test/resources/adapter-contract`。
未知扩展保留但不会自动执行。身份正则需兼容 Java/Python，版本声明不是兼容性证据。

复用已实现 SQL 行为时，添加唯一 manifest、真实身份/能力及 `candidate`/`local` 状态，登记 ID。
可通过 `assets.directory` 复用资源，或新增 `ddl/<asset-id>`、`dml/<asset-id>`、`pool/<asset-id>.yaml`。
普通注册项无需增加产品枚举或前端分支。驱动预设是独立可编辑提示，不决定适配身份或能力。

新方言需实现 `SqlDialect` 的范围、引号、URL 解析、资源定位及归属清理，并覆盖失败路径。
当前有限行为为 `postgresql`、`mysql`、`oracle`、`sqlserver`、`dm`，产品身份独立。
DM 保留 schema 精确大小写且不要求 catalog，与 Oracle 折叠和 MySQL catalog 选择不同。
修改共享逻辑需回归已有产品。仅增加枚举不能实现支持，不得修改公共断言或减少 FULL 选择制造兼容。
扩大支持声明前需要真实授权验证。

适配资源在源码中维护，自定义适配包、SQL 包上传和 JSON 导入已撤回。
多 JAR 驱动使用 Web 上传或 CLI 本地目录；CLI Maven 解析器仅加载主 JAR，不构成完整传递依赖包。
没有 Web 在线 Maven 导入器。[示例](../examples/README.zh-CN.md)说明显式兼容方言选择。

## 增加用例与准备资源

在 Suite 添加测试并登记稳定用例 ID，明确类别、JDBC 基线、适用性及执行前提。
使用共享选择器和目录，不另写固定数量。FULL 为全部适用场景，CORE 由清单维护，
CUSTOM 先交集模块/包含项再应用排除项。空、过期、未知选择均拒绝。
能力 false 不跳过标准调用，厂商扩展适用性独立；当前没有实际调用厂商 API 的扩展用例。

`UseSqlScripts` 按选中方法/类延迟准备，空方法注解可覆盖类资源，无表场景保持独立。
通过显式占位符和归属 API 创建带引号、范围限定的对象并清理，不任意改写 SQL 单词或宽泛 DROP。
断言前检查原生资源形状；SQL NULL 观察和工厂调用不能替代 ARRAY/ROWID/REF_CURSOR 往返。
资源、依赖、权限缺失属于准备或环境问题，保留原始 SQLState、原因和阶段。

使用精确到方法的 `JdbcContracts` 判定，额外要求使用 `SuiteRequirementAssertionError`，
不能从能力标志、SQLState 或消息推导豁免，见 [JDBC 判定参考](jdbc-capability-contracts.zh-CN.md)。
清理与用例完成独立记录，历史缺失证据保持未知；真实复验产生新报告，不改写旧 FULL。

### 驱动自身 Provider

配置真实公共类和 setter，缺失属于前提不足。CLI/运行时 YAML 示例：

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

API `dataSources` 使用相同定义，默认属性为 `URL/user/password`，其他属性映射公共标量 Bean setter。
测试对象是上传驱动的实现，不是平台连接门面。XA 还需数据库前提。
CLI `db.password_env` 逐字解析单个环境变量，未设置与空值不同，同时指定字面密码和环境密码会失败。

## API 与持久化契约

资源 DTO 无统一成功信封。列表 `page/size` 默认20、最大100，返回
`{items,page,size,totalElements,totalPages}`；错误为带稳定 code 的 RFC Problem Details。
创建返回201/Location，删除204，历史引用保护返回409。

| 路由 | 操作 |
|---|---|
| `/api/drivers`、`/api/drivers/presets`、`/api/drivers/{id}` | multipart 上传/列表、提示、读取/PATCH/删除 |
| `/api/drivers/{id}/validate` | POST 隔离加载验证 |
| `/api/adapters` | GET 内置连接选项 |
| `/api/connections`、`/api/connections/{id}` | 创建/列表、读取/PATCH/删除 |
| `/api/connections/test`、`/api/connections/{id}/test` | POST 草稿/已保存连接探测 |
| `/api/test-catalog` | GET profile、模块、当前数量 |
| `/api/runs`、`/api/runs/{id}` | 创建/列表、读取快照/进度 |
| `/api/runs/{id}/cancel`、`/api/runs/{id}/events` | POST 取消、GET SSE |
| 任务下的 `/api/runs/{id}/results`、`/report`、`/report.json` | 结果、摘要/失败和 JSON 证据 |

驱动上传含 multipart `metadata` 与重复 `files`，默认20制品、单个100MiB、总计250MiB。
大小/SHA-256 和排序摘要构成的包指纹标识不可变二进制。`loadedArtifact` 记录实际定义类的 JAR
及独立 Manifest 字段。Driver API 整数版本/合规值是声明，不是连接后的发行版本。
协议元数据无效时严格失败。

连接密码只写，返回 `hasSavedPassword`。`savePassword` 保存/替换，`clearSavedPassword`
与任何新密码（包括空值）冲突。省略字段保留旧值；显式空值按字段清除范围或选择空密码。
探测通过 Worker `Driver.connect` 尽可能读取元数据及只读 Oracle/DM 配置后关闭，不执行测试 SQL。
普通驱动验证仅加载类。被引用驱动/连接不能删除。

任务入队冻结范围/授权、选择、适配执行声明和非秘密设置。可选 `name` 最多200字符，旧无名任务用显示回退。
本地 FIFO 限制并发 Worker，锁保护终态/取消竞争。筛选组为 WAITING、IN_PROGRESS、COMPLETED、
ABNORMAL、CANCELLED，不能同时指定精确 `status` 和 `statusGroup`；过滤及计数在分页前进行。
结果的状态和模块筛选采用 AND，以稳定 ID 处理同序值。

正常链路为 QUEUED → PREPARING → RUNNING → GENERATING_REPORT → SUCCEEDED，
SUCCEEDED 表示处理完成而非全通过。只有经过验证的 exit4/TEST_FAILURE、无独立执行问题且清理成功时，
才可显示正常完成但有未通过用例。其他执行、报告和清理失败仍异常。
Server 交叉校验选择、事件、报告、计数和清理，持久化重建标注 `source`、`workerReportValidated`。
历史结论仅在已有证据充分时只读解释，原始报告不变。

<a id="worker-protocol"></a>
## Worker 协议

配套部署 Server/Worker。协议2从 stdin 读取一个 JSON 请求，stdout 输出 NDJSON，凭据不进入 argv。
每轮 `URLClassLoader` 以 platform 为父加载器，直接调用所选 `Driver.connect`，不全局注册。
驱动输出不能进入公共协议通道；诊断有界并脱敏，识别边界见[使用指南](usage.zh-CN.md)。

| 模式 | 用途 |
|---|---|
| 默认 | 单任务执行 |
| `--describe-catalog`、`--catalog-smoke` | 无目标驱动的目录描述、打包资源检查 |
| `--validate-driver` | 类加载及 Driver 元数据，不连接 |
| `--probe-connection` | 只读连接/元数据探测，无测试 SQL |

请求见 [WorkerRunRequest.java](../jdbc-test-worker/src/main/java/com/jdbctest/worker/WorkerRunRequest.java)。
执行要求协议2、范围/授权、`j` 加20位小写十六进制前缀、完整布尔能力及冻结适配选择。
事件包含任务/模块/用例生命周期、元数据和清理；错误 ID、意外/重复结果和协议不匹配明确失败。
报告 schema 为2.0.0，路径 `<workspace>/report/report.json`。
Worker 退出码：0完成（仍需校验报告）、2请求/选择、3驱动/连接准备、4执行/超时、5目录/工作区/报告/内部错误，
不同于 CLI 比较退出码。

任务预算60–7200秒，UI 默认1800，包含准备/执行/清理。JUnit 方法默认60000ms、SAME_THREAD，
显式 `@Timeout` 优先；发现和回调受任务预算约束，不计入方法计时。
中断不能强制 JDBC 返回，不能用另一线程并发清理或启动下一用例。
Worker 到预算退出，Server 再给予5秒及配置的终止宽限（默认2秒）后强制结束。
未运行、未完成及不确定清理需明确保留。

## 前端维护

保留既有布局与交互，复用 `components/ui`、`components/common`、`components/data-table`，
Radix 处理交互，`foundation.scss` 维护视觉 token。TanStack Query 管理服务端状态，先 REST 加载，
SSE 更新同一缓存，终态关闭流并刷新，10秒轮询兜底；重连不改变任务状态。
密码只在组件内存，不入 URL/存储/缓存，诊断以文本渲染。

同步 `src/i18n` 中英文。`jdbc-test.locale` 优先于支持的浏览器语言，最终回退英文；
日期用浏览器时区，ID/API/原报告不翻译。仪表盘统计只描述近期已加载任务，不虚构后端统计。
修改共用 UI 时保留 `jdbc-test-web/public/third-party-notices.txt` 的 CPA Keeper MIT 声明及来源/改编归属。

<a id="testing"></a>
## 开发检查

```sh
./mvnw clean verify
python3 -B scripts/run_contract_tests.py
npm --prefix jdbc-test-web ci
npm --prefix jdbc-test-web run lint
npm --prefix jdbc-test-web test
npm --prefix jdbc-test-web run build
npm --prefix jdbc-test-web run test:e2e
```

默认 Maven 测试不使用用户数据库。可选 `./mvnw -Pworker-e2e,server-integration verify`
使用临时 Docker 数据库，Docker 不可用时集成测试可能跳过。夹具 Playwright 不是实库验证。
可用 `PLAYWRIGHT_CHANNEL=chrome` 指定已安装 Chrome，构建后用 `JDBC_TEST_E2E_PREVIEW=true`
检查构建后的夹具资产。

`npm --prefix jdbc-test-web run test:e2e:real` 要求匹配的运行中 Server/Worker、全新平台存储和
专用目标 PostgreSQL。私密配置/环境需提供 `JDBC_TEST_E2E_DRIVER_JAR`、`DRIVER_VERSION`、
`TARGET_URL`、`USERNAME`、`DATABASE`、`SCHEMA`、`PASSWORD`，各项均加相同 `JDBC_TEST_E2E_` 前缀。
缺失输入会失败，不回退模拟。显式 `history-readonly` 项目要求已核实的查看后端、构建预览和
`JDBC_TEST_HISTORY_READONLY=true`，不创建任务或探测；历史 QA 不用于写入。

按改动执行必要检查，纯文档整理不要求数据库 FULL。验证前记录已有资源，成功、失败或取消后
清理本轮所属进程/容器/数据库/文件，保留用户和共享资源。详细证据放已忽略本地路径，
一般验证在回复简述；仅影响用户操作或维护时直接更新对应的既有指南。保持当前文档结构，
不为每轮开发、验收或每种数据库新增独立文档。

## 升级兼容

V9/V10 及其他历史迁移保留，撤回包的表和历史仍可读且不重写。历史 SQL 报告保留 SQL_FEATURE，
不能进入 JDBC 基线。旧自定义适配连接创建新任务前需选内置类型或显式兼容方言。
已撤回排队任务以 RETIRED_PACKAGE_EXECUTION 失败，旧 sqlPackageFingerprint 请求返回 SQL_PACKAGES_RETIRED。
重启以 SERVER_RESTART 中断旧非终态任务。Server/Worker 快照须匹配，未知撤回包执行字段严格失败，
不静默回退。见[部署升级/备份规则](deployment.zh-CN.md#operations)及[驱动 A/B 回归](internal-regression.zh-CN.md)。

<a id="known-limitations"></a>
## 已知限制

以下是仍有效的边界，不是完整认证矩阵。保留原断言和历史报告，定向复验不改变原 FULL 通过率。

| 范围 | 当前边界 / 后续条件 |
|---|---|
| PostgreSQL 结果序列 | 平台准备缺口，尚无满足原生过程隐式结果序列契约的等价资源，不能用游标名列表或客户端多语句替代。 |
| PostgreSQL / Kingbase 两阶段 XA | 实测 max_prepared_transactions=0 阻塞 prepare 相关分支；需 DBA 提供已启用的专用环境，其他独立分支继续。 |
| Kingbase 元数据耗时 | 早期 getTypeInfo 调用耗尽任务预算，后续更广任务完成，原因仍未知；归因驱动挂起前，仅在有界隔离进程复现该调用。 |
| Kingbase ROWID | 疑似驱动 NULL 映射问题：SQL NULL 返回非空 KbRowId，wasNull=true，getBytes 抛 NPE。未到达的绑定/更新/删除/批量仍未验证。 |
| PostgreSQL DataSource log writer | 疑似驱动行为，保留断言和最小证据，等待具体方法契约及厂商复核。 |
| PostgreSQL holdability、按索引生成键 | 证据不足，NOT_EVALUATED；PSQLException/SQLState0A000 本身不构成允许不支持。 |
| Kingbase / MySQL 过程更新计数 | 内部语句计数可见性是额外方案要求而非通用 JDBC 保证，未满足仍保留 SUITE 范围失败。 |
| MySQL 可选路径 | 已观察到的 ARRAY/ROWID/SQLType 注册调用允许不支持，不验证下游 Array 方法、绑定和原生游标输出；保留具体方法证据，不宣称整个接口支持情况。 |
| Oracle 已记录执行失败 | 参数序号、取消/查询超时、批量生成键及 SQLXML 流文本仍需逐方法归因/复验，新增准备资源不解决这些既有观察。 |
| Oracle 资源 | 原生数组、多结果准备及嵌套依赖清理已有自动化检查，尚未真实 Oracle 复验。按列名批量键 NPE 归因、终态页面/筛选/下载按钮复核仍待办；需可达授权 PDB、额度与权限。 |
| Oracle LOB 清单 | 历史运行后两个既有字典条目缺失，原因与历史父映射未知；既未证实业务数据丢失，也未证实无影响。合成归属测试不解释或修复差异。该写入范围保持关闭，新写入需独立专用账号/schema。归因需已有运行前映射/备份/审计/redo/采集身份，不能盲目重跑 FULL。 |
| 历史证据 | 部分早期 MySQL/Kingbase 原 FULL 报告已不可得；脱敏摘录不等于原报告，不重建或认证缺失历史。adapters 内维护的证据保留。 |
| 达梦 | 候选接入仅验证 DM DBMS 8.1.3.162＋驱动 8.1.2.192，包括真实 Web 保存/编辑/执行及 FULL（249 通过、21 失败）。后续修复原失败五项，仍有十一项兼容疑点及五项额外套件要求；独立复现不等于已确认厂商缺陷。精确配置和模式范围见使用指南。 |
| 平台启动验证 | Windows ACL/PowerShell 进程行为和 IDEA UI 未验证；标准 Docker 源码构建在镜像仓库认证超时后没有成功验证记录，替代本地镜像运行不证明源码构建通过。 |

准备不足不等于驱动缺陷，允许不支持不算功能通过。REF/ROWID 类型存在、LOB 自动持久化、
非法操作后恢复和内部更新计数要求分别定界，见 [JDBC 判定参考](jdbc-capability-contracts.zh-CN.md)。
