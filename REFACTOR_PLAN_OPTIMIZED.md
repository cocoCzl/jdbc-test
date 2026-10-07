# jdbc-test Web Platform 重构执行计划

> 目标仓库：`https://github.com/cocoCzl/jdbc-test`\
> 文档用途：直接交给 Codex 作为重构任务说明与执行清单。\
> 基线技术栈：JDK 21、Maven 3.9+、Spring Boot 4.1.x、Spring Data JPA、PostgreSQL、Flyway、JUnit Platform、SSE、React、TypeScript、Vite、Tailwind CSS、shadcn/ui、ECharts、Docker Compose。\
> Maven Wrapper 建议固定：Apache Maven 3.9.15。\
> 重要：本文是“目标架构 + 分阶段迁移计划”，Codex 必须先审计现有代码，再逐步迁移，禁止直接推倒重写。

---

## 0. 给 Codex 的总指令

请先完整阅读本文件，然后再开始修改代码。

执行时必须遵守以下原则：

1. **先审计，后重构。**
   - 先读取当前仓库目录、`pom.xml`、README、现有 JDBC 测试、配置、runner、报告生成逻辑、数据库适配逻辑。
   - 先运行当前已有测试，记录基线。
   - 不要假设本文列出的现有目录和代码一定与仓库完全一致；以实际代码为准调整迁移步骤。

2. **不要一次性重写整个项目。**
   - 必须按照本文 Phase 0 → Phase 10 顺序推进。
   - 每个阶段结束后保证项目能够编译、测试能够运行。
   - 不要为了“架构更漂亮”删除仍在使用的功能。

3. **优先保留现有 JDBC 测试资产。**
   - 现有测试用例、数据库差异处理、报告信息都是项目的核心资产。
   - Web 平台是这些能力上面的管理、调度与可视化层。
   - 重构时应迁移/封装现有能力，而不是重新发明全部测试。

4. **平台数据库和被测数据库必须严格隔离。**
   - 平台自身持久化：PostgreSQL + Spring Data JPA + Hibernate + Flyway。
   - 被测数据库：必须直接使用该数据库对应的 JDBC Driver 访问。
   - **禁止使用 JPA/Hibernate 测试目标数据库。**

5. **用户上传的 JDBC Driver 不允许在 Spring Boot Server 主 JVM 中执行。**
   - Driver JAR 属于可执行代码。
   - Server 只负责存储、读取 ZIP/JAR 元数据、计算摘要、调度。
   - Driver 的类加载、初始化、`java.sql.Driver#connect()`、JDBC 测试必须发生在独立 Worker JVM。
   - MVP 可以使用独立 JVM Process；未来再升级为独立容器 Worker。

6. **不把用户上传的 JAR 安装进本地 Maven 仓库。**
   - Maven 属于构建期工具。
   - Driver 上传属于运行期能力。
   - 上传 JAR 直接进入 Driver Storage，由 Worker 通过独立 ClassLoader 加载。

7. **不要泄露敏感信息。**
   - 密码不得写入日志。
   - API 不得返回明文密码或数据库中的密文。
   - JDBC URL 如果包含 password/token 参数，日志和报告中必须脱敏。
   - 上传 Driver 的文件路径不要直接信任用户输入。

8. **所有新增和修改代码必须遵守强制工程规范。**
   - Java 代码遵守《阿里巴巴 Java 开发手册》核心规范。
   - REST API 遵守统一 RESTful 语义。
   - 严格遵守 KISS / YAGNI / DRY，禁止过度设计。
   - 禁止无意义分层、空壳抽象、Dead Code、废话式注释/Javadoc。
   - 详细规则见本文第 26 章。

9. **所有破坏性 JDBC 测试必须有明确保护机制。**
   - 尽量使用专用 schema / namespace。
   - 运行前确认 destructive consent。
   - 测试资源必须带 run-specific 前缀，结束后清理。
   - 清理失败也必须记录并可追踪。

10. 如果 Git 操作权限允许：
   - 每个 Phase 使用独立 commit。
   - commit message 清楚说明阶段。
   - 如果不能 commit，则每个 Phase 完成后输出修改文件、设计决策、测试结果和遗留项。

---

# 1. 产品目标

将当前 `jdbc-test` 从“标准 JDBC 接口测试项目/库”重构为：

> **一个可 Self-hosted 部署的 JDBC Compatibility / Regression Testing Platform。**

用户部署完成后，不需要手工修改项目配置文件即可完成主要操作：

1. 打开 Web 页面。
2. 添加一个或多个 JDBC Driver。
3. 添加一个或多个数据库连接。
4. 为连接选择默认 Driver，也可以每次运行时临时选择其他 Driver。
5. 选择要测试的 JDBC 模块。
6. 启动测试任务。
7. 实时查看运行状态、当前 Test Case、Passed/Failed/Skipped、耗时和日志。
8. 查看历史 Test Run。
9. 查看结构化测试报告和统计图表。
10. 后续支持对比：
    - 同一个数据库 + 不同 Driver 版本；
    - 同一个 Driver + 不同数据库版本；
    - Baseline Run vs Current Run。

第一阶段优先定位为：

> **Self-hosted / 内网部署 / 数据库厂商和研发测试团队使用。**

暂不以公网 SaaS、多租户平台为第一目标。

---

# 2. MVP 核心用户流程

## 2.1 Driver Management

用户进入：

`Drivers`

可以：

- 查看所有 JDBC Driver。
- 上传一个 Driver Package。
- 一个 Driver Package 可以包含 **多个 JAR**。
- 填写或自动识别：
  - name
  - vendor
  - version
  - driver class
  - JDBC URL prefix（可选）
- 查看每个 artifact：
  - filename
  - size
  - SHA-256
- 删除未被引用的 Driver。
- 后续可增加 Maven coordinates 来源，但上传 JAR 是必须优先完成的能力。

示例：

```text
AcmeDB JDBC Driver 5.2.1
├── acmedb-jdbc-5.2.1.jar
├── acmedb-common-5.2.1.jar
└── acmedb-security-5.2.1.jar
```

---

## 2.2 Database Connections

用户进入：

`Connections`

可以添加多个、不同类型的数据库：

```text
PostgreSQL 16 Dev
MySQL 8 Test
Oracle 19c
GaussDB
AcmeDB Nightly
```

连接配置至少包含：

- name
- database type / vendor
- JDBC URL
- username
- password
- default Driver Package
- description（可选）

操作：

- Add
- Edit
- Delete
- Test Connection

**Test Connection 必须通过 Worker 完成，不允许 Server JVM 加载用户 Driver。**

---

## 2.3 New Test Run

创建测试任务时：

1. 选择 Database Connection。
2. 选择 JDBC Driver。
   - 默认使用 connection 的 default driver。
   - 可以临时换成其他 Driver。
3. 选择测试 Profile：
   - Core
   - Full
   - Custom
4. Custom 模式选择模块：
   - 具体模块必须优先根据当前仓库已有 Test Case 目录/标签整理；
   - 推荐标准化为类似：
     - Connection
     - Statement
     - PreparedStatement
     - ResultSet
     - Transaction
     - Batch
     - DatabaseMetaData
     - ResultSetMetaData
     - ParameterMetaData
     - GeneratedKeys
     - CallableStatement
     - LOB
     - SQLXML
     - Savepoint
     - 其他现有模块
5. 展示 destructive test 提示。
6. 用户确认后启动。

---

## 2.4 Running Test

运行页面至少显示：

- Run ID
- Database Connection
- Driver name/version/hash
- Status
- Started At
- Elapsed
- Total Cases
- Completed
- Passed
- Failed
- Skipped
- Current Module
- Current Test Case
- 实时日志
- Cancel 按钮

状态建议：

```text
QUEUED
PREPARING
PROBING
RUNNING
GENERATING_REPORT
SUCCEEDED
FAILED
CANCELLED
```

---

## 2.5 Report

测试结束后提供：

- Summary Cards
- Module Pass Rate
- Test Case 明细
- Failure Message
- Stack / Root Cause（经过脱敏）
- Duration
- Driver 信息
- Target DB 信息
- Test Environment
- Report JSON
- 可选导出 HTML / Markdown
- 后续增加 Run Comparison

---

# 3. 技术栈约束

## Backend

- JDK 21
- Maven >= 3.9.0
- Maven Wrapper：3.9.15
- Spring Boot 4.1.x
- Spring MVC
- Spring Data JPA
- Hibernate
- PostgreSQL
- Flyway
- Bean Validation
- JUnit 5 / JUnit Platform
- SSE (`SseEmitter` 或等效 Spring MVC 方案)
- Jackson

## Target JDBC Testing

- 原生 `java.sql.*`
- `java.sql.Driver`
- 独立 Worker JVM
- JUnit Platform Launcher
- 每个 Run 独立 ClassLoader / 进程
- 不依赖目标数据库的 JPA

## Frontend

- React
- TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- TanStack Query
- TanStack Table
- ECharts
- React Router

## Deployment

- Docker
- Docker Compose
- PostgreSQL container
- App container
- Driver data volume
- Run/report data volume

---

# 4. Maven / Java 基线

根项目必须增加 Maven Enforcer。

最低要求：

```xml
<requireMavenVersion>
    <version>[3.9.0,)</version>
</requireMavenVersion>
```

Java 固定为 21 major：

```xml
<requireJavaVersion>
    <version>[21,22)</version>
</requireJavaVersion>
```

并设置：

