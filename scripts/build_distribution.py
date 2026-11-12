#!/usr/bin/env python3
"""Build a ready-to-run Web/Server/Worker bundle, separate from the CLI source ZIP."""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from documentation import repository_links


PROJECT_ROOT = Path(__file__).resolve().parent.parent


def project_version() -> str:
    document = ET.parse(PROJECT_ROOT / "pom.xml")
    version = document.findtext("{http://maven.apache.org/POM/4.0.0}version")
    if not version:
        raise RuntimeError("Cannot read project version")
    return version


def build(output: Path) -> tuple[Path, Path, Path]:
    version = project_version()
    output = output.resolve()
    name = f"jdbc-test-server-{version.replace('-SNAPSHOT', '')}"
    directory = output / name
    if directory.exists():
        raise RuntimeError(f"{directory} already exists. Choose another --output directory; existing configuration/data will not be overwritten.")
    artifacts = {
        "server/jdbc-test-server.jar": PROJECT_ROOT / "jdbc-test-server" / "target" / f"jdbc-test-server-{version}-exec.jar",
        "worker/jdbc-test-worker.jar": PROJECT_ROOT / "jdbc-test-worker" / "target" / f"jdbc-test-worker-{version}-all.jar",
    }
    web = PROJECT_ROOT / "jdbc-test-web" / "dist"
    for artifact in [*artifacts.values(), web / "index.html"]:
        if not artifact.is_file():
            raise RuntimeError(f"Missing build artifact: {artifact}. Build with ./jdbc-test build.")

    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".jdbc-test-server-", dir=output) as temporary:
        staging = Path(temporary) / name
        for relative, artifact in artifacts.items():
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(artifact, destination)
        shutil.copytree(web, staging / "web", ignore=shutil.ignore_patterns(".env", ".env.*", "*.log", "*.input", "*.local.*"))
        (staging / "config").mkdir()
        (staging / ".gitignore").write_text(
            "config/application.properties\nconfig/master-key\ndata/\n*.env\n*.input\n*.log\n.docker-launch.lock/\n.jdbc-test-init.lock/\n.config-*.tmp\n.config-private-*/\n",
            encoding="utf-8",
        )
        shutil.copy2(PROJECT_ROOT / "deploy/native/application.properties.example", staging / "config")
        for launcher in ("start.sh", "start.ps1"):
            shutil.copy2(PROJECT_ROOT / "deploy/native" / launcher, staging / launcher)
            if launcher.endswith(".sh"):
                (staging / launcher).chmod(0o755)
        for launcher in ("jdbc-test", "jdbc-test.ps1"):
            shutil.copy2(PROJECT_ROOT / launcher, staging / launcher)
        (staging / "jdbc-test").chmod(0o755)
        shutil.copy2(PROJECT_ROOT / ".env.example", staging / ".env.example")
        shutil.copy2(PROJECT_ROOT / "LICENSE", staging / "LICENSE")
        (staging / "docs").mkdir()
        # Runtime reading path only. Development/CLI sources and adapter evidence stay online.
        documents = (
            "README.md", "README.en.md",
            "deployment.md", "deployment.zh-CN.md", "usage.md", "usage.zh-CN.md",
            "jdbc-capability-contracts.md", "jdbc-capability-contracts.zh-CN.md",
            "database-validation.md", "database-validation.zh-CN.md",
        )
        included = {path.relative_to(staging).as_posix()
                    for path in staging.rglob("*") if path.is_file()}
        included.update(f"docs/{document}" for document in documents)
        included.update({"README.md", "README.en.md"})
        for document in documents:
            content = (PROJECT_ROOT / "docs" / document).read_text(encoding="utf-8")
            content = repository_links(content, f"docs/{document}", included)
            (staging / "docs" / document).write_text(content, encoding="utf-8")
        for language, filename in (("zh", "README.md"), ("en", "README.en.md")):
            (staging / filename).write_text(bundle_readme(language), encoding="utf-8")
        shutil.move(str(staging), str(directory))

    archive = output / f"{name}.tar.gz"

    def archive_filter(info: tarfile.TarInfo) -> tarfile.TarInfo:
        # Windows does not preserve POSIX execute bits through chmod/stat. Set
        # the launcher mode in the archive explicitly so Unix extraction is
        # runnable regardless of the build host.
        if info.name in {f"{name}/{launcher}" for launcher in ("jdbc-test", "start.sh")}:
            info.mode = 0o755
        return info

    with tarfile.open(archive, "w:gz") as bundle:
        bundle.add(directory, arcname=name, filter=archive_filter)
    windows_archive = output / f"{name}.zip"
    with zipfile.ZipFile(windows_archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                bundle.write(path, f"{name}/{path.relative_to(directory).as_posix()}")
    for path in (archive, windows_archive):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        path.with_name(path.name + ".sha256").write_text(f"{digest}  {path.name}\n", encoding="utf-8")
    return directory, archive, windows_archive


def bundle_readme(language: str) -> str:
    if language == "zh":
        return """# JDBC Test 部署包

简体中文 / [English](README.en.md)

在真实数据库上验证 JDBC 驱动接口行为的平台。本包用于运行已构建的网站。
需要 Java 21、Shell/PowerShell、空闲 8080 端口和具备迁移权限的专用平台 PostgreSQL；无需 Node/npm/Maven/Python。

在**解压后的本部署包根目录**操作：

1. 仅不存在时复制 `.env.example` 为 `.env`；Linux/macOS 用 `umask 077` 后执行 `cp -n .env.example .env`。
   PowerShell 用 `if (-not (Test-Path '.env')) { Copy-Item .env.example .env }`。
2. 编辑实际 `.env` 中的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。
   URL 示例 `jdbc:postgresql://localhost:5432/jdbc_test`，替换实际主机/端口/库名，URL 不含凭据。
3. 执行 `./jdbc-test start`；Windows 为 `./jdbc-test.ps1 start`。
   全新空平台检查通过后自动保存独立 Master Key；已有状态必须保留原 Key。
4. 启动会执行平台迁移，打开打印的就绪地址，默认 `http://127.0.0.1:8080`。
   Ctrl+C 停止本次进程并保留配置/数据；下次复用原 `.env`、Key 和存储执行同一命令。

`.env` 不可 source/eval：双引号采用 JSON 转义，单引号是字面值，未加引号值保留空格/#/$ 且不展开。
备份必须包括平台 PG、应用文件与原配置/Key。旧 properties/环境配置仍由同一 start 读取。
升级不覆盖已有安装，不让新旧 Server 并行写入；V15 需要停机窗口，见[升级](docs/deployment.zh-CN.md#upgrades)。

下一步：[使用指南](docs/usage.zh-CN.md) · [部署与恢复](docs/deployment.zh-CN.md#native) · [文档导航](docs/README.md)。
目标库另行配置，测试执行 DDL/DML，需要专用授权范围；第三方目标驱动不随包分发。
网站无内置认证，必须控制访问；隔离 Worker 不能保证恶意驱动安全。结果与清理需分别检查。
[Apache License 2.0](LICENSE)。
"""
    return """# JDBC Test deployment bundle

[简体中文](README.md) / English

A platform for validating JDBC driver interface behavior against real databases. This bundle runs the built website.
Requires Java 21, shell/PowerShell, free port 8080 and dedicated platform PostgreSQL with migration permissions.
No Node/npm/Maven/Python is required.

Work in **this extracted bundle root**:

1. Copy `.env.example` to `.env` only if absent; Linux/macOS: `umask 077` then `cp -n .env.example .env`.
   PowerShell: `if (-not (Test-Path '.env')) { Copy-Item .env.example .env }`.
2. Edit the actual `.env`: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
   Example URL: `jdbc:postgresql://localhost:5432/jdbc_test`; replace host/port/database, no credentials in URL.
3. Run `./jdbc-test start` (Windows: `./jdbc-test.ps1 start`). An independent Master Key is saved
   only after new empty-platform checks; existing state requires the original Key.
4. Startup applies platform migrations. Open the printed ready URL, normally `http://127.0.0.1:8080`.
   Ctrl+C stops owned processes and retains config/data. Reuse the original `.env`, Key and storage next time.

Never source/eval `.env`: double quotes use JSON escapes, single quotes are literal; unquoted values retain spaces/#/$ without expansion.
Back up platform PG, application files and original configuration/Key together. Existing properties/environment configurations use the same start.
Do not overwrite an installation or run old/new Servers concurrently. V15 requires downtime; see [upgrades](docs/deployment.md#upgrades).

Next: [Usage](docs/usage.md) · [Deployment and recovery](docs/deployment.md#native) · [Documentation](docs/README.en.md).
Configure target databases separately. Tests execute DDL/DML and need an authorized dedicated scope; third-party target drivers are not included.
The site has no built-in authentication; restrict access. Worker isolation cannot guarantee safety from malicious drivers.
Inspect results and cleanup separately. [Apache License 2.0](LICENSE).
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    try:
        for path in build(args.output):
            print(f"[created] {path}")
    except RuntimeError as error:
        parser.exit(1, f"{error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
