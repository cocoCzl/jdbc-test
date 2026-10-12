# 部署指南

[English](deployment.md) | 简体中文

选择下面一种方式，按对应步骤操作即可。不要将两种 Docker 入口文件混用。

| 方式 | 适用情况 | 运行所需环境 | 配置入口 |
|---|---|---|---|
| [Docker + 自带 PG](#bundled-pg) | 没有管理数据库，想快速启动 | Docker Engine/Desktop、Compose v2 | `.env.example` → `.env` |
| [Docker + 已有 PG](#external-pg) | 已有自建或托管 PostgreSQL | Docker Engine/Desktop、Compose v2、已有 PG | `.env.external.example` → `.env` |
| [非 Docker + 已有 PG](#native) | 直接在服务器运行应用 | Java 21、已有 PG | 部署包中的 `config/application.properties` |

**管理数据库**保存连接配置、加密凭据、任务和结果；**目标数据库**是在 Web 页面中配置的被测试数据库。两者独立，建议使用不同的数据库和账号。本文的 URL、用户名和密码都指管理数据库。

管理数据库使用 PostgreSQL，当前构建与集成验证以 PostgreSQL 16 为基准。Server 已包含管理 PG 的驱动；上传的 JDBC 驱动用于目标数据库。Flyway 自动创建、迁移应用表，Hibernate 只验证结构，无需手动执行项目 SQL。

<a id="bundled-pg"></a>
## 1. Docker + 自带 PG

主机无需安装 Java、Maven、Node 或 PostgreSQL。首次构建需访问 npm、Maven Central 和镜像仓库；镜像支持主机架构，包括 arm64。

### 第一步：准备配置

在仓库根目录执行：

```sh
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

编辑 `.env`，填写两项必填配置：

```dotenv
POSTGRES_PASSWORD='替换为强密码'
JDBC_TEST_MASTER_KEY=粘贴生成的密钥
```

其他配置可以保留默认值。`POSTGRES_DB`、`POSTGRES_USER` 都默认 `jdbc_test`。**不用填写 URL**：Compose 自动启动 `postgres`，连接 `jdbc:postgresql://postgres:5432/jdbc_test`；修改 `POSTGRES_DB` 后 URL 中的数据库名同步变化。此模式使用 `POSTGRES_*`，不读取外部 PG 的 `SPRING_DATASOURCE_*`。

### 第二步：启动

```sh
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

等 `jdbc-test`、`postgres` 两项服务健康、健康接口返回 `UP`，打开 **http://localhost:8080**。无需单独启动 Worker 或前端。

### 日常操作

```sh
docker compose logs --tail=100 jdbc-test
docker compose restart jdbc-test
docker compose stop
docker compose start
```

`postgres_data` 保存管理数据库，`jdbc_test_data` 保存驱动、工作目录、报告和暂存文件。PG 端口不向主机发布。修改 `.env` 不会修改已有 PG 数据卷中的账号、密码，账号变更需由数据库管理员执行。

<a id="external-pg"></a>
## 2. Docker + 已有 PG

此方式只启动 `jdbc-test`，不启动 PG 容器、不创建 `postgres_data`，也不要求 `POSTGRES_PASSWORD`。

### 第一步：准备管理数据库

让 PG 管理员准备专用数据库和拥有该数据库的登录账号。例如，在管理员的 `psql` 会话中执行：

```sql
CREATE ROLE jdbc_test LOGIN;
\password jdbc_test
CREATE DATABASE jdbc_test OWNER jdbc_test;
```

`\password` 会交互式要求设置密码。已有符合要求的专用数据库和账号可以跳过此步骤。应用账号需要在应用 schema（默认 `public`）中创建、修改表和索引的权限，以供 Flyway 迁移，不需要超级用户权限。数据库必须预先存在，应用不会创建数据库本身。不要使用存有其他业务表的数据库。

确认 PG 的监听地址、`pg_hba.conf` 和防火墙允许应用连接。托管 PG 按服务商要求在 JDBC URL 中添加 SSL 参数。

### 第二步：填写配置

在仓库根目录执行：

```sh
cp .env.external.example .env
chmod 600 .env
openssl rand -base64 32
```

已有 `.env` 时先保留备份，不要直接覆盖。编辑四项配置：

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://你的PG地址:5432/jdbc_test
SPRING_DATASOURCE_USERNAME=jdbc_test
SPRING_DATASOURCE_PASSWORD='替换为数据库密码'
JDBC_TEST_MASTER_KEY=粘贴生成的密钥
```

URL 必须包含数据库名；用户名、密码分别填写，不要放进 URL。Compose `.env` 中密码使用单引号可保留字面量 `$`、`#`；密码含单引号时按 Compose dotenv 规则转义。

**地址必须能从容器访问**：

| PG 所在位置 | URL 中的主机名 |
|---|---|
| 远程服务器或托管 PG | 实际 IP / DNS，例如 `pg.example.com` |
| Docker Desktop 宿主机 | `host.docker.internal`，不能写 `localhost` |
| 同一 Docker 网络 | 网络上的 PG 服务名；应用需要加入此网络 |

Linux 访问宿主机 PG 时，可使用 `host.docker.internal` 并额外加上下面提供的 `docker-compose.host-gateway.yml`。

### 第三步：启动

```sh
docker compose -f docker-compose.external.yml config --quiet
docker compose -f docker-compose.external.yml up -d --build --wait
docker compose -f docker-compose.external.yml ps
curl --fail http://localhost:8080/actuator/health
```

健康接口返回 `UP` 后打开 **http://localhost:8080**。Linux 连接宿主机 PG 的启动命令为：

```sh
docker compose -f docker-compose.external.yml -f docker-compose.host-gateway.yml up -d --build --wait
```

使用附加文件后，后续管理命令保留同一组 `-f` 参数。不要加 `-f docker-compose.yml`，否则会混入自带 PG 的配置。

### 日常操作

```sh
docker compose -f docker-compose.external.yml logs --tail=100 jdbc-test
docker compose -f docker-compose.external.yml restart jdbc-test
docker compose -f docker-compose.external.yml stop
docker compose -f docker-compose.external.yml start
```

应用文件保存在 `jdbc_test_data`，数据库持久化、备份和可用性由已有 PG 的运维方式负责。

<a id="native"></a>
## 3. 非 Docker + 已有 PG

部署包包含 Server JAR、Worker JAR 和已经构建好的前端，运行时只需 **Java 21**，无需 Maven、Node、Python 或 Vite。支持 Linux/macOS 的 Shell 启动和 Windows 的 PowerShell 启动。

### 第一步：取得部署包

在 [GitHub Releases](https://github.com/cocoCzl/jdbc-test/releases) 中选择 `jdbc-test-server-<版本>.tar.gz`（Linux/macOS）或 `.zip`（Windows）。历史版本可能只有源码包；新的发布流程才提供 Server 部署包。`jdbc-test-<版本>.zip` 是 CLI 源码包，不是 Server 部署包。

尚未发布时，在源码仓库根目录构建一次：

```sh
python3 scripts/build_distribution.py
```

**仅构建机器**需要 JDK 21、Python >=3.9、Node >=22.12.0 和 npm；Maven Wrapper 自动提供 Maven，首次构建需联网。生成 `dist/jdbc-test-server-<版本>/` 目录、两种压缩包和 SHA-256 文件，将压缩包复制到运行机器即可。

为保护已保存的配置和数据，构建不会覆盖同名部署目录。再次构建可使用 `python3 scripts/build_distribution.py --output dist-new`，也可选择其他新的输出目录。

Linux/macOS 解压示例（换成实际版本号）：

```sh
tar -xzf jdbc-test-server-1.2.1.tar.gz
cd jdbc-test-server-1.2.1
```

Windows 解压 `.zip`，在解压后的目录打开 PowerShell。目录结构如下：

```text
jdbc-test-server-<版本>/
├── start.sh / start.ps1
├── config/application.properties.example
├── server/jdbc-test-server.jar
├── worker/jdbc-test-worker.jar
├── web/
└── docs/
```

### 第二步：填写配置

按[已有 PG 的准备步骤](#external-pg)准备专用数据库和账号。在部署目录执行：

```sh
cp config/application.properties.example config/application.properties
chmod 600 config/application.properties
openssl rand -base64 32
```

Windows PowerShell 对应命令：

```powershell
Copy-Item config/application.properties.example config/application.properties
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
[Convert]::ToBase64String($keyBytes)
$rng.Dispose()
```

编辑 `config/application.properties` 的前四项：

```properties
spring.datasource.url=jdbc:postgresql://你的PG地址:5432/jdbc_test
spring.datasource.username=jdbc_test
spring.datasource.password=替换为数据库密码
jdbc-test.security.master-key=粘贴生成的密钥
```

这是 Java `.properties` 文件，**值不要加引号**；值中的 `$`、`#` 按字面量使用，反斜杠写成 `\\`。Windows 路径建议使用 `/`，并通过文件权限限制配置文件访问。

非 Docker 模式的 `localhost` 就是运行应用的主机，本机 PG 可以使用它。脚本自动加载此配置文件，**不会读取仓库 `.env`**。其他默认配置可保留：本机 `8080` 端口、数据目录 `./data`，以及随包提供的 Worker 和前端。

### 第三步：启动

Linux/macOS：

```sh
java -version
sh start.sh
```

Windows PowerShell：

```powershell
java -version
.\start.ps1
```

如果 PowerShell 执行策略阻止脚本，可仅对本次进程使用 `powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1`。

保持启动终端打开，访问 **http://localhost:8080**。另开终端执行 `curl --fail http://localhost:8080/actuator/health`（Windows 使用 `curl.exe`），返回 `UP` 表示管理数据库和应用已就绪。Server 同源提供页面、REST 和 SSE，自动启动 Worker。

### 停止、重启与常驻运行

按 `Ctrl+C` 正常停止，再运行启动脚本重启；配置和 `data/` 都保留。脚本会切换到自身目录，从其他工作目录启动也能找到正确资源。

Linux 需要开机启动时，可选 systemd。假设部署目录是 `/opt/jdbc-test`、运行账号是 `jdbc-test`，创建 `/etc/systemd/system/jdbc-test.service`：

```ini
[Unit]
Description=JDBC Test Server
After=network-online.target
Wants=network-online.target

[Service]
User=jdbc-test
WorkingDirectory=/opt/jdbc-test
ExecStart=/bin/sh /opt/jdbc-test/start.sh
Restart=on-failure
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
```

由管理员先创建运行账号，并保证 Java 21 在服务的 `PATH` 中。部署文件放到 `/opt/jdbc-test` 后，为默认数据目录和已填写的配置设置权限：

```sh
sudo useradd --system --no-create-home jdbc-test
sudo mkdir -p /opt/jdbc-test/data
sudo chown jdbc-test:jdbc-test /opt/jdbc-test/data
sudo chown root:jdbc-test /opt/jdbc-test/config/application.properties
sudo chmod 640 /opt/jdbc-test/config/application.properties
```

已有运行账号时跳过 `useradd`；自定义数据目录时替换相应路径。部署目录及其他文件需允许该账号读取/进入。然后执行：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now jdbc-test
sudo journalctl -u jdbc-test -n 100
```

之后用 `sudo systemctl stop jdbc-test`、`sudo systemctl restart jdbc-test`，无需保留终端。

## 配置与密钥

| 用途 | Docker `.env` | 非 Docker `config/application.properties` |
|---|---|---|
| 已有 PG 的 URL | `SPRING_DATASOURCE_URL` | `spring.datasource.url` |
| 已有 PG 的账号 | `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` |
| 已有 PG 的密码 | `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` |
| 加密密钥（必填） | `JDBC_TEST_MASTER_KEY` | `jdbc-test.security.master-key` |
| HTTP 端口，默认 `8080` | `JDBC_TEST_PORT` | `server.port` |
| 监听地址，默认 `127.0.0.1` | `JDBC_TEST_BIND_ADDRESS`（主机发布地址） | `server.address` |
| 并行任务数，默认 `1` | `JDBC_TEST_MAX_CONCURRENT_RUNS` | `jdbc-test.execution.max-concurrent-runs` |
| 任务超时，默认 `30m` | `JDBC_TEST_RUN_TIMEOUT` | `jdbc-test.execution.run-timeout` |
| JVM 参数 | `JAVA_TOOL_OPTIONS` | 启动前设置环境变量 `JAVA_TOOL_OPTIONS` |

自带 PG 额外使用 `POSTGRES_DB`、`POSTGRES_USER`（默认 `jdbc_test`）和必填的 `POSTGRES_PASSWORD` 初始化 PG，并映射为应用的数据源配置；已有 PG 不使用这些变量。

master key 解码后必须恰好为 **32 字节**。首次安装生成一次，重启、升级、迁移和恢复保留同一密钥；换掉后无法解密已保存的密码。不要提交 `.env`、实际配置文件或分享含秘密的配置输出。修改 Docker `.env` 后用对应的 `up -d` 重新创建容器，`restart` 不加载新环境变量；非 Docker 修改配置后重启应用。

## 网络与访问安全

目标数据库也必须能从应用所在环境访问；容器中的 `localhost` 指容器自身，地址选择规则与上文相同。应用**没有用户认证**，默认仅绑定本机。远程访问需可信内网，并使用带访问控制的代理或 VPN；不要直接公开到互联网。

只加载可信驱动：Worker JVM 不是安全沙箱，Server/Worker 共享文件系统、用户和网络访问权限。测试执行 DDL/DML，使用明确授权的专用范围，见[执行安全](execution-safety.zh-CN.md)。健康检查只覆盖管理数据库，不探测目标数据库。

## 持久化、备份与升级

### 备份

同时备份**管理数据库、应用数据、同一份 master key**。仅备份数据库无法恢复驱动和文件系统报告。简单的一致性备份先停止应用：

| 方式 | 停止应用 | 应用文件位置 |
|---|---|---|
| Docker + 自带 PG | `docker compose stop jdbc-test` | `jdbc_test_data` 数据卷 |
| Docker + 已有 PG | `docker compose -f docker-compose.external.yml stop jdbc-test` | `jdbc_test_data` 数据卷 |
| 非 Docker + 已有 PG | `Ctrl+C` 或 `systemctl stop` | 部署目录的 `data/`，或自定义数据目录 |

自带 PG 在仓库根目录备份：

```sh
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > platform.dump
```

已有 PG 使用其备份服务，或由管理员在装有 PG 客户端的机器执行（按提示输入密码）：

```sh
pg_dump -h 你的PG地址 -p 5432 -U jdbc_test -d jdbc_test -Fc -f platform.dump
```

归档配套应用文件并保留所有权，另行安全保存密钥/配置，完成后启动应用。恢复时先由管理员恢复数据库，再还原配套应用文件和原 master key，最后启动应用。

### 升级与迁移

Docker：先备份，切换到新源码版本，运行对应方式的 `up -d --build --wait`。非 Docker：先备份、停止旧应用，解压新包，将原 `config/application.properties` 和 `data/` 复制到新目录（使用固定外部数据目录则保留其配置），再启动新包。不要让两个版本同时操作同一管理数据库和数据目录。

Flyway 启动时迁移，禁止启用 Hibernate 自动建表/更新；迁移后的版本回滚可能需要恢复备份。PG 跨大版本升级需数据库迁移。切换自带 PG / 已有 PG 不会自动搬运数据，必须迁移数据库、保留应用文件和同一 master key。

Docker `stop`、`restart`、`down` 保留命名卷；**`docker compose down -v` 永久删除当前配置的数据卷**。保持相同 Compose 项目名/仓库目录，或固定 `-p` 项目名，避免误用新卷。非 Docker 不要删除 `data/`。停止中断活动任务，重启后标为 `INTERRUPTED`，不自动续跑。

## 排障与验证

健康接口不返回 `UP` 时，查看对应方式的日志，检查 PG 地址和连接权限、数据库是否存在、账号密码、密钥格式、数据目录权限和端口占用。Docker Server/Worker 使用 UID/GID 10001，自定义绑定挂载需允许该用户写入。目标数据库离线只影响目标探测/测试，不影响管理数据库健康。

三种方式均可在源码仓库执行以下只读检查；端口变化时设置 `JDBC_TEST_BASE_URL`：

```sh
sh scripts/docker-smoke.sh
```

检查健康、场景目录、页面路由和静态资源，不运行数据库测试。真实目标验收见[开发指南的测试章节](development.zh-CN.md#测试)；部署包不含测试源码。

自带 PG 模式在独立验收环境中可验证持久化（不要为测试清空已有安装）：

```sh
python3 scripts/docker-persistence.py capture /tmp/jdbc-test-checkpoint.json
docker compose restart jdbc-test
# 等待健康状态为 UP 后再验证。
python3 scripts/docker-persistence.py verify /tmp/jdbc-test-checkpoint.json
```

也可在 `down` / `up -d` 后重复。检查点验证捕获的 ID、驱动/报告摘要、结果及保存密码的探测。已有 PG 和非 Docker 按各自停止/启动步骤检查相同数据是否保留。分享诊断信息前脱敏。
