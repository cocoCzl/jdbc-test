# 部署指南

[English](deployment.md) | 简体中文

修改/调试源码请看[开发指南 → 本地源码启动](development.zh-CN.md#local-source-startup)，使用 `./jdbc-test dev`。只运行网站请选择下面**一种**方式。Native 指**非 Docker 部署包运行**，不是 IDEA 源码启动。所有方式使用统一入口；Windows 将 `./jdbc-test` 换成 `./jdbc-test.ps1`。

## 配置写在哪里？

| 模式 | 实际配置位置 | 平台 PG URL |
|---|---|---|
| 源码开发 | 源码根目录 `.env` | `SPRING_DATASOURCE_URL` |
| Docker 已有 PG | 源码根目录 `.env` | `SPRING_DATASOURCE_URL` |
| Docker 自带 PG | 源码根目录 `.env`，生成 `POSTGRES_*` | Compose 决定 `jdbc:postgresql://postgres:5432/${POSTGRES_DB:-jdbc_test}` |
| 非 Docker 部署包默认 | 解压后部署包根目录 `.env` | `SPRING_DATASOURCE_URL` |
| 旧 Native 文件替代方式 | 部署包 `config/application.properties` | `spring.datasource.url` |
| 旧环境变量替代方式 | 启动 Java 的终端/服务进程环境 | `SPRING_DATASOURCE_URL` |

**平台数据库**保存网站状态和加密凭据；**目标数据库**另行配置用于 JDBC 测试，启动网站不需要。**数据库密码**用于 PG 认证；独立的 **`JDBC_TEST_MASTER_KEY`** 加密已保存的目标库凭据，必须在重启/恢复时保留。

源码开发固定三步：**源码根目录 `.env.example` → `.env`（不覆盖），编辑实际 `.env` 数据库三项，执行 `./jdbc-test dev`**。详见[开发指南](development.zh-CN.md#local-source-startup)。同一模板包含 Docker 地址注释，重复 `.env.external.example` 已合并，不需再选模板。部署包在自己的根目录复制同一模板；Docker 自带 PG 则不复制外部 PG 模板，由统一 Docker 命令生成 `POSTGRES_*`。每套环境一份配置，模式不能混用。

<a id="docker-bundled"></a>
## 1. Docker + 自带 PG

**准备：** Docker Engine/Desktop、Compose v2、Shell/PowerShell、空闲应用端口（默认8080）、源码和镜像/依赖下载网络。宿主机无需 Java/Node/Maven/Python。全新安装应没有 `.env`、原项目容器/卷或应用数据。

**终端命令 — 源码根目录，Linux/macOS：**

```sh
./jdbc-test docker --bundled-pg
# 成功后打开打印地址（默认 http://127.0.0.1:8080）。
# 下次启动复用原 .env/Key/project：
./jdbc-test docker --bundled-pg
# 停止，保留 PG/应用卷和配置：
./jdbc-test docker --bundled-pg --stop
```

首次构建 setup/应用镜像，检查实际 Compose project/路径/卷和本地数据。全新空平台检查通过后，无需交互确认，初始化器自动生成 **32 随机字节十六进制 PG 密码**及独立 **32 随机字节 Base64 Master Key**，保存到源码根目录 `.env`，PG 与 Server 读取同一个密码。不需复制秘密。随后检查配置，执行 Compose 并等待健康/就绪；正式 Server 启动执行 migration。

默认数据库/用户 `jdbc_test`，容器 PG 主机 `postgres:5432`。连接地址由 `docker-compose.yml` 决定，不是用户填写外部 URL。以后保留原实际 `.env`、project 名称和卷。创建独立安装可明确使用 `--project NAME`。

<a id="docker-external"></a>
## 2. Docker + 已有 PG

**先看地址：** `jdbc:postgresql://pg.example.com:5432/jdbc_test`。替换实际主机/端口/库名，**必须含数据库名，不嵌入账号密码**。宿主机 PG 用 `host.docker.internal`；Docker Desktop 支持，Linux 还需 `--host-pg`。容器内 `localhost` 指该容器，不是宿主机。工具不会改写 URL。

**准备：** Docker/Compose，以及已准备好且具备 migration 权限的专用平台 PG 数据库/账号，必须**从容器内**可达。不需要 `POSTGRES_PASSWORD`；客户提供现有完整 URL、用户名和密码。不使用共享 QA/目标库。

**终端命令 — 源码根目录，Linux/macOS：**

```sh
./jdbc-test docker --external-pg
# 没有 .env 时交互输入 URL/用户名/隐藏密码。
# 成功打开打印地址；以后：
./jdbc-test docker --external-pg
./jdbc-test docker --external-pg --stop
```

**交互输入 — `.env` 不存在时实际提示：**

```text
Full platform JDBC URL (database name required; no user/password in URL): jdbc:postgresql://pg.example.com:5432/jdbc_test
Existing platform database username: jdbc_test
Existing database password (hidden):
```

URL 输入原始地址，不带 `SPRING_DATASOURCE_URL=` 前缀或 Shell 引号，密码隐藏输入。只读认证并检查空状态后，只生成 Master Key，保存源码根目录 `.env`，不改变客户账号密码。已有私有 `.env` 仅填三项且 Key 为空时，统一入口检查 project、数据库/数据目录为空，检查通过后自动补 Key，无需交互确认。已有状态而缺失 Key 必须停止恢复。

Linux 宿主机 PG 使用 **`./jdbc-test docker --external-pg --host-pg`**，停止时也保留此选项并追加 `--stop`。正式启动运行 migration。容器 URL 与源码 dev URL 可能不同，分别维护各自环境，不能自动乱改地址。

<a id="native"></a>
## 3. 非 Docker 部署包运行 + 已有 PG

**准备：** Java 21 runtime、Shell/PowerShell、空闲8080端口、已准备好的专用平台 PG 数据库/账号，以及本版本完整部署包（[构建见下](#build-bundle)）。运行机器**无需 Node/npm/Maven/Python**。不宣称未验证的 Release 已有下载。

**终端命令 — 解压后的部署包根目录，Linux/macOS：**

```sh
umask 077
cp -n .env.example .env
# 编辑实际部署包根目录 .env：平台 URL、用户名、现有密码。
./jdbc-test start
# 就绪后打开打印地址。Ctrl+C 停止并保留数据。
# 下次使用相同配置/Key，仍只执行 ./jdbc-test start。
```

PG 在运行机器本机时填 `jdbc:postgresql://localhost:5432/jdbc_test`，替换实际主机/端口/库名/用户。这里是**解压后的部署包 `.env`**，不是源码配置。入口保护本地文件，只读认证 PG，检查新装状态；全新空平台缺 Key 时自动生成并保存到同一文件，无需交互确认，随后检查产物/端口，启动 Server、等待 health/Catalog 并提供生产页面。正式启动运行平台库 migration，应用文件默认部署包 **`data/`**。已有数据必须原 Key；停止不删除配置/PG/数据。

### Windows 替代命令集中展示

在对应源码/部署包根目录的 **PowerShell** 中执行，仅选择一种模式，不依次执行所有示例：

```powershell
# 源码开发或构建机器：
./jdbc-test.ps1 dev
./jdbc-test.ps1 build
# Docker 源码根目录，二选一：
./jdbc-test.ps1 docker --bundled-pg
./jdbc-test.ps1 docker --bundled-pg --stop
# 或已有 PG（Docker Desktop 不需要 Linux 的 --host-pg）：
./jdbc-test.ps1 docker --external-pg
./jdbc-test.ps1 docker --external-pg --stop
# 解压后的部署包：仅不存在时复制，编辑 .env，再启动：
if (-not (Test-Path '.env')) { Copy-Item .env.example .env }
./jdbc-test.ps1 start
```

前一步失败就停止，不能使用不完整配置继续。执行策略限制时按本地策略使用进程级 invocation，不改机器全局策略。Windows 宿主机 ACL/进程行为仍需真机验证。

<a id="build-bundle"></a>
## 构建机器与运行机器

**终端命令 — 源码根目录：**

```sh
./jdbc-test build
# 默认输出已存在时选择另一目录：
./jdbc-test build --output dist-next
```

构建需要 **JDK21、Node >=22.12/npm、Python >=3.9** 和下载依赖的网络，Maven Wrapper 提供 Maven。入口自动构建 Java/Worker、生产前端并调用现有允许列表打包逻辑。无需 PG 配置，也不复制实际 `.env`、私有配置、数据或日志。输出 `dist/jdbc-test-server-<版本>/`、`.tar.gz`、`.zip` 和校验和。已有输出安装不覆盖。传到运行机器、核对校验和并完整解压；运行 `./jdbc-test start` 只需 Java21。

<a id="configuration-rules"></a>
## 进阶：配置规则与旧入口兼容

普通用户只复制/编辑 `.env` 并使用 `jdbc-test`。统一入口以本地 `.env` 为唯一秘密来源，不混入继承 datasource/Key 或旧 properties。没有 `.env` 时，已有部署包 properties/环境配置由同一入口直接检查并启动。历史部署包 `start.sh/start.ps1` **仅保留转发**，init/check 包装已删除。已有旧配置不能另建 `.env` 并生成替代 Key；确需转换时必须保留原 Key。

新 `.env` 格式是**字面值 UTF-8 赋值**：

- 支持整行 `#` 注释和空行；不支持行尾注释、变量展开、`export` 或多行值。
- 未加引号时保留 `=` 之后所有字符，包括前后空格、`$`、`#`、`=`、反斜杠和 `${NAME}`。
- 以 `"` 开头采用 **JSON 字符串转义**（Jackson codec）；以 `'` 开头采用一对字面值单引号。复杂引号密码优先JSON 字符串编码，不手工猜转义。
- 空字段是配置准备状态；拒绝 NUL/control/tab/换行/Unicode 行分隔符、非法 UTF-8 和重复变量，不静默 trim。
- 旧工具生成且以 `# Compose dotenv only;` 开头的文件按旧 Compose 编码解码一次。Docker 入口从字面值源生成私有临时 Compose 编码并在操作后删除。特殊密码时**不能把新字面值 `.env` 直接交给旧 Compose**。

不能 `source`/`eval` `.env` 或 Java `.properties`；它们不是 Shell 脚本，读取规则不同。模板含空秘密，不能直接运行。配置和数据保护权限，Git/构建上下文/归档排除私有内容。Key 仅运行时全新空平台检查通过后自动生成，绝不烘焙进镜像。配置、数据/PG 备份保持私有，不分享实际 `.env`、环境 dump、`docker inspect` 或展开后的 Compose 配置。

### 已有 properties / 环境配置兼容

仅供已有安装：保留部署包 `config/application.properties` 的 `spring.datasource.url/username/password`、`jdbc-test.security.master-key`；或从原受保护的服务环境注入全部四项 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`、`JDBC_TEST_MASTER_KEY`。**仍执行同一个 `./jdbc-test start`**，不另跑 init/check/start 流程。Spring preflight 在正式启动前检查配置和端口。Properties 是 Java 语法，不是 dotenv，更不能 Shell source。

没有 `.env` 时，Spring datasource 环境覆盖文件，`JDBC_TEST_MASTER_KEY` 映射到 `jdbc-test.security.master-key` 并以环境优先，空模板不覆盖有效环境 Key。Bash export 只影响同一终端子进程；PowerShell `$env:` 是另一语法。手工 Maven/IDEA 不读 `.env`。新终端/重启需重新注入原环境，不重新生成 Key。新用户继续复制/编辑 `.env` 方式。

### Docker 高级配置

统一入口支持 `--project`、`--port`、`--host-pg`、`--new-install`（仅兼容旧命令，无需填写，也不能绕过状态检查）、`--stop`。 CLI `--port` 对已有文件仅覆盖本次操作；长期修改请编辑 `.env` 保存的端口（首次 init 会保存选定端口）。

始终选择明确 PG 模式，拒绝模式/project 冲突，不改 URL。自定义网络/Compose 存储必须按原 project/配置恢复和审查；默认入口无法推断已移动/更名的历史部署。`JDBC_TEST_SETUP_NETWORK` 选择已有 setup 网络，实际 Server 也需通过审查后的 Compose override 加入同一网络。`JDBC_TEST_SETUP_IMAGE` 仅为高级用户指定本地构建的**当前版本**镜像，不是公共镜像承诺，也不能作为标准构建通过证明。

## 恢复、检查和日常管理

只读 preflight 验证 PG 认证、新装空状态，不执行 migration。正式 dev/start/Compose up 执行 migration；启动检查不跑目标库测试。缺 `.env` 不等于新装：检查所选实际 Compose project/路径/卷、有效数据路径以及提供的 PG schema。已有状态缺 Key 必须恢复**原配置/Key**，不能生成替代。安全随机源失败立即退出；私有 staging、锁和原子写保护半成品与并发覆盖。残留锁须先确认无初始化/启动进程再手工恢复。不执行 DROP、删除卷、重置密码或权限提升。

dev/部署包 Ctrl+C 停止，Docker 用对应命令追加 `--stop`，保留配置和数据。**平台 PG + 应用文件 + 原 Key/配置**一起备份，先停 Server 保持一致快照；恢复整套后再启动。升级替换源码/部署包，保留 project/存储/配置/Key，不重新初始化，不让两个 Server 同时连接同一平台。历史 migration 不变。

错误 URL/密码/Key 格式及端口占用会给可理解错误。格式正确但值错误的32字节 Key 静态无法识别，必须保留原 Key 才能解密；不能通过删除状态解决错误。UI 无认证，保持 loopback 或使用受控代理/VPN，仅加载可信驱动。见[执行安全](execution-safety.zh-CN.md)。

<a id="validation-limits"></a>
## 验证边界

本轮在 macOS/JDK21/Node24、独立临时平台 PG 中实测：复制示例并编辑三项，一条 dev 命令完成构建及源码后端/Vite/Catalog/代理/页面就绪，再次 dev 复用原 Key，Ctrl+C 收尾并保留数据；统一 build、部署包复制/编辑/start、重启后已保存凭据解密、缺 Key 恢复保护均通过。历史 start 转发也通过已有 properties 和纯环境配置启动。两种整理后的 Docker 入口用本轮构建产物和替代本地运行时镜像，通过实际 Compose/Spring/PG 认证，包括特殊 UTF-8 密码完整传递、自带 PG 与应用读取同一自动生成密码。停止/重启保留配置/Key/卷；已有 project 状态缺配置时拒绝重新生成。

**标准 Docker 源码构建本轮重试，仍被 Temurin21 JDK 基础镜像的 Docker Hub OAuth token 超时阻塞**；替代运行时测试不算标准构建成功。Windows 宿主机 ACL/进程、本轮 PowerShell 运行链路及 IDEA UI Run/Debug 尚未实测。本轮没有执行目标测试、QA 或历史副本验收。
