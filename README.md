# JDBC Driver Compatibility Test Framework

[English](README.en.md) | 中文

这是一个面向数据库厂商和 JDBC 驱动维护者的 JDBC 4.3 兼容性与回归测试框架。它连接使用者提供的数据库实例，在专用测试命名空间中运行版本化测试集，并生成脱敏、可比较的 JSON/Markdown/HTML 报告。

它不是官方 JDBC 认证工具，也不用于业务 SQL、性能压测、数据库迁移或厂商专有 API 认证。

## 已有适配包

- 官方且已有真实验证证据：PostgreSQL、MySQL、Oracle
- 已实现、等待真实实例验证：GaussDB、SQL Server
- 任意新数据库可通过本地声明式适配包接入，无需修改 Java 核心代码

## 环境

- JDK 21
- Maven 3.8+
- Python 3.9+（核心命令只使用标准库）
- 使用者提供的目标数据库实例和固定版本 JDBC 驱动

## 三步开始

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

详细步骤见 [中文快速开始](docs/QUICKSTART.zh-CN.md)。

## 安全边界

- 密码只从环境变量读取，本地配置默认被 Git 忽略。
- 未显式设置 `destructive_consent=true` 时不执行数据库变更。
- PostgreSQL/MySQL/GaussDB 使用随机专用命名空间；Oracle 需使用专用测试账号/schema。
- 可共享报告默认不包含凭据、主机、端口、库名、用户名、完整 SQL 或堆栈。
- 完整排障信息只保存在本地 `diagnostics.log`，分享前请人工检查。

## 报告与 CI

`report.json` 是权威格式，Markdown/HTML 由它派生。新增兼容性失败、环境错误、预检失败、适配包不完整或清理失败返回非零退出码；明确的能力跳过和审核后的已知差异不阻断。

## 构建发布包

```bash
python3 scripts/build_release.py --output dist
```

生成的 ZIP 不包含第三方 JDBC 驱动，并附带 SHA-256 文件。

## 许可证

Apache License 2.0。