```xml
<maven.compiler.release>21</maven.compiler.release>
```

增加 Maven Wrapper，并将 Wrapper 固定到 Maven 3.9.15。

目标：

```bash
./mvnw -version
./mvnw clean verify
```

必须成功。

不要依赖开发者机器上恰好安装的 Maven 版本。

---

# 5. 建议的最终仓库结构

Codex 必须先检查现有仓库结构，然后尽量渐进迁移到以下形式。

```text
jdbc-test/
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│
├── jdbc-test-core/
│   ├── JDBC 测试领域模型
│   ├── Test Catalog
│   ├── Test Module
│   ├── Test Execution abstraction
│   ├── Report model
│   └── 现有 JDBC 测试核心能力
│
├── jdbc-test-worker/
│   ├── Worker main entry
│   ├── Driver classloader
│   ├── JUnit Platform Launcher
│   ├── Worker event protocol
│   └── target DB execution
│
├── jdbc-test-server/
│   ├── Spring Boot application
│   ├── JPA entities/repositories
│   ├── Flyway
│   ├── REST API
│   ├── SSE
│   ├── Driver storage
│   ├── Worker process manager
│   └── Report/query services
│
├── jdbc-test-cli/
│   └── 保留/迁移现有命令行能力（如果当前已有）
│
├── jdbc-test-spring-boot-starter/
│   └── 原计划给 Spring Boot 项目直接依赖的能力
│
├── jdbc-test-web/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│
├── docker/
│
├── docs/
│
└── docker-compose.yml
```

如果当前代码规模暂时不适合拆出 `jdbc-test-cli` 或 `jdbc-test-spring-boot-starter`，可以延迟，但：

- `core`
- `worker`
- `server`
- `web`

必须作为主要边界建立起来。

数据库特定 adapter 如果现有数量很多，可再拆：

```text
jdbc-test-adapters/
```

或每个 DB 一个模块；如果当前规模不大，先留在 core 的 adapter package，避免过度拆分。

---

# 6. 核心领域模型

不要先从 Controller 开始写。先建立清晰领域模型。

## 6.1 JdbcDriverPackage

代表一个逻辑 JDBC Driver 版本。

建议字段：

```text
id                 UUID
name               VARCHAR
vendor             VARCHAR
version            VARCHAR
driverClass        VARCHAR
urlPrefix          VARCHAR nullable
sourceType         ENUM
status             ENUM
createdAt
updatedAt
```

`sourceType`：

```text
BUILTIN
UPLOAD
MAVEN   // 可以第二阶段实现
```

Driver Package 与 Driver Artifact 是 1:N。

---

## 6.2 JdbcDriverArtifact

```text
id
driverId
fileName
storageKey
size
sha256
createdAt
```

不要只允许一个 JAR。

Driver Package 必须能够关联多个 JAR。

---

## 6.3 DatabaseConnectionProfile

建议字段：

```text
id
name
databaseType
jdbcUrl
username
encryptedPassword
passwordSaved
defaultDriverId nullable
description
createdAt
updatedAt
lastConnectionTestAt nullable
lastConnectionTestStatus nullable
```

要求：

- API 永不返回 `encryptedPassword`。
- 密码保存必须使用加密服务。
- 删除 Driver 前检查是否仍被 Connection 引用。

---

## 6.4 TestRun

```text
id UUID
connectionId
driverId

status
profile
createdAt
startedAt
finishedAt

totalCases
completedCases
passed
failed
skipped

currentModule
currentTestCase
failureReason
```

必须保存运行时 Snapshot，以保证历史报告可复现：

```text
connectionNameSnapshot
databaseTypeSnapshot
jdbcUrlSanitizedSnapshot

driverNameSnapshot
driverVendorSnapshot
driverVersionSnapshot
driverSha256Snapshot
driverClassSnapshot
```

不要只依赖关联表里的当前值。

---

## 6.5 TestRunModule

记录本次选择了哪些模块。

```text
id
runId
moduleId
moduleName
```

如果模块数量稳定，也可以 JSONB 存 selection，但运行结果仍应结构化存储。

---

## 6.6 TestCaseResult

```text
id
runId
module
scenarioId
displayName
testClass
testMethod
status
durationMs
message
stackTrace
startedAt
finishedAt
```

`status`：

```text
PASSED
FAILED
SKIPPED
ABORTED
```

message / stackTrace 在写库前进行敏感信息脱敏。

---

# 7. Driver Storage

## 7.1 第一版

JAR 不放 PostgreSQL bytea。

使用文件系统 Volume：

```text
/data/jdbc-test/drivers/
```

建议：

```text
/data/jdbc-test/drivers/{driverId}/
├── manifest.json
├── acmedb-jdbc.jar
└── acmedb-common.jar
```

数据库只保存 metadata 和 storage key。

定义抽象：

```java
public interface DriverStorage {
    StoredDriverArtifact store(...);
    Path resolve(...);
    void delete(...);
}
```

第一版实现：

```text
FileSystemDriverStorage
```

未来可增加：

```text
S3DriverStorage
MinioDriverStorage
```

---

## 7.2 上传验证

上传时：

- 只接受 `.jar`。
- 配置最大单文件大小。
- 配置单个 Driver Package 最大总大小。
- 服务端生成文件名/路径，不信任用户路径。
- 计算 SHA-256。
- 禁止 zip-slip 类路径问题。
- 不在 Server JVM 中执行 JAR 类。

可以安全读取 ZIP/JAR 元数据，例如：

```text
META-INF/MANIFEST.MF
META-INF/services/java.sql.Driver
```

但仅作为文本/ZIP entry 读取。

---

## 7.3 Driver Class 自动发现

优先读取：

```text
META-INF/services/java.sql.Driver
```

如果发现一个实现：

自动填充 `driverClass`。

如果发现多个：

让用户选择。

如果没有：

让用户手工输入。

**真正验证 Class 能否加载必须在 Worker 中完成。**

---

# 8. Worker JVM 设计

这是本次重构最重要的架构边界之一。

## 8.1 原则

Spring Boot Server：

- 不加载用户 JDBC Driver。
- 不直接执行 JDBC Compatibility Test。
- 只负责：
  - 任务管理
  - 数据持久化
  - Driver Storage
  - Worker 生命周期
  - SSE 推送
  - Report 查询

Worker：

- 每次 Test Run 启动一个独立 JVM Process。
- 读取本次 Run 配置。
- 构建独立 Driver ClassLoader。
- 加载 `java.sql.Driver`。
- 建立 target database Connection。
- 使用 JUnit Platform 执行指定测试。
- 把事件流发送给 Server。
- 测试结束后退出。

---

## 8.2 Worker 启动方式

MVP 使用 `ProcessBuilder`。

概念上：

```text
java -jar jdbc-test-worker.jar \
  --run-id=<uuid> \
  --config=<temporary-config-path>
```

配置文件必须：

- 位于 run workspace；
- 权限尽可能限制；
- 测试结束后删除；
- 密码不得出现在命令行参数中，防止进程列表泄露。

更推荐：

- 临时文件；
- 或通过 stdin 传递敏感配置。

不要：

```text
--password=plain-text-password
```

---

## 8.3 每次 Run 独立 Workspace

```text
/data/jdbc-test/runs/{runId}/
├── config/
├── logs/
├── report/
└── temp/
```

任务完成后：

- 保留 report；
- 临时 credential 配置删除；
- temp 删除；
- 根据 retention policy 清理旧日志。

---

## 8.4 Driver ClassLoader

Worker 内为本次 Driver Package 创建独立 ClassLoader。

逻辑：

```text
Driver Package Artifacts
        ↓
URLClassLoader
        ↓
Class.forName(driverClass, true, classLoader)
        ↓
instantiate java.sql.Driver
        ↓
driver.connect(jdbcUrl, properties)
```

优先直接调用：

```java
Driver#connect(...)
```

不要依赖全局 `DriverManager` 注册状态。

原因：

- 避免不同 Driver 版本互相污染。
- 避免静态 Driver 注册残留。
- 更容易同时测试多个驱动版本。

---

## 8.5 Worker Event Protocol

建议 Worker stdout 输出 **NDJSON**，每行一个结构化事件。

例如：

```json
{"type":"RUN_STARTED","runId":"...","timestamp":"..."}
{"type":"MODULE_STARTED","module":"statement","timestamp":"..."}
{"type":"CASE_STARTED","scenarioId":"...","displayName":"..."}
{"type":"CASE_FINISHED","scenarioId":"...","status":"PASSED","durationMs":125}
{"type":"RUN_FINISHED","status":"SUCCEEDED","passed":10,"failed":0,"skipped":0}
```

Server：

1. 启动 Process。
2. 读取 stdout。
3. 解析 event。
4. 更新数据库。
5. Publish 内部 RunEvent。
6. SSE 推给浏览器。

Worker 的普通诊断日志使用 stderr，避免污染协议 stdout。

---

## 8.6 Cancel

取消流程：

```text
POST /api/runs/{id}/cancel
```

Server：

1. 状态改为 cancelling。
2. `process.destroy()`。
3. 等待 configurable grace period。
4. 仍未退出则 `destroyForcibly()`。
5. 最终状态 `CANCELLED`。
6. 尝试执行 cleanup，并记录 cleanup 结果。

---

## 8.7 并发

MVP：

- 默认 `max-concurrent-runs=1`。
- 其他 Run 进入 `QUEUED`。
- 配置化，例如：

```yaml
jdbc-test:
  execution:
    max-concurrent-runs: 1
```

