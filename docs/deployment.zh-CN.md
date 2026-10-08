# 部署指南

[English](deployment.md) | 简体中文

需要 Docker Engine/Desktop 和 Compose v2。首次构建需要访问 npm、Maven Central 和镜像仓库；主机无需安装 Java、Maven、Node 或 PostgreSQL。镜像支持主机架构，包括 arm64。

## 启动

在仓库根目录执行：

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

编辑 `.env`：设置强密码 `POSTGRES_PASSWORD`，将生成的密钥填入 `JDBC_TEST_MASTER_KEY`。密钥解码后必须恰好为 32 字节，重启和恢复时必须保持不变，否则无法解密已保存的密码。不要提交 `.env`，也不要分享包含密钥的配置输出。修改 `.env` 不会更改已有 PostgreSQL 数据卷中的数据库密码。

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

两项服务健康后打开 **http://localhost:8080**。Server 同源提供 Web UI、REST 和 SSE，并为每个 Worker 启动独立子 JVM。只需要 `jdbc-test` 和 `postgres` 两项服务；生产环境不运行 Vite。健康检查覆盖平台数据库，不探测目标数据库。

## 配置

| 环境变量 | 默认值 / 用途 |
|---|---|
| `POSTGRES_DB`、`POSTGRES_USER` | `jdbc_test` |
| `POSTGRES_PASSWORD` | 必填，无默认值 |
| `JDBC_TEST_MASTER_KEY` | 必填，持久保存的 Base64 AES-256 密钥 |
| `JDBC_TEST_PORT` | `8080` |
| `JDBC_TEST_BIND_ADDRESS` | `127.0.0.1` |
| `JDBC_TEST_MAX_CONCURRENT_RUNS` | `1` |
| `JDBC_TEST_RUN_TIMEOUT` | `30m` |
| `JAVA_TOOL_OPTIONS` | 可选 JVM 参数，Worker 也会继承 |

Compose 使用 `docker` profile，应用文件存放在 `/data/jdbc-test`，Worker 路径为 `/app/worker/jdbc-test-worker.jar`。平台 PostgreSQL 通过服务名 `postgres` 访问，不向主机发布端口。Server 和 Worker 使用 UID/GID 10001；自定义绑定挂载必须允许该用户写入。

多阶段构建生成前端资源和 Java 制品，不执行数据库端到端测试。运行时包含 Java 21、应用制品和静态资源；目标驱动由使用者在运行时提供。镜像标签会接收补丁更新；需要不可变构建时应固定已审核的镜像摘要。`Dockerfile.cli` 和仅包含源码的 CLI ZIP 与生产 Compose 部署分别维护。

## 目标数据库访问与安全

目标数据库必须能从应用容器访问。JDBC URL 中的 `localhost` 指容器自身。Docker Desktop 上访问主机数据库可使用 `host.docker.internal`，同时配置数据库监听地址和防火墙。Linux 可能还需要通过 Compose `extra_hosts` 将该名称映射到 `host-gateway`。同一 Docker 网络中的数据库可使用服务名访问。

应用**没有用户认证**。仅部署在 localhost 或可信内网；外部访问需要带访问控制的代理或 VPN。只加载可信驱动：独立 Worker JVM 不是安全沙箱，Server 与 Worker 共享文件系统、用户和网络访问权限。测试会执行 DDL/DML，必须使用专用且明确授权的范围，见[执行安全](execution-safety.zh-CN.md)。

## 持久化与升级

`postgres_data` 保存平台元数据、加密凭据和测试结果。`jdbc_test_data` 保存驱动、工作目录、报告和暂存文件。

```sh
docker compose restart jdbc-test
docker compose down
docker compose up -d
```

上述命令保留命名数据卷。**`docker compose down -v` 会永久删除两个数据卷。** 重启或恢复时保持相同的 Compose 项目名。Flyway 执行迁移，Hibernate 只验证 schema，不要开启自动建表或更新。

升级前先备份，然后切换到目标源码版本，执行 `docker compose build` 和 `docker compose up -d`。迁移后的回滚可能需要恢复备份。PostgreSQL 跨大版本升级必须采用受支持的迁移流程，不能仅更改镜像标签。

关闭服务时会中断活动任务、清除临时凭据并终止 Worker。启动恢复会将未完成任务标为 `INTERRUPTED`，不会自动继续运行。

## 备份与恢复

同时备份平台数据库和应用数据，另行保存同一份 master key。仅备份数据库无法恢复驱动 JAR 和文件系统报告。简单的一致性备份可先停止应用：

```sh
docker compose stop jdbc-test
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > platform.dump
```

应用停止期间，用数据卷备份工具归档 `jdbc_test_data` 并保留所有权信息。完成后执行 `docker compose start jdbc-test`。恢复时先还原配套的数据库、数据卷和 master key，再启动应用。备份文件应按敏感资料保管；项目没有内置自动备份服务。

## 排障与验证

```sh
docker compose logs --tail=100 jdbc-test
docker compose logs --tail=100 postgres
sh scripts/docker-smoke.sh
```

优先检查数据库健康、密钥格式、数据卷权限、端口冲突和镜像仓库连通性。驱动文件丢失时先检查数据卷与项目名，再决定是否重新上传。目标数据库离线会导致连接探测或任务失败，不影响平台健康检查。分享前需对诊断信息脱敏。

验证生产浏览器流程时，通过受保护的环境提供私有驱动和目标数据库凭据，设置 `JDBC_TEST_E2E_MODE=production`，必要时设置 `JDBC_TEST_E2E_BASE_URL`（默认 `http://127.0.0.1:8080`），然后执行 `npm --prefix jdbc-test-web run test:e2e:real`。使用全新的验收存储，不要为测试清空已有安装。构建与回归命令见[开发指南](development.zh-CN.md#测试)。

验收运行后可检查持久化：

```sh
python3 scripts/docker-persistence.py capture /tmp/jdbc-test-checkpoint.json
docker compose restart jdbc-test
# 等待健康状态为 UP 后再验证。
python3 scripts/docker-persistence.py verify /tmp/jdbc-test-checkpoint.json
```

执行 `docker compose down` 和 `docker compose up -d`、等待服务健康后再次验证。检查点验证已捕获的 ID、驱动与报告摘要、结果、驱动验证和已保存密码的连接探测。非 Docker 部署上的检查不能证明 Docker 数据卷行为。
