# 文档导航

简体中文 / [English](README.en.md)

[项目首页](../README.md)

本文是中文优先入口。指南保留 `xxx.zh-CN.md` 中文、`xxx.md` 英文命名；根首页使用 `README.md` 中文和 `README.en.md` 英文。

按任务选择阅读路径，无需依次阅读全部文档。首次使用先选部署方式，再按使用指南完成一次测试。

| 任务 | 推荐入口 | 阅读内容 |
|---|---|---|
| 第一次使用 | [使用指南](usage.zh-CN.md) | 上传驱动 → 配置连接 → 创建任务 → 阅读结果 → 检查清理 |
| 部署运维 | [部署指南](deployment.zh-CN.md) | 选择运行方式、配置、备份恢复、升级与排错 |
| 开发与贡献 | [开发指南](development.zh-CN.md) | 本地启动、架构、适配与用例、API/Worker 协议、代码规范和验证 |
| 理解 JDBC 结果 | [JDBC 判定参考](jdbc-capability-contracts.zh-CN.md) | 执行状态、契约、套件额外要求、环境与证据 |
| 驱动升级回归 | [CLI A/B 回归](internal-regression.zh-CN.md) | A 报告 → 选择基线 → B 报告 → 比较与 CI |
| 核对数据库边界 | [数据库验证记录与限制](database-validation.zh-CN.md) | 历史组合、当前限制、定向复测和证据入口 |

CLI 配置见[示例](../examples/README.zh-CN.md)。模块职责见 [Server](../jdbc-test-server/README.zh-CN.md) 和 [Worker](../jdbc-test-worker/README.zh-CN.md)。

验证记录仅描述已有证据及缺口，不是厂商认证或跨数据库排名。秘密、原始本地 QA 和私有驱动不随文档分发。