后续再支持更多 Worker。

不要第一版就实现分布式调度。

---

# 9. JUnit Platform / 测试模块重构

Codex 必须先盘点当前已有测试。

创建一个：

```text
Test Catalog
```

用于向 Web 暴露可运行的模块与测试项。

## 9.1 推荐使用 JUnit Tag

例如：

```java
@Tag("connection")
class ConnectionTest {
}
```

```java
@Tag("statement")
class StatementTest {
}
```

然后 Worker 使用：

```java
TagFilter.includeTags(...)
```

执行用户选择的模块。

如果当前测试结构已有自己的 profile/filter 机制，不要立即删除。

先做兼容层：

```text
Web Module Selection
        ↓
Test Selection Model
        ↓
现有 Filter / JUnit Tag
```

等测试结果一致后再逐步统一。

---

## 9.2 Test Catalog API 数据结构

推荐：

```json
{
  "profiles": [
    {
      "id": "core",
      "name": "Core"
    },
    {
      "id": "full",
      "name": "Full"
    }
  ],
  "modules": [
    {
      "id": "statement",
      "name": "Statement",
      "description": "java.sql.Statement compatibility tests",
      "testCount": 20
    }
  ]
}
```

测试模块必须来自真实 Test Catalog，不要在 React 写死一份长期独立列表。

---

# 10. 平台数据库：PostgreSQL + JPA + Flyway

## 10.1 原则

JPA 只管理平台数据：

- Driver Package
- Driver Artifact metadata
- Connection Profile
- Test Run
- Test Case Result
- Settings（如有）

目标数据库永远不进入平台 EntityManager。

---

## 10.2 Flyway

禁止依赖：

```text
hibernate.hbm2ddl.auto=update
```

生产/正式环境使用：

```text
validate
```

所有 schema 变化使用 Flyway：

```text
V1__init.sql
V2__add_driver_snapshot.sql
...
```

Local dev 可以由 Docker Compose 自动创建 PostgreSQL database，但 table schema 仍由 Flyway 管理。

---

## 10.3 密码加密

定义：

```java
CredentialEncryptionService
```

推荐：

- AES-256-GCM
- 每条 credential 独立 nonce
- Master Key 从环境变量或 secret file 获取

例如：

```text
JDBC_TEST_MASTER_KEY
```

API：

- password 是 write-only field。
- GET connection 不返回 password。
- Update 时 password 为空表示不修改原值。

如果未配置 Master Key：

- 可以禁止保存 password；
- 或应用启动失败并给出明确错误。

具体策略写入 README，不能静默退化为明文存储。

---

# 11. Backend API

所有 API 使用 `/api` 前缀。

响应格式统一，错误使用明确 error code。

## 11.1 Drivers

```text
GET    /api/drivers
GET    /api/drivers/{id}
POST   /api/drivers/upload
PUT    /api/drivers/{id}
DELETE /api/drivers/{id}
POST   /api/drivers/{id}/validate
```

`POST /upload`：

- multipart
- metadata JSON
- 1..N JAR files

后续可增加：

```text
POST /api/drivers/maven
```

---

## 11.2 Connections

```text
GET    /api/connections
GET    /api/connections/{id}
POST   /api/connections
PUT    /api/connections/{id}
DELETE /api/connections/{id}

POST   /api/connections/test
```

`/test` 支持尚未保存的 Connection Draft。

测试连接依然启动 short-lived Worker/Probe，不在 Server 加载 Driver。

---

## 11.3 Test Catalog

```text
GET /api/test-catalog
```

返回：

- profiles
- modules
- test count
- module descriptions

---

## 11.4 Runs

```text
POST /api/runs
GET  /api/runs
GET  /api/runs/{id}
POST /api/runs/{id}/cancel

GET  /api/runs/{id}/events
GET  /api/runs/{id}/results
GET  /api/runs/{id}/report
GET  /api/runs/{id}/report.json
```

创建 Run 示例：

```json
{
  "connectionId": "uuid",
  "driverId": "uuid",
  "profile": "CUSTOM",
  "modules": [
    "connection",
    "statement"
  ],
  "destructiveConsent": true
}
```

返回：

```json
{
  "id": "uuid",
  "status": "QUEUED"
}
```

---

## 11.5 Dashboard

MVP：

```text
GET /api/dashboard/summary
```

返回至少：

```text
totalRuns
successfulRuns
failedRuns
totalTestCases
passRate
averageDuration
recentRuns
moduleStats
```

不要为了 Dashboard 引入额外 OLAP 技术。

---

# 12. SSE

使用：

```text
GET /api/runs/{id}/events
```

Content-Type：

```text
text/event-stream
```

事件示例：

```text
run.status
module.started
case.started
case.finished
run.progress
log
run.finished
```

前端断线重连后：

- 先通过 `GET /api/runs/{id}` 获取当前 snapshot；
- 再建立 SSE；
- 不要求第一版实现复杂 event replay。

Server 必须有连接清理机制，避免 Run 完成后 emitter 泄漏。

---

# 13. Frontend 信息架构

整体风格参考用户给出的 CPA Usage Keeper 观感，但：

- 不复制其源码；
- 不复制品牌；
- 只借鉴：
  - 清爽白底
  - 大圆角 Card
  - 数据指标区
  - 趋势图
  - donut chart
  - 清晰留白
  - 轻量 dashboard

## 13.1 页面

```text
/
├── Dashboard
├── Drivers
│   ├── Driver List
│   └── Driver Detail / Add
├── Connections
│   ├── Connection List
│   └── Add / Edit Connection
├── Runs
│   ├── New Run
│   ├── Run History
│   └── Run Detail
└── Settings (可后置)
```

---

## 13.2 Dashboard

顶部指标卡：

```text
Total Runs
Test Cases
Pass Rate
Avg Duration
```

中间：

- Runs / Tests timeline
- Passed / Failed trend

下面：

- Request/Test Outcomes 类似柱状图
- Module Composition donut
- Recent Runs table

---

## 13.3 Drivers

列表显示：

- Name
- Vendor
- Version
- Driver Class
- Source
- Artifact count
- Created At

新增流程：

```text
1. Basic Info
2. Upload JAR(s)
3. Detect Driver
4. Validate
5. Save
```

---

## 13.4 Connections

Card 或 Table 显示：

- Name
- DB type
- Sanitized JDBC URL
- Driver
- Last Test
- Status

操作：

```text
Test
Edit
Delete
Start Run
```

---

## 13.5 New Run Wizard

Step 1：

```text
Choose Connection
```

Step 2：

```text
Choose Driver
```

Step 3：

```text
Choose Profile / Modules
```

Step 4：

```text
Confirm destructive test consent
```

Step 5：

```text
Run
```

---

## 13.6 Running / Detail

顶端状态卡。

Progress：

```text
31 / 120
```

统计：

```text
Passed
Failed
Skipped
Elapsed
```

Tabs：

```text
Overview
Test Cases
Logs
Environment
Report
```

Test Case 表支持：

- module
- status
- duration
- search
- filter
- failure only

---

# 14. 前端工程约束

使用：

```text
React
TypeScript
Vite
Tailwind
shadcn/ui
TanStack Query
TanStack Table
ECharts
React Router
```

建议目录：

```text
jdbc-test-web/src/
├── app/
├── pages/
├── components/
├── features/
│   ├── drivers/
│   ├── connections/
│   ├── runs/
│   └── dashboard/
├── api/
├── hooks/
├── lib/
└── types/
```

规则：

- Server state 使用 TanStack Query。
- 不要把所有状态塞进一个全局 store。
- TypeScript 开启 strict。
- API DTO 定义统一。
- UI 不直接依赖 JPA Entity JSON 结构。
- Backend 使用 DTO，不直接把 Entity 暴露给前端。

---

# 15. Report Model

保留当前已有 report 能力。

如果当前已有 `report.json`：

- 先审计 schema。
- 尽量兼容。
- 增加：

```text
schemaVersion
runId
driver
target
summary
modules
cases
environment
```

建议将 JSON 作为权威 machine-readable report。

HTML/Markdown 是 JSON 的派生产物。

报告必须脱敏：

- password
- access token
- secret query parameter
- credential property

---

# 16. Docker 部署

MVP 的 Docker Compose：

```text
jdbc-test-app
postgres
```

Volume：

```text
postgres_data
jdbc_test_drivers
jdbc_test_runs
```

环境变量至少：

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
JDBC_TEST_MASTER_KEY
JDBC_TEST_DATA_DIR
```

建议 Web production build 最终由 Spring Boot 同源提供静态资源，以降低部署复杂度：

```text
Browser
  ↓
