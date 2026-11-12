# Documentation

[简体中文](README.md) / English

[Project home](../README.en.md)

Guides use `xxx.md` for English and `xxx.zh-CN.md` for Chinese. The root home uses `README.md` for Chinese and `README.en.md` for English.

Choose by task; there is no need to read everything in order. For a first test, choose a deployment mode, then follow Usage.

| Task | Guide | Contents |
|---|---|---|
| First use | [Usage](usage.md) | Upload → connect → create/run → results → cleanup |
| Deploy and operate | [Deployment](deployment.md) | Choose a mode, configure, back up/restore, upgrade and troubleshoot |
| Develop and contribute | [Development](development.md) | Local startup, architecture, adapters/scenarios, API/Worker, style and checks |
| Interpret JDBC results | [JDBC reference](jdbc-capability-contracts.md) | Execution, contracts, extra suite requirements, environment and evidence |
| Upgrade a driver | [CLI A/B regression](internal-regression.md) | A report → baseline → B report → comparison and CI |
| Check database scope | [Database validation and limits](database-validation.md) | Recorded combinations, current limits, targeted retests and evidence |

See [CLI examples](../examples/README.md), and module entries for [Server](../jdbc-test-server/README.md) and [Worker](../jdbc-test-worker/README.md).

Validation records describe retained evidence and gaps, not vendor certification or a cross-database ranking. Secrets, raw local QA and private drivers are not distributed.
