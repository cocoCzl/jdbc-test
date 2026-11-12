# JDBC Test

简体中文 / [English](README.en.md)

在真实数据库上验证 JDBC 驱动接口行为的测试平台。

## 核心功能

- 管理外部 JDBC 驱动及依赖 JAR，在隔离 Worker 中检查加载。
- 配置目标连接、独立凭据与授权范围，探测连接和元数据。
- 选择标准 JDBC 用例（CORE / FULL / CUSTOM），由独立 Worker JVM 执行。
- 查看执行进度、逐项结果、清理证据并下载 JSON 报告。
- **CLI 专用：** 驱动 A/B 升级回归、选择基线及比较报告，见[回归指南](docs/internal-regression.zh-CN.md)。

## 当前内置数据库适配

| 数据库 | 平台适配状态 | 已记录组合或验证范围 | 配置与限制 |
|---|---|---|---|
| PostgreSQL | official | 15.2 / 驱动 42.7.2 | [连接配置](docs/usage.zh-CN.md#database-configuration) |
| MySQL | official | 8.0.30 / 驱动 8.3.0 | [连接配置](docs/usage.zh-CN.md#database-configuration) |
| Oracle | official | 19.3.0.0.0 / 驱动 21.13.0.0.0 | [权限与 LOB 限制](docs/database-validation.zh-CN.md#known-limitations) |
| KingbaseES | official | 服务端 V009R002C016；oracle 模式；JDBC 产品 12.1；驱动 V008R006B0001 | [组合边界](docs/database-validation.zh-CN.md#recorded-combinations) |
| GaussDB | candidate | `validated_combinations` 为空 | [复用边界](docs/database-validation.zh-CN.md#recorded-combinations) |
| 达梦 / DM | official | 8.1.3.162 / 驱动 8.1.3.62；COMPATIBLE_MODE=0 | [接纳范围与历史记录](docs/database-validation.zh-CN.md#dm-platform-acceptance) |

活动索引为 [adapters/catalog.json](adapters/catalog.json)，状态与范围来自各 `adapter.json`、
`qualification` 和已有 evidence，见[证据入口](docs/database-validation.zh-CN.md#recorded-combinations)。
`official` 是项目内的平台适配状态，不是厂商认证，也不表示全部 JDBC 用例通过。
`candidate` 为尚未正式接纳的适配；达梦仅登记下述验证记录中的精确组合，旧驱动历史另列。
`supported_versions` 是准入范围，不代表区间内全部版本实测；capability 布尔值是声明，不是通过证明。
GaussDB 复用 PostgreSQL 基础设施不代表所有 GaussDB/openGauss 产品完整验证。
SQL Server 已停止新建连接、探测和执行，仅保留[历史读取](docs/database-validation.zh-CN.md#known-limitations)。

## 快速开始

<a id="启动网站"></a>
<a id="start-the-website"></a>
### 运行网站

以下是一条独立的 **Docker 自带平台 PG** 路径。需要 Git、Docker Engine/Desktop、Compose v2、
Shell/PowerShell、空闲 8080 端口及镜像/依赖下载网络；宿主机不需要 Java/Node/Maven/Python。
适用于全新安装：所选项目没有既有配置、卷或应用数据；已有安装应保留原配置并按[部署指南](docs/deployment.zh-CN.md#operations)恢复。

```sh
git clone https://github.com/cocoCzl/jdbc-test.git
cd jdbc-test
./jdbc-test docker --bundled-pg
```

配置保存在源码根目录 `.env`；此模式由启动器生成 PG 密码及独立 Master Key，不要先复制外部 PG 模板。
就绪后打开命令打印的地址，默认 `http://127.0.0.1:8080`。
停止使用 `./jdbc-test docker --bundled-pg --stop`，保留配置和数据。

其他运行方式分别见 [Docker 外部 PG](docs/deployment.zh-CN.md#docker-external)和
[解压后的 Java 21 部署包](docs/deployment.zh-CN.md#native)；部署包在自己的根目录执行 `./jdbc-test start`。

### 源码开发与构建

源码开发需要 **JDK 21、Node >=22.12/npm**、Maven Wrapper 和具有迁移权限的专用平台 PostgreSQL。
在源码根目录仅在配置不存在时复制，编辑 `.env` 的 `SPRING_DATASOURCE_URL`、
`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 后启动：

```sh
umask 077
cp -n .env.example .env
# 编辑实际 .env 中的平台数据库三项，再执行：
./jdbc-test dev
```

默认访问 `http://127.0.0.1:5173`，以命令打印的就绪地址为准。Ctrl+C 停止本次前后端。
后续启动保留原 `.env`、Master Key 和数据，详见[本地源码启动](docs/development.zh-CN.md#local-source-startup)。
构建机器另需 **Python >=3.9**，在源码根目录执行 `./jdbc-test build`；
[部署包](docs/deployment.zh-CN.md#build-bundle)运行机器只需 Java 21、Shell/PowerShell 和平台 PG。
Windows 使用对应的 `./jdbc-test.ps1 dev`、`build`、`docker --bundled-pg`、
`docker --external-pg` 或部署包中的 `start`，按所选模式操作，见[Windows 命令](docs/deployment.zh-CN.md#windows)。

<a id="first-test"></a>
## 第一次测试

1. **上传驱动：** 上传有权使用的驱动及全部依赖 JAR，验证加载。
2. **配置连接：** 选择目标数据库类型，填写 URL、独立凭据与精确范围，探测并保存。
3. **创建任务：** 选择 CORE / FULL / CUSTOM，检查用例、Provider、预算及写入范围，明确授权后执行。
4. **阅读结果：** 查看进度、逐项 JDBC 契约结论和套件额外要求，下载报告。
5. **检查清理：** 查看 `cleanupEvidence` 及残留/不确定对象，手工处理前核实归属。

完整操作路径见[使用指南](docs/usage.zh-CN.md)。第三方目标驱动不随项目分发。

## 结果解释与使用边界

`SUCCEEDED` 只表示任务执行与报告处理完成，不等于全部用例通过。合法 unsupported 不算功能通过；
JDBC 契约结论、SUITE 额外要求、准备/环境问题和清理证据分别判断。
`SUITE_REQUIREMENT_UNMET` 是 `NOT_EVALUATED` 的子集，不能重复计数，见[JDBC 判定参考](docs/jdbc-capability-contracts.zh-CN.md)。
不同版本或选择的历史报告不能用于数据库通过率排名。

平台 PostgreSQL 保存网站状态；被测目标数据库另行配置。测试会执行 **DDL/DML**，
必须使用明确授权的专用数据库/schema。网站当前没有内置认证，需要限制网络访问。
只加载可信驱动，隔离 Worker 不能保证恶意驱动安全。清理成功也不单独证明已有对象未受影响。

<a id="documentation"></a>
<a id="文档入口"></a>
## 文档与贡献

从[中文文档导航](docs/README.md)按任务阅读：
[使用](docs/usage.zh-CN.md) → [部署运维](docs/deployment.zh-CN.md) →
[开发与贡献](docs/development.zh-CN.md)；判定、CLI 回归和验证记录也有独立入口。
英文读者从 [English documentation](docs/README.en.md) 开始。

<a id="advanced-guides"></a>
<a id="进阶入口"></a>
开发前阅读[开发检查与资源清理规则](docs/development.zh-CN.md#testing)，CLI 配置见[示例](examples/README.zh-CN.md)。
本项目使用 [Apache License 2.0](LICENSE)；保留上游声明。