Spring Boot :8080
  ├── /api/*
  └── React static assets
```

开发模式：

```text
Vite :5173
Spring Boot :8080
```

由 Vite proxy `/api`。

第一版不要额外引入 Nginx，除非构建/部署确实需要。

---

# 17. 安全要求

MVP 即使是 Self-hosted，也必须完成以下基础项：

- [ ] Password 加密保存
- [ ] Password 不出现在 GET API
- [ ] Password 不写日志
- [ ] JDBC URL 脱敏
- [ ] 用户上传 JAR 不在 Server JVM 加载
- [ ] 文件名路径规范化
- [ ] 上传大小限制
- [ ] Driver SHA-256
- [ ] Worker timeout
- [ ] Worker cancel
- [ ] Worker 并发上限
- [ ] Run-specific workspace
- [ ] destructive consent
- [ ] 数据库测试使用独立 schema/namespace（现有能力允许时）
- [ ] 报告脱敏
- [ ] REST 输入 Bean Validation
- [ ] 不把异常堆栈原样返回给普通 API 错误响应
- [ ] Server 日志中区分 internal error 和 user-visible message

暂不要求：

- 多租户
- OAuth/OIDC
- RBAC
- 公网 SaaS 安全模型

如果当前项目没有用户系统，MVP 可以保持单用户/self-hosted。

---

# 18. 测试策略

## 18.1 Java Unit Tests

至少覆盖：

- DriverStorage
- Driver artifact hash
- Driver SPI metadata detection
- CredentialEncryptionService
- JDBC URL sanitizer
- Test selection
- Worker event parser
- Run state machine
- Report transformation

---

## 18.2 Integration Tests

使用 Testcontainers PostgreSQL 测平台持久层。

测试：

- Flyway migration
- JPA repositories
- Driver upload metadata
- Connection CRUD
- Run CRUD

---

## 18.3 Worker Integration Test

至少使用一个公开 JDBC Driver + Testcontainers PostgreSQL 做真实 Worker 测试：

```text
Server/Test Harness
    ↓
spawn Worker JVM
    ↓
PostgreSQL JDBC Driver
    ↓
PostgreSQL Testcontainer
```

验证：

- Driver load
- Connection
- Test Catalog selection
- NDJSON event
- Report
- Process exit code
- cleanup

这是非常重要的 E2E backend test。

---

## 18.4 Frontend Tests

至少：

- Vitest
- React Testing Library

覆盖：

- Driver upload form
- Connection form
- New Run selection
- Run status rendering
- Report filter

可以增加 1 个 Playwright smoke test，但不是第一阻塞项。

---

# 19. CI

建议 GitHub Actions：

## Java

```text
JDK 21
./mvnw clean verify
```

## Frontend

```text
npm ci
npm run lint
npm run test
npm run build
```

## Docker

```text
docker build
```

如果 integration tests 需要 Docker，CI 使用可运行 Testcontainers 的 runner。

不要在第一版做复杂 release pipeline。

---

# 20. 分阶段执行计划

---

## Phase 0 — Audit / Baseline

### 任务

- [ ] 输出当前仓库目录树。
- [ ] 阅读根 `pom.xml`。
- [ ] 识别当前 Java 版本、JUnit 版本、Maven 插件。
- [ ] 列出现有 JDBC 测试 Class。
- [ ] 列出现有数据库 adapter / dialect / config。
- [ ] 找出现有 test profile/filter。
- [ ] 找出现有 runner。
- [ ] 找出现有 report.json / HTML / Markdown 生成逻辑。
- [ ] 找出所有可能需要保持兼容的 CLI/config。
- [ ] 运行现有测试。
- [ ] 写 `docs/current-architecture.md`。
- [ ] 写 `docs/migration-notes.md`，列出本文计划与实际代码差异。

### 验收

- 当前项目基线可复现。
- 明确哪些代码会迁入 core。
- 明确哪些行为必须保留。
- 此阶段不要进行大规模重构。

---

## Phase 1 — Maven Multi-module Foundation

### 任务

- [ ] 根 pom 改为 aggregator。
- [ ] Maven Enforcer：
  - Maven `[3.9.0,)`
  - Java `[21,22)`
- [ ] Maven Wrapper 3.9.15。
- [ ] 建立至少：
  - `jdbc-test-core`
  - `jdbc-test-worker`
  - `jdbc-test-server`
- [ ] 保留现有测试可运行。
- [ ] 如果 web 还没开始，只创建目录和 README 即可。
- [ ] 配置统一：
  - compiler
  - surefire
  - failsafe
  - encoding
  - dependency management

### 验收

```bash
./mvnw clean verify
```

成功。

现有 JDBC 测试没有因为“搬目录”而被无理由删除。

---

## Phase 2 — Core Test Catalog / Test Selection

### 任务

- [ ] 从现有测试抽出 Test Catalog。
- [ ] 定义：
  - `TestProfile`
  - `TestModule`
  - `TestDescriptor`
  - `TestSelection`
- [ ] 给现有测试补充一致的 Tag / metadata。
- [ ] 支持：
  - Core
  - Full
  - Custom modules
- [ ] 兼容现有 filter/profile 配置。
- [ ] 不改变测试语义。

### 验收

可以通过 Java API 指定：

```text
statement
```

只执行 Statement 模块。

可以指定：

```text
connection + statement
```

只执行这两个模块。

---

## Phase 3 — Worker

### 任务

- [ ] 建立 `jdbc-test-worker`。
- [ ] 建立 Worker CLI entry。
- [ ] 实现 Driver Package classpath。
- [ ] 使用独立 ClassLoader。
- [ ] 通过 `java.sql.Driver#connect()` 建立连接。
- [ ] 使用 JUnit Platform Launcher。
- [ ] 输出 NDJSON event。
- [ ] stderr 用于 Worker 日志。
- [ ] 支持 timeout。
- [ ] 支持 exit code。
- [ ] 生成 report JSON。
- [ ] 增加 Worker Integration Test。

### 验收

不启动 Spring Boot，也可以执行：

```text
worker + driver jars + run config
```

并完成一次 PostgreSQL JDBC 测试。

---

## Phase 4 — Server Persistence Foundation

### 任务

- [ ] Spring Boot 4.1.x。
- [ ] Spring Data JPA。
- [ ] PostgreSQL。
- [ ] Flyway。
- [ ] Entities：
  - DriverPackage
  - DriverArtifact
  - DatabaseConnectionProfile
  - TestRun
  - TestRunModule
  - TestCaseResult
- [ ] Repository。
- [ ] DTO。
- [ ] Mapper。
- [ ] Validation。
- [ ] CredentialEncryptionService。
- [ ] URL Sanitizer。

### 验收

- Server 可连接 PostgreSQL。
- Flyway 自动迁移。
- `ddl-auto` 不使用 update。
- CRUD integration tests 通过。

---

## Phase 5 — Driver Management

### 任务

- [ ] `DriverStorage` abstraction。
- [ ] FileSystem implementation。
- [ ] multipart multi-JAR upload。
- [ ] SHA-256。
- [ ] SPI metadata detection。
- [ ] Driver CRUD API。
- [ ] Driver validate Worker command。
- [ ] 被 Connection 引用的 Driver 删除保护。

### 验收

可以在 Web API：

1. 上传 2 个 JAR。
2. 保存为同一个 Driver Package。
3. 自动/手工确定 Driver Class。
4. Worker 能正确加载。
5. Server 主 JVM 不执行 Driver class。

---

## Phase 6 — Connection Management

### 任务

- [ ] Connection CRUD。
- [ ] default driver。
- [ ] password encryption。
- [ ] password write-only。
- [ ] Test Connection endpoint。
- [ ] Test Connection 使用 Worker probe。
- [ ] Last test status。

### 验收

用户可保存：

```text
PostgreSQL 16
MySQL 8
Vendor Private DB
```

并分别绑定不同 Driver。

---

## Phase 7 — Test Run Orchestration + SSE

### 任务

- [x] `TestRunService`
- [x] `RunQueue`（由 `TestRunScheduler` 实现本地 FIFO）
- [x] `WorkerProcessManager`（由 `TestRunExecutor` 管理独立进程）
- [x] max concurrent runs
- [x] run workspace
- [x] NDJSON parser
- [x] Run state machine
- [x] TestCaseResult persistence
- [x] SSE endpoint
- [x] cancel
- [x] timeout
- [x] failure handling
- [x] Server restart 后将仍处于 RUNNING 的 orphaned runs 标为 interrupted/failed，不能永远卡住。

### 验收

创建 Run 后：

```text
QUEUED
→ PREPARING
→ RUNNING
→ SUCCEEDED/FAILED
```

浏览器/API SSE 能实时收到进度。

2026-10-01 Deterministic Orchestration Verification：PASS。55 项无 Docker 确定性验证通过；完整构建 96/96，Server integration 105/105，Worker E2E 98/98；真实 Connection 17/17、Statement 23/23、HTTP SSE 与 external Driver 身份断言通过。Docker 不可用模拟验证 10 项 skipped 且 BUILD SUCCESS。详细证据见 `docs/phase7-verification.md`。停留在 Phase 7，未进入 Phase 8。

---

## Phase 8 — React Web

### 任务

- [x] Vite React TS。
- [x] Tailwind。
- [x] shadcn/ui。
- [x] TanStack Query。
- [x] React Router。
- [x] ECharts。
- [x] Layout / sidebar / topbar。
- [x] Dashboard。
- [x] Drivers。
- [x] Connections。
- [x] New Run Wizard。
- [x] Run History。
- [x] Running / Run Detail。
- [x] Report。
- [x] API error handling。
- [x] loading / empty / error states。
- [x] responsive layout。

### 验收

一个完全不修改 YAML 的用户可以从浏览器完成：

```text
上传 Driver
→ 添加 Connection
→ Test Connection
→ 选择 Statement
→ Run
→ 看实时进度
→ 看报告
```

2026-10-01 Phase 8 验收：PASS。75 项 Vitest/RTL、2 项 API-mocked Playwright、
1 项真实 full-stack Playwright 均通过；真实浏览器 Statement 23/23、Connection
17/17、Vite SSE、报告和历史验证通过。npm install/lint/test/build 成功，Java
clean verify 96/96、Server integration 105/105、Python 30/30。证据见
`docs/phase8-verification.md`，开发与现有 API 限制见 `docs/web-ui.md`。
停留在 Phase 8；等待用户确认，未进入 Phase 9。

---

## Phase 9 — Docker / Packaging

用户已正式批准 Phase 8 PASS 并授权 Phase 9。Phase 9 已完成并通过：完整
Compose production image、同源静态托管、Flyway、named volumes、SSE、真实
Statement23/Connection17 E2E、重启持久化和容器 shutdown 验收均已执行。完整证据见
`docs/phase9-verification.md`。

### 任务

- [x] React production build。
- [x] 将静态资源整合到 Spring Boot production artifact 或明确等效方案。
- [x] App Dockerfile（Red Hat OpenJDK 21 runtime，完整镜像已验收）。
- [x] Docker Compose（完整 app stack 已验收）。
- [x] PostgreSQL healthcheck。
- [x] App healthcheck（容器 health 已验收）。
- [x] volumes（PostgreSQL 与 app data named volume 均已验收）。
- [x] 环境变量文档。
- [x] startup README。

### 验收

在干净机器：

```bash
docker compose up -d
```

然后访问：

```text
http://localhost:8080
```

即可使用。

---

## Phase 10 — Documentation / Cleanup / Compatibility

### 任务

- [x] 修复 Run status filtering 与分页统计。
- [x] 增加 PostgreSQL、前端和 production browser 回归。
- [x] 核对 README、Architecture、Driver、Connection、Run、Worker、Docker 文档。
- [x] 核对 CLI、Maven/JDK、Dockerfile 与 Dockerfile.cli 边界。
- [x] 记录未实现的 Starter、Dashboard aggregate、diagnostics API 与认证范围。
- [x] 执行最终构建、兼容性和部署验证。

### 验收

README 新用户能完成：

```text
clone
docker compose up
open browser
upload/configure
run test
read report
```

Phase 10 已完成；最终证据见 `docs/phase10-verification.md`。本计划不授予发布、
Tag、推送或版本变更权限。

---

# 21. Server / Worker 边界的强制规则

以下规则属于架构约束，不能为了省事绕过：

## Server 可以做

- 存 JAR。
- 计算 SHA-256。
- 读取 ZIP entry。
- 读取 `META-INF/services/java.sql.Driver` 文本。
- 启动 Worker。
- 接收 Worker event。
- 存 Test Result。
- 推 SSE。

## Server 不可以做

- `Class.forName(userDriverClass)`。
- `new user.jdbc.Driver()`。
- 在 Server JVM 注册用户 Driver。
- 用用户 Driver 连接 target DB。
- 执行用户 Driver static initializer。
- 把 Driver JAR 加进主应用 classpath。

---

# 22. Run Reproducibility

历史 Run 必须能回答：

```text
到底测了哪个数据库？
到底用了哪个 Driver？
到底用了哪个 Driver 二进制文件？
到底跑了哪些 Test Module？
当时测试结果是什么？
```

因此 Run 必须保存：

- Connection snapshot
- sanitized JDBC URL
- database type
- Driver ID
- Driver name
- Driver version
- Driver class
- Driver artifact SHA-256 / combined package fingerprint
- selected modules
- report schema version
- app version / git commit（可获取时）

推荐 Driver Package fingerprint：

```text
sort(artifact.sha256)
→ concatenate
→ SHA-256
```

---

# 23. 数据库厂商私有 JDBC Driver 场景

必须保证以下场景成为一等公民，而不是 workaround：

```text
某数据库厂商内部有：
vendor-jdbc.jar
vendor-common.jar

这些 JAR：
- 不在 Maven Central
- 不在公开 Nexus
- 只能本地拿到
```

用户应能：

```text
Drivers
→ Add Driver
→ Upload vendor-jdbc.jar + vendor-common.jar
→ 设置 Driver Class
→ Validate
→ Save
```

然后：

```text
Connections
→ Add Database
→ 选择这个 Driver
→ Test Connection
```

再：

```text
New Run
→ 选择 Connection
→ 选择该 Driver 版本
→ 选择 Statement
→ Run
```

禁止要求用户：

```text
mvn install:install-file
```

禁止要求用户修改项目 `pom.xml`。

这属于平台最关键的产品能力之一。

---

# 24. 暂不实现的内容

除非现有代码非常容易支持，否则第一版不要扩展以下内容：

- Kubernetes Worker
- 多机器 Distributed Worker
- Multi-tenant
- RBAC
- OAuth/OIDC
- SaaS billing
- 复杂组织/团队
- S3/MinIO Driver Storage
- Driver Marketplace
- 自动下载安装任意互联网 Driver
- Native library Driver 完整沙箱支持
- 大规模并行压力测试
- AI 自动分析失败原因

这些都可以在 MVP 稳定后追加。

---

# 25. 可选 Phase 2 功能

MVP 完成后再考虑：

## Run Comparison

```text
Run A vs Run B
```

按 `scenarioId` 对齐：

```text
PASS → PASS
PASS → FAIL   Regression
FAIL → PASS   Fixed
FAIL → FAIL
NEW
REMOVED
```

支持：

```text
Driver 5.2 vs Driver 5.3
DB 5.0 vs DB 6.0
Baseline vs Current
```

---

## Maven Driver Source

增加：

```text
groupId
artifactId
version
repository URL
```

运行期用专门的 artifact resolver 下载至 Driver Storage。

不要调用系统 Maven 命令把它安装到 `~/.m2` 后再依赖全局状态。

---

## Worker Container Isolation

公共/更高安全环境：

```text
Server
  ↓
Ephemeral Worker Container
  ↓
Target DB
```

限制：

- CPU
- Memory
- Timeout
- Filesystem
- Linux capabilities
- Network policy

---

# 26. 强制工程规范与代码质量约束

> 本章是强制约束，不是建议。Codex 在任何 Phase 中新增或修改代码时都必须遵守。
>
> 规范优先级：
>
> ```text
> 本项目明确约束
> > 阿里巴巴 Java 开发手册
> > Spring / Java / React 社区通用最佳实践
> > 框架默认行为
> ```

## 26.1 总体原则

所有新增或修改代码必须同时满足：

- 遵守《阿里巴巴 Java 开发手册》的核心编码规范；
- REST API 遵守统一、清晰的 RESTful 语义；
- 遵守 KISS、YAGNI、DRY，但禁止为了“模式正确”而过度设计；
- 优先保证正确性、可读性、可测试性和可维护性；
- 不制造无意义分层、空壳类、废话式注释、未使用代码和无消费者扩展点；
- 不为了减少几行代码引入难以理解的泛型、反射或框架；
- 每个 Phase 只修改完成当前目标真正需要的内容，避免无关大范围重构。

核心准则：

> **Prefer the smallest clear implementation that fully satisfies the current requirement. Do not introduce abstractions, layers, patterns, dependencies, configuration options, or extension points without a concrete present need.**

中文解释：

> 优先实现满足当前需求的最小、清晰、可测试方案。没有明确的当前需求，不要擅自增加抽象层、设计模式、依赖、配置项或扩展点。

同时：

> 简单不能以破坏安全边界、测试能力、数据一致性或可维护性为代价。

---

## 26.2 阿里巴巴 Java 开发手册

Java 代码必须遵守《阿里巴巴 Java 开发手册》的核心规则，重点包括：

- 命名；
- 常量；
- 集合；
- 并发；
- 异常；
- 日志；
- 单元测试；
- 数据库访问；
- 安全；
- 代码可读性。

不要为了“机械符合规范”牺牲项目已有的合理约定；如果项目已有明确规则，以项目规则优先。

### 命名

类名使用 UpperCamelCase：

```java
DatabaseConnectionService
JdbcDriverPackage
TestRunController
WorkerProcessManager
```

方法和字段使用 lowerCamelCase：

```java
createTestRun()
findDriverById()
testConnection()
```

常量使用大写下划线：

```java
MAX_CONCURRENT_RUNS
DEFAULT_TIMEOUT
```

禁止无意义命名：

```text
data1
temp2
obj
xxx
abc
handle1
CommonUtil2
ManagerImpl2
```

除非变量作用域非常小且含义明确。

---

## 26.3 禁止魔法值

禁止：

```java
if (status == 3) {
}
```

应使用明确类型：

```java
if (status == TestRunStatus.RUNNING) {
}
```

系统参数如果属于可配置行为，应进入类型安全配置：

```yaml
jdbc-test:
  execution:
    timeout: 30s
```

或使用有明确含义的常量。

不要把同一个业务字符串或数字散落在多个类中。

---

## 26.4 RESTful API 设计规范

所有 HTTP API 必须保持一致的 RESTful 风格。

### URI 使用资源名

推荐：

```text
GET    /api/drivers
GET    /api/drivers/{id}
POST   /api/drivers
PUT    /api/drivers/{id}
DELETE /api/drivers/{id}

GET    /api/connections
POST   /api/connections

GET    /api/runs
POST   /api/runs
GET    /api/runs/{id}
```

禁止：

```text
/api/getDrivers
/api/createDriver
/api/deleteConnection
/api/getRunById
/api/doRunTest
```

URI 规则：

- 小写；
- 资源名使用复数；
- 多单词使用 `kebab-case`；
- 不随意把 CRUD 动词放入 URI；
- 子资源使用资源层级表达。

例如：

```text
GET /api/runs/{id}/results
GET /api/runs/{id}/report
GET /api/runs/{id}/events
```

确实属于动作而不是普通 CRUD 时，可以使用动作子资源：

```text
POST /api/runs/{id}/cancel
POST /api/connections/test
POST /api/drivers/{id}/validate
```

不要为了追求“教科书纯 REST”而设计难以理解的接口。

---

## 26.5 HTTP Method 语义

严格遵守：

```text
GET      查询，不产生业务状态修改
POST     创建资源或执行非幂等动作
PUT      整体替换 / 更新
PATCH    局部更新
DELETE   删除资源
```

禁止为了方便所有接口都使用 `POST`。

GET 请求不得用于：

- 创建；
- 删除；
- 启动测试；
- 取消任务；
- 修改配置。

---

## 26.6 HTTP Status Code

必须正确使用 HTTP 状态码，而不是所有请求都返回 200。

建议：

```text
200 OK
201 Created
204 No Content

400 Bad Request
401 Unauthorized
403 Forbidden
404 Not Found
409 Conflict
422 Unprocessable Entity

500 Internal Server Error
```

示例：

```text
POST /api/connections
→ 201 Created
```

```text
DELETE /api/drivers/{id}
→ 204 No Content
```

```text
GET /api/runs/{id}
→ 404 Not Found
```

```text
DELETE /api/drivers/{id}
→ 409 Conflict   // Driver 正被 Connection 引用
```

禁止：

```text
HTTP 200
{
  "code": 500,
  "success": false
}
```

用 JSON 中的伪状态码代替真实 HTTP 状态码。

---

## 26.7 API Response

除非存在明确需求，不要创建万能响应包装：

```text
Result<T>
ApiResult<T>
CommonResult<T>
RestResult<T>
ResponseWrapper<T>
```

正常资源直接返回 DTO：

```json
{
  "id": "uuid",
  "name": "PostgreSQL 16",
  "status": "AVAILABLE"
}
```

错误响应统一结构，例如：

```json
{
  "code": "DRIVER_NOT_FOUND",
  "message": "JDBC driver was not found",
  "timestamp": "2026-09-29T10:00:00Z",
  "path": "/api/drivers/..."
}
```

错误码必须稳定、可测试、可供前端判断。

建议至少定义：

```text
DRIVER_NOT_FOUND
DRIVER_IN_USE
DRIVER_INVALID
DRIVER_VALIDATION_FAILED

CONNECTION_NOT_FOUND
CONNECTION_TEST_FAILED

RUN_NOT_FOUND
RUN_ALREADY_FINISHED
RUN_CANNOT_CANCEL
RUN_EXECUTION_FAILED

INVALID_TEST_SELECTION
DESTRUCTIVE_CONSENT_REQUIRED
VALIDATION_ERROR
INTERNAL_ERROR
```

不要直接使用 Java Exception 类名作为 API error code。

---

## 26.8 Controller 必须保持薄

Controller 负责：

```text
HTTP Request
↓
Validation
↓
DTO
↓
Service
↓
HTTP Response
```

Controller 不允许：

- 写大段业务逻辑；
- 直接操作 Repository；
- 直接启动 Worker Process；
- 直接访问 Driver Storage；
- 直接做密码加解密；
- 直接解析 Worker NDJSON；
- 直接编排复杂状态机。

但也禁止为了“Controller 要薄”制造：

```text
Controller
→ Facade
→ Manager
→ Service
→ Processor
→ Handler
→ Repository
```

如果：

```text
Controller → Service → Repository
```

已经足够，就不要额外增加层。

---

## 26.9 分层原则

采用“足够清晰”的分层，而不是“层数越多越专业”。

推荐：

```text
Controller
↓
Application / Service
↓
Repository / Infrastructure
```

只有领域逻辑确实复杂时再引入 Domain Service。

禁止为了形式创建没有独立职责的：

```text
Facade
Manager
Processor
Handler
Helper
Executor
Provider
```

每个类都必须能明确回答：

> 它负责什么？为什么这个职责不能自然地放在现有类中？

---

## 26.10 KISS

优先选择能够清楚解决当前问题的最简单方案。

如果：

```java
repository.findById(id)
```

已经够用，就不要制造：

```text
Repository
→ DataProvider
→ DataAccessor
→ QueryExecutor
→ PersistenceGateway
```

KISS 的含义是：

> 在满足正确性、可测试性、安全边界和可维护性的前提下，不引入不必要复杂度。

---

## 26.11 YAGNI

严格遵守：

> You Aren't Gonna Need It.

没有当前明确需求的功能不要提前实现。

例如 MVP 只需要：

```text
FileSystemDriverStorage
```

可以保留必要的架构边界：

```java
interface DriverStorage
```

但只实现：

```text
FileSystemDriverStorage
```

禁止仅仅因为“未来可能用到”就新增：

```text
S3DriverStorage
MinioDriverStorage
GcsDriverStorage
AzureBlobDriverStorage
StorageProviderFactory
StorageProviderRegistry
AbstractStorageProvider
```

未来真正需要时再实现。

---

## 26.12 DRY，但禁止过度抽象

重复的稳定业务规则应抽取。

但：

> 两段代码长得像，不代表它们属于同一个抽象。

禁止为了少写几行代码创建：

```java
AbstractGenericCommonBaseExecutorFactory<T, R>
```

或者难以理解的多层泛型结构。

优先级：

```text
可读性
> 正确抽象
> 减少重复行数
```

重复只有在语义稳定后再抽象。

---

## 26.13 禁止无意义代码和空壳抽象

禁止：

```java
public void initialize() {
    // TODO
}
```

禁止无意义代理：

```java
public JdbcDriver findDriver(UUID id) {
    return driverService.findDriver(id);
}
```

如果这一层没有：

- 业务规则；
- 权限；
- 事务边界；
- 数据转换；
- 编排；
- 其他真实职责；

则不要增加一层。

禁止仅为“以后可能扩展”创建没有消费者的：

```text
Factory
Strategy
Provider
Registry
Builder
Adapter
Facade
```

例外是具有明确当前架构价值的边界，例如 `DriverStorage`、Worker Protocol 等。

---

## 26.14 Dead Code

不得提交：

- 未使用 class；
- 未使用 method；
- 未使用 field；
- 未使用 import；
- 注释掉的大段旧实现；
- 临时实验代码；
- 没有调用方的扩展点；
- 无意义 TODO；
- debug output。

旧代码不需要靠注释保留，Git History 已经负责保存历史。

---

## 26.15 注释规范

注释重点解释：

> 为什么这样做。

不要重复代码本身已经表达的内容。

禁止：

```java
// Get driver by id
JdbcDriver driver = repository.findById(id);
```

推荐：

```java
// Keep the driver fingerprint on TestRun so historical reports
// remain reproducible even after the original package is removed.
```

不要生成大量废话式注释。

---

## 26.16 Javadoc

Javadoc 优先用于：

- public SPI；
- 核心架构边界；
- Worker Protocol；
- Driver ClassLoader 生命周期；
- 安全约束；
- 不直观的状态机；
- 复杂算法或行为契约。

禁止为所有 getter/setter/repository method 自动生成无价值 Javadoc。

以下内容没有价值：

```java
/**
 * Gets driver by id.
 *
 * @param id driver id
 * @return driver
 */
