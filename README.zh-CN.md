# JDBC 驱动兼容性测试框架

[English](README.md) | 简体中文

面向数据库厂商和驱动维护者的 JDBC 4.3 兼容性与回归测试框架。在明确授权的测试命名空间中连接你的数据库、运行版本化测试集，并生成脱敏的 JSON、Markdown 和 HTML 报告。

平台管理数据仍保存在 **PostgreSQL**，与被测目标库分开。已支持 Web 自定义数据库适配包导入、CLI 内部 JDBC 驱动 A/B 回归，以及 Web/CLI 声明式 SQL 功能测试包。导入包保持 local 来源；导入或连接成功不代表兼容性通过。

JDBC 报告判定已实现的 API 契约，分别保留能力声明、实测行为和方案要求。SQL 报告判定测试包定义的功能，逐步展示预期值与实际值，不混入 JDBC 兼容率或 JDBC 基线。

测试会执行 DDL/DML。使用专用数据库/schema，运行前阅读[执行安全](docs/execution-safety.zh-CN.md)。本项目不是官方 JDBC 认证工具，也不用于性能压测、迁移、业务 SQL 或厂商专有 API 测试。

## 快速开始：复制、编辑、启动

准备 **JDK21、Node >=22.12/npm** 和预先创建的专用**平台 PostgreSQL** 数据库/账号。在**源码根目录**：

1. **仅在 `.env` 不存在时，将 `.env.example` 复制为 `.env`**。已有配置和 Key 保留，不覆盖。
2. 用本地编辑器打开**源码根目录实际 `.env`**，填写 **`SPRING_DATASOURCE_URL`**、**`SPRING_DATASOURCE_USERNAME`**、**`SPRING_DATASOURCE_PASSWORD`**：平台完整 JDBC 地址、账号和现有密码。URL 示例 `jdbc:postgresql://localhost:5432/jdbc_test`，替换实际主机/端口/库名，必须含数据库名，不嵌入账号密码。全新平台 `JDBC_TEST_MASTER_KEY` 留空；已有平台保留原 Key。
3. 执行 **`./jdbc-test dev`**，随后打开打印地址。工具检查全新空平台后自动保存 Key，构建 Java/Worker、按需安装依赖并启动源码后端＋Vite。Ctrl+C 停止本次进程；以后复用同一 `.env`/Key/数据，执行同一命令。

**终端命令 — Linux/macOS，源码根目录（复制后先编辑，再 dev）：**

```sh
umask 077
cp -n .env.example .env
# 用本地编辑器打开实际 .env，填写三个 SPRING_DATASOURCE 字段。
./jdbc-test dev
```

**PowerShell 终端 — 源码根目录：**

```powershell
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
# 编辑实际 .env：平台 URL、用户名、现有密码。
./jdbc-test.ps1 dev
```

