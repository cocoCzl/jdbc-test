# JDBC Driver Compatibility Test Framework

运行测试前必须配置明确授权的 database/schema。对象归属、清理边界与执行统计规则见 [Execution safety](docs/execution-safety.md)。

[English](README.en.md) | 中文

这是一个面向数据库厂商和 JDBC 驱动维护者的 JDBC 4.3 兼容性与回归测试框架。它连接使用者提供的数据库实例，在专用测试命名空间中运行版本化测试集，并生成脱敏、可比较的 JSON/Markdown/HTML 报告。

它不是官方 JDBC 认证工具，也不用于业务 SQL、性能压测、数据库迁移或厂商专有 API 认证。

## Quick Start — Self-hosted Docker

需要 Docker Engine/Desktop 与 Compose v2；生产部署无需在主机安装 Java、Maven、
Node 或 PostgreSQL。获取仓库后：

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

编辑 `.env`：设置独立强密码 `POSTGRES_PASSWORD`，将生成的 32-byte Base64 key
填入 `JDBC_TEST_MASTER_KEY`。示例故意不提供可用 secret；不要提交 `.env`。
保存过 Connection password 后必须保留同一个 master key，否则无法解密旧密码。

```bash
docker compose up -d --build
docker compose ps
```

两项服务健康后打开 **http://localhost:8080**：React、API、SSE 同源提供，上传 JDBC
Driver 后创建测试连接并运行。首次构建需要访问 npm、Maven 和镜像仓库。默认只将
Web 端口绑定到 host loopback；Platform PostgreSQL 不发布 host 端口。

当前无用户认证，只适合 localhost/可信内部网络，不要裸露公网。连接目标数据库
需容器网络可达；测试会执行 DDL/DML，请使用专用测试库/命名空间。
部署、网络、备份和安全边界见 [Deployment](docs/deployment.md)。
Phase 9 Docker 验收结果与验证命令见 [Phase 9 verification](docs/phase9-verification.md)。

## 已有适配包

- 官方且已有真实验证证据：PostgreSQL、MySQL、Oracle
- 已实现、等待真实实例验证：GaussDB、SQL Server
- 任意新数据库可通过本地声明式适配包接入，无需修改 Java 核心代码

## 环境

- JDK 21
- Maven >=3.9（Wrapper 3.9.15）
- Python 3.9+（核心命令只使用标准库）
- 使用者提供的目标数据库实例和固定版本 JDBC 驱动

## Frontend Development

Phase 8 提供独立 `jdbc-test-web/`（不是 Maven module）：React + TypeScript +
Vite，开发地址 `http://localhost:5173`，`/api` 代理至 Server `localhost:8080`。
需要 Node >=22.12.0（推荐受支持 LTS）和 npm。

后端先准备 Platform PostgreSQL、稳定的 `JDBC_TEST_MASTER_KEY`、data directory 和
Worker executable；并非零配置启动。详细环境与命令见
[Web UI](docs/web-ui.md) 和 [Platform Database](docs/platform-database.md)。

```bash
./mvnw -DskipTests install
# 配置文档要求的 datasource / master key / Worker 环境变量之后：
./mvnw -pl jdbc-test-server spring-boot:run
```

另开终端：

```bash
cd jdbc-test-web
npm install
npm run dev
```

浏览器可完成 Driver 上传/验证、Connection 创建/探测、Run selection、实时 SSE 与
报告。前端验证：`npm run lint`、`npm run test`、`npm run build`；浏览器测试区分
mocked `npm run test:e2e` 和真实 `npm run test:e2e:real`。
开发模式仍保持 Vite/Server 分离；生产 Compose 从源码构建静态资源并由 Server
同源托管，不启动 Vite。普通 Maven lifecycle 不执行 npm，也不需要 Node。

## CLI 三步开始

```bash
python3 scripts/runner.py init \
  --adapter postgresql \
  --url 'jdbc:postgresql://localhost:5432/test' \
  --username tester \
  --password-env DB_PASSWORD \
  --consent

export DB_PASSWORD='your-password'
python3 scripts/runner.py run configs/config.local.json
```

建立基线后比较回归：