```

---

## 26.17 方法设计

避免超长方法。

如果一个方法同时负责：

```text
validate input
→ decrypt password
→ create workspace
→ write config
→ start process
→ parse stdout
→ persist event
→ publish SSE
→ cleanup
```

必须按职责拆分。

但不要机械规定“超过 N 行必须拆”。

拆分依据是职责和可读性，而不是行数。

---

## 26.18 参数设计

避免大量独立参数：

```java
createRun(
    UUID connectionId,
    UUID driverId,
    String profile,
    List<String> modules,
    boolean destructive,
    int timeout
)
```

参数较多且属于同一 use case 时，使用有意义的 Command：

```java
CreateTestRunCommand
```

禁止使用：

```java
Map<String, Object>
```

传递核心业务数据。

---

## 26.19 Entity / DTO

JPA Entity 只表示 persistence model。

REST API 不直接暴露 Entity。

默认保持：

```text
Entity
Request DTO
Response DTO
```

只有真正存在独立领域模型时，再增加 Domain Object。

禁止制造含义高度重复的：

```text
DO
PO
POJO
BO
VO
AO
DTO
ResponseDTO
ViewDTO
DomainDTO
```

---

## 26.20 Mapper

Entity 与 DTO 的映射，第一版优先使用显式 Java Mapper。

如果映射数量明显增加，再评估 MapStruct。

禁止通过反射式 Bean Copy 隐藏敏感字段传播：

```java
BeanUtils.copyProperties(...)
```

尤其是 credential / password 相关对象。

---

## 26.21 Lombok

如果使用 Lombok，禁止在 JPA Entity 上无脑使用：

```java
@Data
```

避免：

- `equals/hashCode` 关联递归；
- `toString()` 泄露敏感数据；
- Lazy Relation 被意外加载；
- 双向关联递归。

按需使用：

```java
@Getter
@Setter
```

复杂或敏感 Entity 可以直接显式编写代码。

---

## 26.22 Optional

允许：

```java
Optional<T>
```

表达 Repository 查询结果可能不存在。

不推荐 Optional 用于：

- Entity Field；
- DTO Field；
- Method Parameter。

Service 层通常应把 `Optional.empty()` 转换成明确业务异常。

---

## 26.23 Exception

禁止：

```java
catch (Exception e) {
    return null;
}
```

禁止吞异常。

禁止：

```java
catch (Exception e) {
    log.error("error");
}
```

然后继续假装成功。

规则：

```text
能够恢复
→ 明确恢复

