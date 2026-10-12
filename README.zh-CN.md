# JDBC 驱动兼容性测试框架

[English](README.md) | 简体中文

面向数据库厂商和驱动维护者的 JDBC 4.3 兼容性与回归测试框架。在明确授权的测试命名空间中连接你的数据库、运行版本化测试集，并生成脱敏的 JSON、Markdown 和 HTML 报告。

测试会执行 DDL/DML。使用专用数据库/schema，运行前阅读[执行安全](docs/execution-safety.zh-CN.md)。本项目不是官方 JDBC 认证工具，也不用于性能压测、迁移、业务 SQL 或厂商专有 API 测试。

## 选择部署方式

| 方式 | 配置 | 启动 |
|---|---|---|
| [Docker + 自带 PG](docs/deployment.zh-CN.md#bundled-pg) | `.env.example` → `.env`，填写密码和密钥 | `docker compose up -d --build --wait` |
| [Docker + 已有 PG](docs/deployment.zh-CN.md#external-pg) | `.env.external.example` → `.env`，填写 PG URL、账号、密码和密钥 | `docker compose -f docker-compose.external.yml up -d --build --wait` |
| [非 Docker + 已有 PG](docs/deployment.zh-CN.md#native) | Server 部署包的 `config/application.properties` | `sh start.sh` 或 PowerShell `./start.ps1` |

三种方式提供相同的 Web UI；管理数据库与页面中配置的目标数据库相互独立。详细准备、启动、备份步骤见表中的链接。

## 快速开始：Docker + 自带 PG

需要 Docker Engine/Desktop 和 Compose v2，主机无需安装 Java、Maven、Node 或 PostgreSQL。

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

编辑 `.env`：设置强密码 `POSTGRES_PASSWORD`，将生成的 32 字节 Base64 密钥填入 `JDBC_TEST_MASTER_KEY`。保存连接密码后必须保留同一密钥，不要提交 `.env`。

```sh
docker compose up -d --build --wait
docker compose ps
```

两项服务健康后打开 **http://localhost:8080**。上传并验证 JDBC 驱动，保存并探测连接，配置明确授权的数据库/schema，再核对选择并同意运行。首次构建需访问 npm、Maven 和镜像仓库；应用容器必须能够访问目标数据库。

应用**没有用户认证**，仅适合 localhost 或可信内网。Web 默认绑定本机回环地址，平台 PostgreSQL 不向主机发布端口。

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

需要 JDK 21、Maven >=3.9（Wrapper 3.9.15）、用于 CLI 工具的 Python >=3.9，以及使用者提供的目标数据库与驱动。Web 开发还需要 Node >=22.12.0 和 npm、平台 PostgreSQL、持久保存的 master key 和已构建的 Worker。启动步骤见[本地启动](docs/development.zh-CN.md#本地启动)。

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
python3 scripts/build_distribution.py
```

生成 `dist/jdbc-test-server-<版本>/`、`.tar.gz`、`.zip` 和校验文件，包含 Server、Worker、前端和启动脚本。构建机器需要 JDK 21、Python、Node/npm，运行机器仅需 Java 21 和已有 PG；步骤见[非 Docker 部署](docs/deployment.zh-CN.md#native)。

## 许可证

Apache License 2.0。
