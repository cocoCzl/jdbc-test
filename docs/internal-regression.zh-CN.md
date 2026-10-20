# 内部驱动升级回归

[English](internal-regression.md) | 简体中文

内部基线是用户明确选定的 CLI 报告快照，local/private、candidate 适配包均可使用。
它不改变 `adapter.trust`、`formal_eligible`、已知差异或原报告，不构成 JDBC 认证或项目维护者背书。
默认 `compare --scope formal` 继续要求正式报告资格；两种范围现在都要求完整、版本化的比较证据。
历史 JSON 保留原样，缺少证据时明确拒绝比较，需要用当前 CLI 重跑。

## 驱动 A → 选择基线 → 驱动 B → 比较

使用 JDK 21、Maven Wrapper 和明确授权的独立测试数据库/schema。已有本地适配包使用
`init --adapter /absolute/path/to/adapter`；需要创建时使用 `init-custom`，参见[示例](../examples/README.zh-CN.md)。
驱动和含连接信息的配置留在本地，不提交 Git。

在生成的配置中合并以下字段，保留已有连接、密码环境变量、适配包、命名空间授权和用例选择：

```json
{
  "regression": {
    "database_identity": "isolated-vendor-test-instance",
    "server_build": "exact-server-build",
    "database_mode": "native",
    "environment_key_env": "JDBC_TEST_REGRESSION_KEY"
  },
  "db": {
    "driver": {"kind": "local", "path": "/private/drivers/A"}
  }
}
```

`database_identity`、`server_build`、`database_mode` 是操作者明确确认的信息，应填写实际实例标识、
精确服务端构建和兼容模式；无兼容模式也显式填写 `native`。CLI 不会声称已独立核实物理实例身份或服务端设置，
因此结论仅在这些确认成立的前提下有效。同时会检查真实 JDBC 产品/版本和连接配置指纹。

只生成一次随机比较密钥，A/B 使用同一密钥，CI 通过既有密钥机制保存并注入：

```sh
export JDBC_TEST_REGRESSION_KEY="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
# 按现有秘密管理方式提供配置中 password_env 指向的密码环境变量。
python3 -B scripts/runner.py run configs/config.local.json
python3 -B scripts/runner.py baseline create \
  --report report/private-db_A/report.json --output report/internal-baseline.json
# 仅修改 db.driver 指向 B，或替换该目录中的驱动/依赖 JAR。
python3 -B scripts/runner.py run configs/config.local.json
python3 -B scripts/runner.py compare --scope internal \
  --baseline report/internal-baseline.json --current report/private-db_B/report.json \
  --output report/driver-upgrade.json
```

将示例报告路径替换为 `run` 打印的真实路径。`baseline create` 使用独占创建，不覆盖已有文件；
可通过不同文件名保存和选择多个基线。快照包含完整原报告及 SHA-256，后续篡改会阻断比较；这不是数字签名。
密钥至少 32 个 UTF-8 字节，应使用随机生成值。密钥丢失或轮换后需要重建基线。
比较证据仅保留密钥 ID 和配置 HMAC，不保存密钥、连接 URL、用户名和连接属性值。
密码本身不参与配置指纹，可以轮换；权限一致性仍由操作者保证，调用失败会被保留。
分享本地 `diagnostics.log` 前需审查其内容。

`run` 发现用例失败时返回 1，仍生成报告。已完整执行、但存在既有契约失败的结果可以明确选为基线。
准备、清理、未执行问题或未评估的方案要求会阻止选择基线。
CI 应保留报告，并在运行返回 1 后继续执行比较，以比较退出码作为回归门禁；
如果还要求所有用例通过，应额外检查运行退出码。

## 可比性策略

| 维度 | 驱动升级比较规则 |
|---|---|
| 驱动名称、版本及驱动/依赖 JAR | 允许变化，保留前后每个制品名称与 SHA-256 |
| Suite/断言/执行器源码、目录、POM、CLI 定义 | 内容指纹必须一致，包含未提交源码变化 |
| 适配包清单、版本/revision、能力、已知差异及 SQL/连接池资源 | 内容指纹必须一致 |
| 实际选择的稳定用例 ID 集合 | 完全一致；增删必须另建基线 |
| 数据库实例标识、服务端构建、兼容模式 | 必须显式填写且一致；依赖操作者确认，结论有限 |
| 实测 JDBC 数据库产品与版本 | 必须存在且一致，配置回退值不能代替实测证据 |
| JDK runtime/vendor、OS/version/架构、实测时区/locale/字符集及框架依赖 JAR | 必须存在且一致 |
| 端点/用户、属性/Provider、驱动类、直连/连接池、超时、执行/方案配置及 locale/JVM 环境选项 | HMAC 必须一致 |
| 自动创建的命名空间名称、对象前缀 | 可变化，但生命周期策略必须一致 |
| 已有命名空间 | 名称也必须一致，仍需满足独立测试范围及授权要求 |