不能恢复
→ 向上传递

HTTP 边界
→ @RestControllerAdvice

Worker 边界
→ 明确 failure event / exit code
```

优先捕获能够实际处理的具体异常，而不是到处 `catch (Exception)`。

---

## 26.24 Logging

使用参数化日志：

```java
log.info("Test run {} started with driver {}", runId, driverId);
```

不要：

```java
log.info("Test run " + runId + " started");
```

严格禁止日志输出：

- password；
- token；
- secret；
- encryption key；
- 完整 credential JDBC URL；
- 敏感 connection properties。

禁止垃圾日志：

```text
Entering method...
Leaving method...
Getter called...
```

日志必须有实际排障价值。

---

## 26.25 Transaction

事务必须围绕业务用例定义。

可以：

```java
@Transactional
public TestRun createRun(...) {
}
```

读取：

```java
@Transactional(readOnly = true)
```

不要在 Controller 上统一加事务。

最重要：

> Worker 测试执行期间不能持有平台 PostgreSQL 长事务。

绝对不要：

```text
BEGIN
→ launch Worker
→ wait several minutes
→ COMMIT
```

Worker 生命周期与平台数据库短事务必须解耦。

---

## 26.26 JPA 查询质量

必须检查：

- N+1；
- 无限制全表查询；
- LazyInitializationException；
- 错误使用 EAGER；
- 不必要的大对象加载；
- 高频查询缺少索引。

禁止简单粗暴把所有关系设为：

```java
FetchType.EAGER
```

需要时使用：

```text
@EntityGraph
JOIN FETCH
Projection
```

---

## 26.27 Pagination

持续增长的列表资源必须考虑分页：

```text
GET /api/runs
GET /api/drivers
GET /api/connections
```

推荐：

```text
?page=0&size=20&sort=createdAt,desc
```

必须限制最大 page size，防止：

```text
?size=999999999
```

---

## 26.28 API Versioning

MVP 使用：

```text
/api
```

不要仅因为“未来可能有 v2”就提前引入：

```text
/api/v1
```

真正出现不兼容 API 变化时，再制定 versioning 策略。

---

## 26.29 Time

Backend 优先统一使用：

```java
Instant
```

数据库统一 UTC。

API 使用 ISO-8601。

Frontend 根据浏览器时区展示。

不要无规则混用：

```text
Date
Timestamp
LocalDateTime
OffsetDateTime
Instant
```

导致时间语义混乱。

---

## 26.30 UUID

核心业务实体优先 UUID：

```text
JdbcDriverPackage
DatabaseConnectionProfile
TestRun
```

内部明细表根据实际情况可以使用 BIGINT。

Java 层优先使用真实 `UUID` 类型，不要到处无意义转成 String。

---

## 26.31 Spring Configuration

配置优先使用：

```java
@ConfigurationProperties
```

例如：

```java
@ConfigurationProperties("jdbc-test.execution")
public record ExecutionProperties(
        int maxConcurrentRuns,
        Duration timeout
) {
}
```

不要在大量类中散落：

```java
@Value("${...}")
```

配置必须类型安全、可验证，并有明确默认值或必填要求。

---

## 26.32 禁止 God Class

任何类不得同时承担大量无关职责。

例如以下设计不可接受：

```text
JdbcTestService
├── Driver Upload
├── Connection CRUD
├── Password Encryption
├── Process Launch
├── JUnit
├── SSE
├── Report
└── File Cleanup
```

必须按真实职责拆分。

---

## 26.33 也禁止碎片化

同样不要出现：

```text
StartWorkerService
StopWorkerService
ReadWorkerOutputService
ParseWorkerLineService
PersistWorkerEventService
```

每个类只有一个 trivial method，导致调用链难以阅读。

如果它们共同属于一个内聚职责，应放在：

```text
WorkerProcessManager
```

等合理组件中。

原则：

> 高内聚、低耦合，而不是“类越多越好”。

---

## 26.34 TestRun 状态机

`TestRun` 状态变化必须集中管理。

不能在多个 Service 随意：

```java
run.setStatus(...)
```

必须有明确合法状态迁移，例如：

```text
QUEUED
→ PREPARING
→ PROBING / RUNNING
→ GENERATING_REPORT
→ SUCCEEDED
```

以及：

```text
QUEUED → CANCELLED
RUNNING → FAILED
RUNNING → CANCELLED
```

非法状态迁移必须拒绝，并有测试覆盖。

---

## 26.35 Repository

Spring Data JPA Repository 应保持简单。

不要把复杂业务规则放入 Repository default method。

查询属于 persistence concern 时可以使用：

- method query；
- JPQL；
- projection；
- Specification；
- custom repository。

业务流程留在 Service / Application 层。

---

## 26.36 PostgreSQL Schema 规范

表和字段统一使用 `snake_case`。

例如：

```text
jdbc_driver_package
jdbc_driver_artifact
database_connection_profile
test_run
test_case_result
```

为真实高频查询创建必要索引，例如：

```text
test_run.status
test_run.created_at
test_case_result.run_id
jdbc_driver_artifact.driver_id
```

不要在没有查询场景时滥建索引。

---

## 26.37 Flyway Migration

Migration 一旦进入主分支：

- 不随意修改旧 migration；
- schema 变化创建新版本；
- migration 必须可以从干净数据库完整执行；
- 文件名必须明确表达变化。

例如：

```text
V1__init_schema.sql
V2__add_driver_fingerprint.sql
```

生产配置禁止依赖 `ddl-auto=update`。

---

## 26.38 Frontend 代码约束

Frontend 同样遵守：

```text
KISS
YAGNI
DRY
```

禁止一个简单 Feature 人为制造：

```text
hooks/
services/
managers/
repositories/
providers/
contexts/
stores/
adapters/
```

多层转发。

推荐按 Feature 组织：

```text
features/
├── drivers/
├── connections/
├── runs/
└── dashboard/
```

每个 Feature 只建立当前真正需要的：

```text
api
components
hooks
types
```

---

## 26.39 React State

Server State：

```text
TanStack Query
```

本地 UI State：

```text
useState / useReducer
```

只有真正存在复杂跨页面客户端状态时，才评估全局状态库。

禁止因为“项目比较大”就默认引入 Redux/Zustand。

---

## 26.40 React Component

禁止复制粘贴大量几乎一致的组件。

同时也不要为了“复用”设计巨大 Generic Component。

抽象原则：

> 重复稳定后再抽象。

第一版禁止打造“万能 CRUD 页面生成器”“万能动态表单框架”等无当前必要性的基础设施。

---

## 26.41 TypeScript

必须开启：

```text
strict: true
```

禁止滥用：

```typescript
any
```

第三方边界确实无法避免时，应局部隔离并尽快转换为明确类型。

核心业务数据禁止长期使用：

```typescript
Record<string, any>
```

在系统中传播。

---

## 26.42 UI Dependency

UI 统一使用：

```text
Tailwind CSS
shadcn/ui
```

除非存在明确无法满足的问题，不额外引入：

```text
Ant Design
Material UI
Element
Bootstrap
```

避免多个 UI 框架并存导致：

- 样式冲突；
- 视觉不统一；
- Bundle 增大；
- 维护成本增加。

---

## 26.43 Dependency 约束

新增任何 Maven/NPM dependency 前必须确认：

1. JDK / Spring / React 标准能力是否已能解决；
2. 当前项目依赖是否已经能解决；
3. 新库是否明显降低复杂度；
4. 是否仍在维护；
5. 是否增加安全或运行风险。

禁止为一个简单 utility 引入大型 dependency。

每个新增关键依赖应在 Phase 汇报中说明用途。

---

## 26.44 禁止“聪明代码”

优先：

```text
明显
直接
可读
容易 Debug
```

而不是：

```text
炫技
极短
复杂泛型
隐藏控制流
大量反射
```

Driver 动态加载本身确实需要 ClassLoader，这是明确需求；这不代表其他业务代码可以滥用 reflection。

---

## 26.45 TODO 规则

允许 TODO 的情况仅限：

- 本 Phase 明确不实现但已发现的问题；
- 有清晰 follow-up 描述；
- 不影响当前完成标准。

禁止：

```java
// TODO implement later
return null;
```

作为“已完成实现”。

Phase 验收范围内不得依赖 TODO 才能工作。

---

## 26.46 安全优先于简洁

如果“更简单”的实现破坏安全边界，必须选择安全方案。

例如：

```text
在 Spring Boot Server JVM 直接加载用户 JDBC Driver
```

虽然实现更简单，但明确禁止。

因此：

```text
KISS ≠ 绕过安全边界
```

---

## 26.47 性能原则

MVP 不做无依据的微优化。

优先保证：

- 正确索引；
- 避免 N+1；
- 避免无界查询；
- 避免大文件一次性无必要加载；
- Worker IO 流式处理；
- SSE 连接正确回收；
- 不阻塞关键线程池。

禁止仅仅因为“未来规模可能很大”就提前引入：

```text
Redis
Kafka
RabbitMQ
Elasticsearch
```

除非已有明确实际需求。

---

## 26.48 测试代码同样必须高质量

测试不得：

- 大量使用固定 `Thread.sleep()`；
- 依赖不稳定随机等待；
- 依赖开发者本机已有数据库；
- 依赖开发者本地私有 `~/.m2` 状态；
- 测试顺序互相依赖；
- 隐式共享可变状态。

集成测试优先使用 Testcontainers。

等待异步状态时优先显式 polling / synchronization，而不是：

```java
Thread.sleep(5000);
```

---

## 26.49 Codex 修改范围约束

每个任务必须：

1. 只修改当前 Phase 真正需要的内容；
2. 不顺手重构无关模块；
3. 不修改无关格式；
4. 不做大范围 rename，除非当前 Phase 明确要求；
5. 不为了“统一风格”制造巨大 diff；
6. 不删除尚未确认无用的旧能力；
7. 新旧逻辑迁移并验证后，再删除兼容代码。

目标：

> 每个 Commit 都容易 Review、容易回滚、容易定位问题。

---

## 26.50 Phase 完成前强制自检

### Architecture

- [ ] 是否遵守 Server / Worker 安全边界？
- [ ] 是否错误地让 JPA 接触 Target Database？
- [ ] 是否错误地在 Server JVM 加载用户 JDBC Driver？
- [ ] 是否新增没有当前需求的抽象？

### Java

- [ ] 是否符合《阿里巴巴 Java 开发手册》核心规范？
- [ ] 命名是否清楚？
- [ ] 是否存在 magic number / magic string？
- [ ] 是否存在 God Class？
- [ ] 是否存在 trivial layer？
- [ ] 是否存在 dead code？
- [ ] 是否存在无价值注释/Javadoc？
- [ ] Exception 是否正确处理？
- [ ] 日志是否可能泄露敏感信息？
- [ ] Transaction 边界是否合理？
- [ ] 是否存在 N+1？

### REST

- [ ] URI 是否以资源名为主？
- [ ] HTTP Method 是否正确？
- [ ] HTTP Status Code 是否正确？
- [ ] 是否避免万能 200？
- [ ] Entity 是否没有直接暴露？
- [ ] 错误响应是否统一？
- [ ] 列表是否需要分页？

### Frontend

- [ ] 是否开启 TypeScript strict？
- [ ] 是否滥用 any？
- [ ] Server State 是否优先 TanStack Query？
- [ ] 是否创建不必要 Global Store？
- [ ] 是否复制大量相似组件？
- [ ] 是否引入多余 UI Framework？

### Quality

- [ ] 是否遵守 KISS？
- [ ] 是否遵守 YAGNI？
- [ ] 是否合理遵守 DRY，而非过度抽象？
- [ ] 是否新增无用 dependency？
- [ ] 是否存在 TODO placeholder？
- [ ] 是否存在 debug output？
- [ ] Diff 是否保持在当前 Phase 必要范围？

### Build

- [ ] `./mvnw clean verify` 是否成功？
- [ ] `npm run lint` 是否成功（前端存在后）？
- [ ] `npm run test` 是否成功（前端存在后）？
- [ ] `npm run build` 是否成功（前端存在后）？

任何适用项失败，Phase 不得标记为 `Complete`。

---

## 26.51 Code Review 问题

任何新增代码都应能回答：

1. 为什么这个类需要存在？
2. 为什么这个方法需要存在？
3. 它的职责是否清晰且有意义？
4. 能否用更直接的实现完成？
5. 是否为当前真实需求服务，而不是“未来可能会用”？
6. 是否可以被自动测试？
7. 错误发生时能否快速定位？
8. 是否可能泄露凭据？
9. 是否增加不必要的长期维护成本？
10. 这个 Diff 是否容易让其他 Java 工程师 Review？

如果无法合理回答，应重新审视设计。

---

# 27. Definition of Done

本次大重构只有满足以下条件才算完成 MVP：

- [ ] 所有 Java 新代码符合《阿里巴巴 Java 开发手册》核心规范。
- [ ] REST API URI、HTTP Method、HTTP Status Code 符合本文 RESTful 规范。
- [ ] 无无意义 Facade/Manager/Handler/Processor 等空壳分层。
- [ ] 无 Dead Code、无 placeholder、无无价值 TODO。
- [ ] 无废话式注释和自动生成式无价值 Javadoc。
- [ ] 无因本次修改产生的敏感日志泄露、明显 N+1、长期事务等问题。
- [ ] JDK 21。
- [ ] Maven >= 3.9。
- [ ] Maven Wrapper 3.9.15。
- [ ] `./mvnw clean verify` 成功。
- [ ] Spring Boot 4.1.x。
- [ ] PostgreSQL 平台库。
- [ ] JPA + Flyway。
- [ ] Driver Package 支持多个 JAR。
- [ ] 私有 Driver 不需要 Maven。
- [ ] Driver SHA-256。
- [ ] Server 不加载用户 Driver。
- [ ] Worker 独立 JVM。
- [ ] Test Connection 走 Worker。
- [ ] JUnit Platform 模块选择。
- [ ] 可以只跑 Statement。
- [ ] 可以只跑 Connection。
- [ ] 可以组合模块。
- [ ] Test Run 持久化。
- [ ] Test Case Result 持久化。
- [ ] SSE 实时进度。
- [ ] Cancel。
- [ ] Run History。
- [ ] Report 页面。
- [ ] Dashboard。
- [ ] React + TypeScript + Vite。
- [ ] Tailwind + shadcn/ui。
- [ ] ECharts。
- [ ] Docker Compose 一键启动。
- [ ] 密码不明文存储。
- [ ] 敏感日志脱敏。
- [ ] README 更新。
- [ ] 至少一个真实 PostgreSQL Driver E2E Worker test。

---

# 28. Codex 每个 Phase 的输出格式

每完成一个 Phase，请输出：

```text
Phase X Complete

