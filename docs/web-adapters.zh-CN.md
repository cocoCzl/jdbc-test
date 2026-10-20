# Web 导入自定义数据库适配包

[English](web-adapters.md) | 简体中文

在 Web 的**数据库适配包**页面导入本地包。能够复用 `postgresql`、`mysql`、`oracle`、
`sqlserver` 已有方言的产品无需修改平台源码或重新构建；其他方言明确拒绝。
导入仅验证清单与资源完整性，不证明 SQL、产品版本、兼容模式或驱动已经可用。
本阶段不新增 SQL 引擎、Java 插件或在线编辑器，SQL 仍通过已有授权 Worker 生命周期执行。

## 将 CLI 包迁移到 Web

在源码目录先查看模板生成参数，已有本地包可直接打包：

```sh
python3 -B scripts/runner.py init-custom --help
python3 -B scripts/runner.py init-custom --id private-db --dialect postgresql \
  --driver-class example.jdbc.Driver --driver-dir /private/drivers \
  --url jdbc:example://localhost/disposable --username test_user \
  --adapter-output /private/adapters/private-db --output /private/config.private.json
python3 -B scripts/runner.py pack-adapter \
  --adapter /private/adapters/private-db --output /private/adapter.zip
```

生成后如实调整 `adapter.json` 中的身份匹配、版本范围、能力、命名空间和权限声明。
不得把其他厂商的验证组合当成本产品证据。`init-custom` 当前生成 Oracle/MySQL/PostgreSQL
示例；已有合法 SQL Server 包同样可以导出、导入。生成和打包均不连接数据库，ZIP 不覆盖已有文件。

`pack-adapter` 按现有 CLI 规则解析资源，将对应目录的 SQL、连接池 YAML 复制到包内
`ddl/`、`dml/`、`pool/`，将声明的版本组合证据复制到 `evidence/`。核心声明保留，仅规范化
资源根和证据路径，因此原包使用绝对资产路径时，Server 无需具有相同目录。
不收集 JAR、连接配置或密码；分享前仍需自行审查清单扩展字段和 SQL。
清单中的本地驱动来源路径不会用于定位 Web 驱动。导出的 ZIP 解压后也能作为普通 CLI 本地包使用。

1. 在**驱动**页面一起上传 JDBC 驱动和所需依赖 JAR，执行校验。驱动代码由短生命周期 Worker
   加载，Server 只存储和检查制品。
2. 在**数据库适配包**页面选择 ZIP，点击**校验并导入**。结果展示名称、ID、版本/修订、来源、
   方言、匹配条件和声明的验证组合。导入包始终为 `IMPORTED` 来源、有效状态 `LOCAL`；填写
   `trust=official/candidate` 也不会获得对应平台身份。原始声明保留在冻结字节中。
3. 点击**添加连接**，适配包已预选，驱动需独立选择。通过原有表单配置授权的专用临时
   数据库/模式和凭据。连接成功不代表适配包 SQL 可执行或 JDBC 契约测试通过。
4. 创建任务、选择已有用例、核对范围/驱动/适配包并授权破坏性测试。命名空间授权、对象前缀、
   归属和清理规则继续生效。Web 使用授权的已有范围，不执行 CLI 的创建/删除命名空间 SQL。
5. 任务详情、报告页面和 JSON 报告展示创建时冻结的适配包。历史证据不会查询当前库版本补齐；
   缺少历史快照时明确显示未知。驱动快照和指纹仍独立保存。

## 格式与校验边界

ZIP 根目录包含 `adapter.json`，使用 CLI/Core 相同的严格 JSON 与清单字段规则。
声明区域对应的 DDL/DML 必须存在，保留已有 `sqlxml=false` 资源例外。
Web 另外要求 `<pool根>/<assets.directory>.yaml`，保证连接池资源完整可迁移。
声称 official 的包仍须包含声明的证据文件，但不会获得正式来源。
共享清单测试和 CLI 导出→Java 导入测试验证核心声明一致；Java/Python 正则引擎差异仍是既有限制。

归档条目只能使用字母、数字、`_`、`-`、`.`、`/` 的相对可迁移路径，拒绝 `..`、反斜杠、
绝对路径和重复文件。资源只能位于声明资产根或 `evidence/` 下，拒绝可执行后缀、JAR 和 class。
最多 512 个条目（含目录）、单文件 1 MiB、解压总量 8 MiB、上传 ZIP 8 MiB。
Server 不解压文件；Worker 在自有任务目录内以新普通文件写入已校验字节，不采用归档符号链接信息。
错误返回稳定代码，并在可确定时指出具体字段或缺失资源。扩展字段保留，但不会执行。