本地驱动目录是明确的驱动依赖包，其中所有 JAR 都记录指纹并允许变化。
Maven 坐标选择该制品 JAR，多 JAR 驱动请使用本地目录；框架依赖变化会阻断比较。
缺失/冲突的目标信息、未知状态、无法解释的 Maven 失败或指纹缺失均给出具体原因，不把“两边都缺失”当作相同。
运行期间源码或驱动制品变化会被拒绝。SQL 指纹保守地覆盖已解析资源根目录，因此无关 SQL 修改也可能要求另建基线。

## 输出与退出码

比较 JSON 包含比较目的、允许变化维度、逐项可比性检查、具体错误、新增失败、修复、既有未解决失败、覆盖变化、
功能支持退化、执行/准备问题、方案要求评估，以及两份报告的来源和关键指纹。
`known_difference` 仍作为既有失败展示。先前可用的功能变为合法不支持，也属于支持能力退化，
但不会被改称为 JDBC 契约违规。

| 退出码 | 含义 |
|---|---|
| 0 | 可比较、执行完整、无新增回归；仍可能有既有失败或合法不支持 |
| 1 | 新增失败、先前支持能力丢失，或新增未满足方案要求 |
| 2 | 不可比较、输入错误、证据/版本不足、覆盖变化，或执行/准备/清理问题 |

`no_new_regressions=true` 不等于 `all_selected_passed=true`。退出 0 后仍需查看既有失败和方案评估。
选中用例未执行、跳过或准备失败，即使基线也存在同样问题，也不能误报回归成功。
未选用例保留 `USER_EXCLUDED`/`NOT_APPLICABLE`，不会算作已执行，也不导致单次评估失败。
比较过程不连接数据库、不重写原报告。

## 本阶段边界

内部基线管理仅在 CLI 实现。Suite 沿用 Worker 已消费的 JUnit report entries 保存独立证据，
Core/Worker/Server/Web 结果模型和来源含义不变；共享目录已明确布尔元数据是声明，
仅本次审查的保存点创建调用允许明确不支持。参见[能力与契约依据](jdbc-capability-contracts.md)。
本阶段未实现 SQL 功能、产品兼容或厂商扩展评分、独立服务端身份核验、远端/签名基线存储和 Web 基线页面。

比较密钥应保存在现有 CI 密钥库或私有密钥管理工具中，仅通过指定环境变量注入。
不要提交到 Git、写入适配包/报告 JSON、回显或在开启 shell 跟踪时运行上述命令。
它用于配置 HMAC，不是数据库密码、报告签名或认证令牌。报告只含 SHA-256 密钥 ID 和配置摘要，
不含原密钥。A/B 及后续 CI 必须保留并使用同一密钥；丢失或更换后配置指纹不可比，需要重新运行并
选择新基线。删除密钥不改变旧报告内容，但无法再生成匹配的新比较证据。
第一阶段仅在临时 PostgreSQL 16、驱动 42.7.2→42.7.5 上各执行六个用例（其余 264 排除）：
`database_metadata.supports_transactions`、`database_metadata.supports_batch_updates`、
`database_metadata.supports_savepoints`、`savepoint.set_savepoint`、`connection.rollback`、
`statement.execute_batch`。A/B 和内部比较均返回 0。未验证完整 JDBC、全部 SQL/清理路径、
其他连接池/模式/平台及 MySQL、Oracle、Kingbase、私有驱动；之后的输出控制字符转义修复只做了 mock
Suite 测试，未重新实测。参见[契约说明](jdbc-capability-contracts.md)。

CLI 会从 Maven/驱动子进程环境移除当前比较密钥及默认密钥变量；保存报告和诊断日志前也会
脱敏意外回显的当前密钥，包括需要 JSON 转义的值。