1. Summary
   - 做了什么

2. Files Added
   - ...

3. Files Changed
   - ...

4. Architecture Decisions
   - ...

5. Compatibility
   - 哪些旧功能保留
   - 是否有行为变化

6. Tests
   - 执行了什么命令
   - 结果

7. Known Issues
   - ...

8. Next Phase
   - 下一步准备做什么
```

如果测试失败：

- 不要假装完成。
- 说明错误。
- 优先修复。
- 如果是环境原因，给出可复现命令和明确阻塞项。

---

# 29. Codex 第一轮现在应该做什么

**不要第一轮就创建 React 页面。**

第一轮只执行：

```text
Phase 0 — Audit / Baseline
```

具体：

1. Inspect repository。
2. 输出目录树。
3. 阅读所有核心 Java 测试。
4. 阅读 pom。
5. 阅读现有 runner/config/report。
6. 跑 baseline tests。
7. 生成：
   - `docs/current-architecture.md`
   - `docs/migration-notes.md`
8. 根据实际仓库情况，对本文件中的模块拆分和迁移风险提出必要修正。
9. 不做大规模代码改动。
10. 把 Phase 0 结果汇报给用户，等待确认后进入 Phase 1。

---

# 30. 推荐给 Codex 的启动 Prompt

可以直接对 Codex 说：

```text
请先完整阅读仓库根目录的 REFACTOR_PLAN.md。

