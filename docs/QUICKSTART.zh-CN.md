# 快速开始

1. 安装 JDK 21、Maven 3.8+、Python 3.9+。
2. 运行 `python3 scripts/runner.py adapters` 查看适配包。
3. 使用 `runner.py init` 生成本地 JSON 配置；不要把密码写入配置。
4. 将配置中的测试账号指向专用数据库环境。Oracle 必须使用专用测试账号/schema。
5. 设置配置指定的密码环境变量，执行 `runner.py run`。
6. 查看输出目录中的 `report.json`、`report.md`、`report.html`；`diagnostics.log` 只用于本地排障。
7. 使用者认可一份正式报告后，可自行保存为基线，并用 `runner.py compare` 接入 CI。

本地适配包可用目录路径代替内置适配包 ID。未审核适配包的报告会标记为本地/试验性，不能作为正式基线。
