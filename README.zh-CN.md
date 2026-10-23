# JDBC Test

[English](README.md) | 简体中文

在真实数据库上测试 JDBC 驱动行为：上传外部驱动、配置连接、选择标准 JDBC 用例，
由隔离 Worker 执行并提供结果和报告。CLI 还支持驱动 A/B 回归比较。

从这里开始：[使用指南](docs/usage.zh-CN.md) · [部署指南](docs/deployment.zh-CN.md)。

项目内置 PostgreSQL、MySQL、Oracle、SQL Server、GaussDB、KingbaseES 和达梦适配。
内置适配提供准备与执行规则，不代表全部 JDBC 测试通过。
各数据库的配置与验证边界见[使用指南](docs/usage.zh-CN.md#database-configuration)。

## 启动网站

源码开发需要 **JDK 21、Node >=22.12/npm**、Maven Wrapper，以及具有迁移权限的专用
**平台 PostgreSQL**。平台库存储网站状态，被测目标数据库稍后在页面中单独配置。

在源码根目录执行；仅在文件不存在时复制，然后编辑实际 `.env` 中的数据源 URL、用户名和密码：

```sh
umask 077
cp -n .env.example .env
./jdbc-test dev
```

打开命令打印的就绪地址，通常为 `http://127.0.0.1:5173`。
启动器仅对全新空平台生成 Master Key；后续启动保留原 `.env`、Key 和数据。
Ctrl+C 停止本次启动的前后端进程。Windows 在 PowerShell 中复制并编辑 `.env` 后，
使用 `./jdbc-test.ps1 dev`。源码启动细节见下方进阶入口。

部署可选 [Docker 自带 PG](docs/deployment.zh-CN.md#docker-bundled)、
[Docker 连接已有 PG](docs/deployment.zh-CN.md#docker-external)，或
[Java 21 部署包](docs/deployment.zh-CN.md#native)。使用 `./jdbc-test build` 构建部署包。

## 开始第一次测试

1. 在**驱动管理**上传驱动和所需依赖 JAR，检查加载结果。
2. 在**连接管理**选择数据库类型，填写 URL 和独立凭据，测试并保存。
3. 配置明确授权的专用数据库/schema。在**新建任务**中核对名称、用例和写入范围，授权后启动。
4. 查看进度、逐项结论、清理证据并下载报告。

测试会执行 DDL/DML，务必使用已授权的专用范围和可信驱动。
网站没有内置认证，应限制网络访问。操作细节见[使用指南](docs/usage.zh-CN.md)。

## 文档入口

| 用途 | English | 简体中文 |
|---|---|---|
| 驱动、连接、任务与结果 | [Usage](docs/usage.md) | [使用指南](docs/usage.zh-CN.md) |
| 部署、配置、备份与升级 | [Deployment](docs/deployment.md) | [部署指南](docs/deployment.zh-CN.md) |

### 进阶入口

| 用途 | English | 简体中文 |
|---|---|---|
| 架构、本地开发与扩展 | [Development](docs/development.md) | [开发指南](docs/development.zh-CN.md) |
| 驱动 A/B 比较与退出码 | [Driver regression](docs/internal-regression.md) | [驱动回归](docs/internal-regression.zh-CN.md) |
| JDBC 契约与归因边界 | [JDBC reference](docs/jdbc-capability-contracts.md) | [JDBC 判定参考](docs/jdbc-capability-contracts.zh-CN.md) |
| CLI 配置示例 | [Examples](examples/README.md) | [示例](examples/README.zh-CN.md) |

Apache License 2.0。第三方驱动是独立测试输入，不随项目分发。
