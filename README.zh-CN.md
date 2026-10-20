# jdbc-test

[English](README.md) | 简体中文

面向上传 JDBC 驱动的标准 JDBC 测试平台。平台管理数据库仍为 **PostgreSQL**，与被测数据库独立。

**上传驱动 → 配置连接 → 选择标准 JDBC 测试 → 执行 → 查看报告。**
用例、断言、方言及准备资源统一在源码维护，修改后重新构建部署。普通用户无需编写 JSON、制作 ZIP 或管理适配包。
保留私有驱动、依赖 JAR、驱动验证及内部驱动版本回归；已撤回 SQL 测试包和用户上传适配包。

结果区分 JDBC 契约、合法不支持、测试方案要求与执行准备／平台问题。历史 SQL 报告只读，不计入 JDBC 契约结论或基线。
不宣称任意 JDBC 数据库均已兼容，不提供完整 JDBC 认证或完整产品兼容性结论。
测试会执行 DDL/DML，仅使用专用目标库／schema；先阅读[执行安全](docs/execution-safety.zh-CN.md)。

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

1. 在“驱动”上传主驱动及必要依赖 JAR，点击验证。登记版本、Driver API 自报主／次版本和 JAR 声明分别显示；验证成功仅代表加载及元数据读取成功。
2. 创建连接，选择数据库类型，自动使用内置适配。自研数据库选择“自研／兼容数据库”，再显式选择内置兼容方言；这不证明产品兼容。配置驱动及专用授权命名空间，测试连接。
3. 在“新建测试”选择连接、驱动和内置标准 JDBC 用例，确认授权范围后执行。
4. 查看进度、逐项结果、准备问题、清理证据、冻结的驱动／适配／选择快照和报告。

驱动验证、连接成功与测试通过分别判定。无法适配的 SQL、权限或命名空间条件按准备问题显示，不能视为通过或自动归为驱动缺陷。

## 文档

| 指南 | English | 简体中文 |
|---|---|---|
| 部署、配置与备份 | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |
| 架构、本地开发、API 与 Worker 协议 | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| 授权范围、清理与结果统计 | [Execution safety](docs/execution-safety.md) | [执行安全](docs/execution-safety.zh-CN.md) |
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

适配器状态表示接入成熟度，不代表认证或任意 JDBC 数据库均已兼容。自研数据库可显式复用内置方言；需要修改适配逻辑时由开发者改源码、重新构建部署。

历史[2026-10-20 验收摘要](docs/acceptance-2026-10-20.zh-CN.md)记录独立临时 PostgreSQL **16.15 / pgjdbc 42.7.11** 上的真实 Web/Server/Worker 全链路、MySQL **8.4.11 / Connector/J 8.3.0** 的四项 SQL 用例，以及 PostgreSQL 驱动 **42.7.2 → 42.7.11** 的恰好六项 JDBC 回归。自定义 PostgreSQL 包验证的是接入机制，不是真实自研数据库的兼容性。本轮未验证其他产品/版本/模式或完整 JDBC 矩阵。

此前 KingbaseES V009R002C016 oracle 模式 / 驱动 V008R006B0001 的证据保留在[验证记录](adapters/kingbase/evidence/platform-v009r002c016-oracle-v008r006b0001.json)。Oracle 19.3 / ojdbc11 21.13 仍有准备缺口及未归因的 LOB 字典差异，见[历史后续验证与限制](docs/development.zh-CN.md#oracle-full-后续验证2026-10-19)。两者均不能证明完整产品兼容性。

## 开发环境

源码 Web 开发需 JDK 21、Maven Wrapper 3.9.15、Node >=22.12.0/npm 和专用平台 PG；配置、Key 与 Worker 准备见[本地源码启动](docs/development.zh-CN.md#local-source-startup)。Python >=3.9、已授权目标库和驱动是 CLI 目标测试的额外要求，不是打开开发网站的前提。

## CLI 入门与内部驱动回归

在源码根目录运行，配置与驱动只保存在本地：

```sh
python3 -B scripts/runner.py adapters
python3 -B scripts/runner.py init --adapter postgresql \
  --url jdbc:postgresql://localhost:5432/test_db --username jdbc_tester \
  --namespace authorized_test_schema --password-env DB_PASSWORD --consent
# 使用现有秘密管理方式设置 DB_PASSWORD；可用 --driver-dir 指定驱动和依赖目录。
python3 -B scripts/runner.py run configs/config.local.json
```

`run`：0 为本次评估通过，1 为测试／评估失败，2 为输入无效或评估启动前失败；已生成评估中的准备／执行问题仍使该次评估失败。
内部 A/B 回归使用 `baseline create` 和 `compare --scope internal`，详见[内部回归](docs/internal-regression.zh-CN.md)。
比较的 0 仅表示无新增回归，既有失败仍然显示；1 为新增回归，2 为不可比较／证据不足。两种命令的 0 含义不同。
历史 SQL 报告不能用作 JDBC 基线。

兼容方言、本地驱动、多 JAR 和连接属性见[示例](examples/README.zh-CN.md)。
`init-custom`、`pack-adapter`、`sql-validate`、`sql-run` 及外部适配包路径入口已撤回。
不提交配置、密钥、凭据或私有驱动；`report.json` 为判定依据，对外分享前审查诊断信息。

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
