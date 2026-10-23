# jdbc-test-worker

[English](README.md) | 简体中文

单次运行的 JVM 制品，加载外部 JDBC 驱动、执行兼容性测试集，并输出 NDJSON 事件与 JSON 报告。也支持目录描述、驱动验证和连接探测。

见 [Worker 协议](../docs/development.zh-CN.md#worker-protocol)与[使用指南](../docs/usage.zh-CN.md)。Server 和 Worker 制品必须配套部署。