缺 `.env` 时在 **Java/工具链/构建之前**提示复制位置，不再先运行一次生成模板。不能 source `.env` 或把秘密放入命令参数。准备条件、密码字面值语法、日常启动和可选 IDEA Debug 见[开发指南](docs/development.zh-CN.md#local-source-startup)。

只运行网站不开发源码，请看[部署指南](docs/deployment.zh-CN.md)：`./jdbc-test docker --bundled-pg`、`./jdbc-test docker --external-pg`，或在**解压后的非 Docker 部署包内** `./jdbc-test start`。用 `./jdbc-test build` 构建部署包。Docker 运行机器只需 Docker/Compose，部署包运行机器只需 Java21；构建机器额外需要 Python >=3.9 归档。每套环境一份私有配置，普通用户只使用统一入口。

## 第一次 Web 测试

启动后，准备专用目标数据库/schema 和可信 JDBC 驱动：

1. 在**驱动管理**上传驱动及必要依赖 JAR，然后校验。
2. 在**数据库适配器**导入 `pack-adapter` 导出的 ZIP，或使用内置适配器。见[模板与导出说明](docs/web-adapters.zh-CN.md)。
3. 创建连接，选择驱动、适配包和明确授权的目标范围，再测试连接。
4. 在 **SQL 测试包**导入 [postgresql-basics.json](examples/sql/postgresql-basics.json) 或 [mysql-basics.json](examples/sql/mysql-basics.json)，选择版本、连接及用例，创建任务。
5. 查看任务进度和报告中的逐步预期/实际值、清理结果及冻结的驱动、适配包和 SQL 包指纹。JDBC 任务仍使用独立入口。

示例必须匹配目标产品。上传校验、连接成功、测试通过是不同状态。详见[SQL 测试包流程与限制](docs/sql-packages.zh-CN.md)。

## 文档

| 指南 | English | 简体中文 |
|---|---|---|
| 部署、配置与备份 | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| 架构、本地开发、API 与 Worker 协议 | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| 授权范围、清理与结果统计 | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |
| 自定义适配包模板、导入与快照 | [Web adapters](docs/web-adapters.md) | [Web 适配包](docs/web-adapters.zh-CN.md) |
| SQL 测试包格式、示例及 CLI/Web 操作 | [SQL packages](docs/sql-packages.md) | [SQL 测试包](docs/sql-packages.zh-CN.md) |
| 私有驱动 A/B 基线与 CI 退出码 | [Internal regression](docs/internal-regression.md) | [内部回归](docs/internal-regression.zh-CN.md) |
| 实测范围、修复与缺口（2026-10-20） | [Acceptance summary](docs/acceptance-2026-10-20.md) | [验收摘要](docs/acceptance-2026-10-20.zh-CN.md) |

## 数据库接入列表

| 数据库 | 适配器 ID | 状态 |
|---|---|---|
| PostgreSQL | `postgresql` | 正式 |
| MySQL | `mysql` | 正式 |
| Oracle | `oracle` | 正式 |
| GaussDB | `gaussdb` | 候选 |
| SQL Server | `sqlserver` | 候选 |
| KingbaseES（金仓） | `kingbase` | 正式 |

适配器状态表示接入成熟度，不代表认证或任意 JDBC 数据库均已兼容。本地包可复用已支持方言，无需重建平台；不支持的方言会被拒绝。

[2026-10-20 验收摘要](docs/acceptance-2026-10-20.zh-CN.md)记录独立临时 PostgreSQL **16.15 / pgjdbc 42.7.11** 上的真实 Web/Server/Worker 全链路、MySQL **8.4.11 / Connector/J 8.3.0** 的四项 SQL 用例，以及 PostgreSQL 驱动 **42.7.2 → 42.7.11** 的恰好六项 JDBC 回归。自定义 PostgreSQL 包验证的是接入机制，不是真实自研数据库的兼容性。本轮未验证其他产品/版本/模式或完整 JDBC 矩阵。

此前 KingbaseES V009R002C016 oracle 模式 / 驱动 V008R006B0001 的证据保留在[验证记录](adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)。Oracle 19.3 / ojdbc11 21.13 仍有准备缺口及未归因的 LOB 字典差异，见[历史后续验证与限制](docs/development.zh-CN.md#oracle-full-后续验证2026-10-19)。两者均不能证明完整产品兼容性。

## 开发环境

源码 Web 开发需 JDK 21、Maven Wrapper 3.9.15、Node >=22.12.0/npm 和专用平台 PG；配置、Key 与 Worker 准备见[本地源码启动](docs/development.zh-CN.md#local-source-startup)。Python >=3.9、已授权目标库和驱动是 CLI 目标测试的额外要求，不是打开开发网站的前提。

## CLI 快速开始

在源码根目录使用 JDK 21 构建一次 Worker，然后无需数据库即可校验测试包：

```sh
./mvnw -pl jdbc-test-worker -am package -DskipTests
python3 -B scripts/runner.py sql-validate --package examples/sql/postgresql-basics.json
```

参照[完整 SQL 配置示例](docs/sql-packages.zh-CN.md)创建私有连接配置，通过秘密管理方式设置密码环境变量，指定已授权的已有命名空间及本地驱动，然后运行：

```sh
python3 -B scripts/runner.py sql-run --config /private/sql-config.json \
  --package examples/sql/postgresql-basics.json --case basic.query \
  --output report/sql-first-run
```

每次使用新的输出目录。SQL 执行退出码 **0** 仅表示选中项全部通过且清理确认成功，**1** 为已完成但存在失败，**2** 为输入无效或执行证据不完整。`sql-validate` 的 0 仅表示定义有效。

JDBC 驱动 A/B 使用[内部回归入门](docs/internal-regression.zh-CN.md)：准备连接配置、明确的数据库身份/构建/模式及持久保存的私有比较密钥，运行 A，用 `baseline create` 选报告为基线，仅更换驱动后运行 B，再执行 `compare --scope internal`。比较退出码 **0** 为无新增回归（旧失败仍可见），**1** 为新增回归，**2** 为不可比较或证据不完整；这里的 0 **不代表全部通过**。默认正式比较仍要求正式资格；旧报告缺证据需重跑。SQL 报告不能作为 JDBC 基线。

执行 `python3 scripts/runner.py adapters` 查看适配包。配置、密钥和驱动 JAR 留在本地；`report.json` 是权威结果，分享诊断日志前需人工检查。见[契约判定依据](docs/jdbc-capability-contracts.md)。

## 示例与自定义适配包

[使用示例](examples/README.zh-CN.md)涵盖内置/私有数据库、Hikari/DriverManager、JDBC 属性、环境变量敏感值和多 JAR 驱动。`init-custom` 为本地适配包复用 Oracle/MySQL/PostgreSQL 方言资产。本地适配包包含 `adapter.json` 和 SQL 资产，通过 `runner.py init --adapter /path/to/adapter` 使用；其结果保留原来源，可明确选为内部驱动升级回归基线，不会自动获得正式资格。

[Web 导入自定义适配包](docs/web-adapters.zh-CN.md)：用 `pack-adapter` 导出现有 CLI 包，在 Web 导入后创建连接。包保存在平台管理数据库，任务独立冻结资源与本地来源。

适配包声明产品与驱动身份/版本范围、能力、资产、命名空间生命周期、最小权限、已验证组合和已知差异。使用本地驱动时，将生成配置的 `db.driver` 设置为：

```json
{"kind": "local", "path": "/absolute/path/to/driver.jar"}
```

不要提交私有或第三方 JDBC 二进制文件。

## 测试范围与选择

CLI 默认 `test_profile=full` 选择 JDBC 4.3 基线：连接/事务、Statement、批处理、生成键、ResultSet、类型、元数据、LOB、RowSet、SQLXML、保存点和 CallableStatement。使用 `core` 选择严格的跨数据库场景：

```json
{"test_profile": "core", "test_filter": {"timeout_ms": 60000}}
```

新建任务默认选中全部适用用例，可按模块或单用例取消。标准能力不再由静态 false 声明禁用；缺少 Provider/SQL 准备属于执行前提不足。只有具体 API 契约允许且实测不支持时才报告允许不支持。CORE 保留显式维护的旧 153 项，FULL 当前覆盖 270 项已实现的标准用例，尚无真实厂商扩展，并非所有 JDBC 接口。执行 `python3 scripts/runner.py coverage --profile full` 查看稳定 ID，通过 `test_filter.include_tests` / `exclude_tests` 缩小范围。共享连接压测（`concurrency.enabled=true`）会被拒绝。

## 构建 CLI 发布包

```sh
python3 scripts/build_release.py --output dist
```

仅包含源码的 ZIP 不含第三方驱动 JAR，并附带 SHA-256 文件。生产 Compose 部署使用仓库源码。

非 Docker 的 Web/Server 部署包单独构建：

```sh
./jdbc-test build
```

生成 `dist/jdbc-test-server-<版本>/`、`.tar.gz`、`.zip` 和校验文件，包含 Server、Worker、前端和启动脚本。构建机器需要 JDK 21、Python、Node/npm，运行机器仅需 Java 21 和已有 PG；步骤见[非 Docker 部署](docs/deployment.zh-CN.md#native)。

## 许可证

Apache License 2.0。
