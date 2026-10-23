# 标准 JDBC 测试示例

[English](README.md) | 简体中文

Web 用户只需上传驱动、配置连接并选择内置标准测试；无需制作适配包或 SQL 包。
以下 CLI 示例均在源码根目录运行，仅用于专用测试库，凭据、驱动和生成配置不得提交。

```sh
python3 -B scripts/runner.py init --adapter postgresql \
  --url jdbc:postgresql://localhost:5432/test_db --username jdbc_tester \
  --namespace owned_schema --password-env DB_PASSWORD --consent \
  --driver-dir local-drivers/postgresql --connection-mode driver_manager
python3 -B scripts/runner.py run configs/config.local.json
```

执行前通过秘密管理方式设置 `DB_PASSWORD`。`--adapter` 仅接受 `adapters/catalog.json` 内置 ID。
MySQL 使用 `--adapter mysql` 和专用库；Oracle 使用 `--adapter oracle` 和专用账号的 schema。
命名空间授权、对象归属和清理规则见[使用指南](../docs/usage.zh-CN.md)。

自研数据库可显式复用已有方言；例如：

```sh
python3 -B scripts/runner.py init --adapter postgresql --compatible-dialect \
  --driver-class com.example.jdbc.Driver --driver-dir local-drivers/vendor \
  --url jdbc:vendor://localhost:9000/test_db --username jdbc_tester \
  --namespace owned_schema --password-env DB_PASSWORD --consent \
  --connection-mode driver_manager --output configs/vendor.local.json
```

此选项不检查另一产品的名称／发行版本范围，实际身份仍由 JDBC 元数据独立记录；保留原能力声明、标准断言和命名空间／准备校验。
结果来源为 local，不提升正式资格；复用方言并不保证准备成功或兼容。差异需要改源码时，维护
`adapters/<id>/adapter.json`、`ddl`、`dml`、`pool` 及相关代码，重新构建部署。

`--driver-dir` 可为一个 JAR 或含主驱动和全部依赖的目录，每个 JAR 都参与指纹。
`--connection-mode` 支持 `driver_manager`／`hikari`；普通连接属性用重复的 `--property KEY=VALUE`，
敏感属性用 `--property-env KEY=ENV_NAME`。`user`／`password` 使用独立参数，URL 不得嵌入凭据。

[内部驱动 A/B 回归](../docs/internal-regression.zh-CN.md)沿用同一配置与测试选择，只更换驱动制品。
