# JDBC 驱动兼容性测试框架

[English](README.md) | 简体中文

面向数据库厂商和驱动维护者的 JDBC 4.3 兼容性与回归测试框架。在明确授权的测试命名空间中连接你的数据库、运行版本化测试集，并生成脱敏的 JSON、Markdown 和 HTML 报告。

测试会执行 DDL/DML。使用专用数据库/schema，运行前阅读[执行安全](docs/execution-safety.zh-CN.md)。本项目不是官方 JDBC 认证工具，也不用于性能压测、迁移、业务 SQL 或厂商专有 API 测试。

## 快速开始：复制、编辑、启动

准备 **JDK21、Node >=22.12/npm** 和预先创建的专用**平台 PostgreSQL** 数据库/账号。在**源码根目录**：

1. **仅在 `.env` 不存在时，将 `.env.example` 复制为 `.env`**。已有配置和 Key 保留，不覆盖。
2. 用本地编辑器打开**源码根目录实际 `.env`**，填写 **`SPRING_DATASOURCE_URL`**、**`SPRING_DATASOURCE_USERNAME`**、**`SPRING_DATASOURCE_PASSWORD`**：平台完整 JDBC 地址、账号和现有密码。URL 示例 `jdbc:postgresql://localhost:5432/jdbc_test`，替换实际主机/端口/库名，必须含数据库名，不嵌入账号密码。全新平台 `JDBC_TEST_MASTER_KEY` 留空；已有平台保留原 Key。
3. 执行 **`./jdbc-test dev`**，只有全新空平台才确认 `NEW`，随后打开打印地址。工具安全保存 Key，构建 Java/Worker、按需安装依赖并启动源码后端＋Vite。Ctrl+C 停止本次进程；以后复用同一 `.env`/Key/数据，执行同一命令。

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

## 文档

| 指南 | English | 简体中文 |
|---|---|---|
| 部署、配置与备份 | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| 架构、本地开发、API 与 Worker 协议 | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| 授权范围、清理与结果统计 | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |

## 已有适配包

- PostgreSQL、MySQL、Oracle：官方适配包，已有真实目标验证证据。
- GaussDB、SQL Server：已实现适配包，等待真实实例验证。
- 其他数据库：通过本地声明式适配包接入，无需修改 Java 核心。

具体版本和验收边界见[已验证组合](docs/execution-safety.zh-CN.md#已验证组合)。

## 开发环境

源码 Web 开发需 JDK 21、Maven Wrapper 3.9.15、Node >=22.12.0/npm 和专用平台 PG；配置、Key 与 Worker 准备见[本地源码启动](docs/development.zh-CN.md#local-source-startup)。Python >=3.9、已授权目标库和驱动是 CLI 目标测试的额外要求，不是打开开发网站的前提。

## CLI 快速开始

```sh
python3 scripts/runner.py init \
  --adapter postgresql \
  --url 'jdbc:postgresql://localhost:5432/test' \
  --username tester \
  --password-env DB_PASSWORD \
  --consent

export DB_PASSWORD='your-password'
python3 scripts/runner.py run configs/config.local.json
```

执行 `python3 scripts/runner.py adapters` 查看适配包。生成配置和驱动 JAR 仅保存在本地；Oracle 已有 schema 模式还需通过 `--namespace` 明确授权。密码从环境变量读取。报告位于 `report/`，分享本地 `diagnostics.log` 前需人工检查。

与审核后的基线比较：

```sh
python3 scripts/runner.py compare \
  --baseline baseline/report.json \
  --current report/postgresql_YYYY-MM-DD_HH-MM-SS/report.json
```

`report.json` 是权威格式，Markdown/HTML 由它派生。新增兼容性失败、环境/预检错误、适配包不完整或清理失败返回非零退出码；明确的能力跳过和审核后的已知差异不阻断。

## 示例与自定义适配包

[使用示例](examples/README.zh-CN.md)涵盖内置/私有数据库、Hikari/DriverManager、JDBC 属性、环境变量敏感值和多 JAR 驱动。`init-custom` 为本地适配包复用 Oracle/MySQL/PostgreSQL 方言资产。本地适配包包含 `adapter.json` 和 SQL 资产，通过 `runner.py init --adapter /path/to/adapter` 使用；其结果保持试验性，不能直接作为正式基线。

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

可选 API 由适配能力声明控制：`false` 产生带来源的 `known_unsupported` 跳过，`true` 启用断言。跳过不证明数据库缺少支持。执行 `python3 scripts/runner.py coverage --profile full` 查看稳定 ID，通过 `test_filter.include_tests` / `exclude_tests` 缩小范围。共享连接压测（`concurrency.enabled=true`）会被拒绝。

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