## 保存、更新与保留

迁移 V9 在**平台管理数据库**新增 `imported_adapter`，保存当前清单字节、Base64 资源、
内容指纹、归档及并发状态。备份平台库时应包含该表，无需额外备份可变适配包文件目录。
服务重启后从持久化恢复，驱动制品继续使用原有驱动存储。

版本和修订是用户标签。导入包 SHA-256 覆盖原始清单及排序后的资源路径/字节，不受 ZIP
时间戳、压缩率或顺序影响。内置适配包 SHA-256 仅标识随平台发布的清单，SQL 在 Worker 构建中。
指纹不是签名，也不代表认证。

相同内容的活动包重复导入保持不变。同 ID 不同内容必须在页面明确选择**更新**，提交当前
`expectedFingerprint`；并发变化被拒绝。不同方言必须使用新 ID，禁止覆盖内置 ID。
连接选择适配包 ID，新任务使用该 ID 当前明确选定的内容，因此更新需要像更换驱动一样审查。
已创建任务的 `executionInput` 独立冻结完整资源、执行声明、公开来源、所选用例和驱动信息，
不会因之后更新或服务重启改变。

支持的移除操作是**归档**：连接仍在引用时拒绝归档，ID 和内容继续保留并从新选择列表隐藏。
连接写入与归档检查在适配包记录上串行化。本阶段不提供物理清除或版本历史浏览器。
旧库版本只在曾经冻结该版本的任务中保留；需要重选未使用的旧版本时请自行保留原 ZIP。
导入/更新不会回收任务记录或当前包。临时凭据继续按既有规则清理，任务工作目录沿用原有报告保留策略。

Server 与 Worker 需使用匹配构建。协议仍为 2，增加可选 `adapterPackage`/`adapterSnapshot`；
元数据握手声明 `adapterPackageVersion=1`。缺少快照支持的 Worker 会在新任务启动前被拒绝。
无此快照的旧任务保留已有内置回退逻辑，不自动获得新证据。

## 验证范围

自动化使用合成资源、夹具驱动/Worker 和隔离 H2 管理存储，覆盖 REST 导入→驱动上传校验→
连接→选定任务创建，显式更新、排队快照、Worker 读取冻结资源、关闭重开持久化，非法/缺失/
越界/不支持/超大包、来源冒充、内置保护和引用归档。
浏览器夹具覆盖导入、预选连接和中英文/窄屏展示。夹具成功不是实库兼容性验证。
本轮未访问业务、共享或历史数据库。

Web 创建任务时提交核对过的适配包指纹；库内容并发变化会返回 `ADAPTER_CHANGED`，需要刷新复核。
旧 API 客户端可省略此可选检查。

开发检查的可复制命令见[英文指南的 Development checks](web-adapters.md#development-checks)：
Java 针对性检查共 49 项，包含内置/旧报告、重开持久化、Worker 配置、导入包排队执行及原驱动
Provider 快照流程；Python 合同通过 `scripts/run_contract_tests.py` 运行，浏览器夹具通过
`PLAYWRIGHT_CHANNEL=chrome npm run test:e2e -- adapter-import.spec.ts` 运行。
需允许本地子进程与临时回环监听；已有开发服务占用 5173 时应采用隔离配置与空闲端口。
检查结束清理本轮资源，保留夹具截图和原有服务。IDEA 原生格式检查须单独报告，实例锁不允许时不得绕过。

第三阶段补齐 PostgreSQL 迁移缺口：在本轮独立临时 PostgreSQL 16 中通过空库 V1–V10、
合成旧数据 V8→V9→V10、旧记录读取及应用重启后的适配包/任务快照恢复。
H2 与浏览器夹具证据范围仍单独区分。见 [SQL 包验证范围](sql-packages.zh-CN.md#本阶段验证范围)。

后续[真实集成验收（2026-10-20）](acceptance-2026-10-20.zh-CN.md)使用真实 PostgreSQL 平台/目标与
Worker，通过浏览器验证上传、导入、连接、SQL 执行、排队版本冻结和正常重启恢复。自定义适配包
验证的是接入机制，不是自研产品本身的兼容性；上述初始 H2/夹具测试仍单独解释。