这是本次 jdbc-test 重构的总体任务说明和架构约束。不要一次性重写整个项目，也不要直接开始做前端。

先严格执行 Phase 0：Audit / Baseline。

请检查当前仓库真实代码结构、pom、测试用例、runner、配置、report 逻辑和数据库适配逻辑，运行当前基线测试，并生成 docs/current-architecture.md 与 docs/migration-notes.md。

Phase 0 完成后，按 REFACTOR_PLAN.md 中约定的格式向我汇报：
- Summary
- Files Added
- Files Changed
- Architecture Decisions
- Compatibility
- Tests
- Known Issues
- Next Phase

在我确认之前，不要进入 Phase 1，也不要进行大规模重构。

特别注意：
1. 平台自身持久化使用 PostgreSQL + Spring Data JPA + Flyway。
2. Target Database 只使用对应 JDBC Driver + 原生 JDBC。
3. 用户上传 Driver JAR 不允许在 Spring Boot Server 主 JVM 中执行。
4. 私有 JDBC Driver 必须支持直接上传多个 JAR，不要求 Maven Central，也不要求 mvn install。
5. Maven 最低 3.9.0，Maven Wrapper 使用 3.9.15，JDK 21。
6. Web 最终技术栈为 React + TypeScript + Vite + Tailwind + shadcn/ui + ECharts。
7. 每个阶段都必须保持可编译、可测试，并优先保护当前已有 JDBC 测试资产。
8. 所有 Java 新代码必须遵守 REFACTOR_PLAN.md 第 26 章：阿里巴巴 Java 开发手册、RESTful API、KISS、YAGNI、DRY、异常/日志/JPA/事务/分页/前端等工程约束。
9. 禁止为了“架构完整”制造没有当前用途的 Facade、Manager、Handler、Processor、Factory、Provider、Registry 或万能抽象。
10. 禁止提交 Dead Code、空壳方法、placeholder、无意义 TODO、废话式注释/Javadoc。
```

---

# 31. 最终产品的一句话架构

```text
React Web
    ↓
Spring Boot Server
    ↓
PostgreSQL + Driver Storage + Run Scheduler
    ↓
Isolated Worker JVM
    ↓
Selected JDBC Driver
    ↓
Target Database
```

`jdbc-test` 的核心价值应始终是：

> **使用真实 JDBC Driver，以可重复、可选择、可比较、可报告的方式验证目标数据库的 JDBC 兼容性与回归情况。**

Web、JPA、Dashboard 都是围绕这个核心能力服务，而不是取代它。