```bash
python3 scripts/runner.py compare \
  --baseline baseline/report.json \
  --current report/postgresql_YYYY-MM-DD_HH-MM-SS/report.json
```

运行前可使用 `python3 scripts/runner.py adapters` 查看内置适配包。生成的本地配置不要保存明文密码，也不要提交到 Git；Oracle 必须使用专用测试账号/schema。运行结果位于 `report.json`、`report.md` 和 `report.html`，`diagnostics.log` 仅用于本地排障。

## 使用示例

完整、可复制的示例见 [examples/README.md](examples/README.md)：

- PostgreSQL、MySQL、Oracle 内置适配包
- Oracle、MySQL、PostgreSQL 方言兼容的自研数据库
- Hikari 连接池与 DriverManager 直连
- 普通 JDBC Properties、环境变量属性和 URL 参数
- 单个驱动 JAR 与包含依赖的多 JAR 目录

## 本地适配包

本地适配包由 `adapter.json` 和数据库专用 SQL 资产组成，可通过 `runner.py init --adapter /path/to/adapter` 使用，无需修改 Java 核心代码。可参考 `examples/adapters/postgresql-local/adapter.json`。

适配包必须声明产品/驱动身份与版本范围、能力、测试资产、命名空间生命周期、最小权限、已验证组合和已知差异。本地适配包生成的结果始终标记为本地/试验性，不能直接作为正式基线。

如需使用本地 JDBC 驱动 JAR，请将生成配置中的 `db.driver` 改为：

```json
{"kind": "local", "path": "/absolute/path/to/driver.jar"}
```

驱动 JAR 不应复制或提交到本项目仓库。

### 自研数据库与私有驱动

如果数据库兼容 Oracle、MySQL 或 PostgreSQL 方言，可使用 `init-custom` 生成仅保存在本机的适配包并复用对应 SQL 资产。自研驱动默认使用 `DriverManager`，也可切换到 Hikari；任意厂商属性和 URL 参数均可配置。完整命令见 [自研数据库示例](examples/README.md#自研数据库与私有驱动)。

## 安全边界

- 密码只从环境变量读取，本地配置默认被 Git 忽略。
- 未显式设置 `destructive_consent=true` 时不执行数据库变更。
- PostgreSQL/MySQL/GaussDB 使用随机专用命名空间；Oracle 需使用专用测试账号/schema。
- 可共享报告默认不包含凭据、主机、端口、库名、用户名、完整 SQL 或堆栈。
- 完整排障信息只保存在本地 `diagnostics.log`，分享前请人工检查。

## 报告与 CI

`report.json` 是权威格式，Markdown/HTML 由它派生。新增兼容性失败、环境错误、预检失败、适配包不完整或清理失败返回非零退出码；明确的能力跳过和审核后的已知差异不阻断。

## 测试范围与选择

默认 `test_profile=full` 运行全部 JDBC 4.3 基线：连接和事务、Statement/PreparedStatement、批处理失败语义、生成键、ResultSet 生命周期与 NULL 语义、类型映射、元数据、LOB、RowSet、SQLXML、保存点和 CallableStatement。使用 `core` 只运行无需能力声明的严格跨数据库场景：

```json
{"test_profile": "core", "test_filter": {"timeout_ms": 60000}}
```

`full` 中的 Array、SQLXML、NClob、参数元数据和请求边界等可选 API 由适配包能力声明控制：`false` 显示为 `known_unsupported`，`true` 后失败即为兼容性失败。使用 `python3 scripts/runner.py coverage --profile full` 查看稳定场景 ID；`test_filter.include_tests` 与 `exclude_tests` 可进一步筛选。报告会按 JDBC 域和能力汇总结果。

本项目测试标准 JDBC 驱动契约，不是厂商专有 SQL 认证、性能压测或共享连接并发压测。`concurrency.enabled=true` 会被明确拒绝，避免产生未实际执行的覆盖结论。

## 构建发布包

```bash
python3 scripts/build_release.py --output dist
```

生成的 ZIP 不包含第三方 JDBC 驱动，并附带 SHA-256 文件。

## 许可证

Apache License 2.0。
