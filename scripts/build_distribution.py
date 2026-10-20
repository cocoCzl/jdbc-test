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
        for document in ("deployment.md", "deployment.zh-CN.md", "execution-safety.md", "execution-safety.zh-CN.md", "sql-packages.md", "sql-packages.zh-CN.md"):
            content = (PROJECT_ROOT / "docs" / document).read_text(encoding="utf-8")
            # Development sources are available online, outside the runtime bundle.
            for guide in ("development.md", "development.zh-CN.md"):
                content = content.replace(f"({guide}#", f"(https://github.com/cocoCzl/jdbc-test/blob/master/docs/{guide}#")
            (staging / "docs" / document).write_text(content, encoding="utf-8")
        (staging / "examples/sql").mkdir(parents=True)
        for example in ("postgresql-basics.json", "mysql-basics.json"):
            shutil.copy2(PROJECT_ROOT / "examples/sql" / example, staging / "examples/sql")
        (staging / "README.md").write_text(
            "# JDBC Test Server\n\n"
            "Runtime: Java 21 + shell/PowerShell + a prepared dedicated platform PostgreSQL. No Node/npm/Maven/Python.\n\n"
            "1. Copy `.env.example` to `.env` in this extracted bundle directory only when .env is absent (Linux/macOS: `umask 077; cp -n .env.example .env`).\n"
            "2. Edit that actual `.env` locally: fill SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD.\n"
            "URL example: jdbc:postgresql://localhost:5432/jdbc_test (replace host/port/database; no credentials in URL).\n"
            "3. Run `./jdbc-test start` (PowerShell: `./jdbc-test.ps1 start`); the entry protects the local file and checks configuration.\n"
            "After authenticating and confirming NEW empty platform, the tool saves a persistent independent Master Key in .env; existing data requires the original Key.\n"
            "Startup checks configuration and ports, runs migrations and prints the ready website URL. Ctrl+C stops this launch; data/configuration are retained.\n"
            "Next time run `./jdbc-test start` again, reusing the original .env and Key. Do not repeat --new-install.\n\n"
            "在本部署包目录复制 .env.example 为 .env（不覆盖），编辑实际 .env 三项，再执行 ./jdbc-test start。全新空平台确认 NEW 后自动保存 Key；恢复必须使用原 Key。\n\n"
            "Existing properties/environment configurations are read by the same unified start; historical start scripts only forward to it. Full guide: [English](docs/deployment.md#native) | [简体中文](docs/deployment.zh-CN.md#native).\n"
            "Never source/eval .env; quoted strings use JSON escapes, single quotes are literal, unquoted values retain spaces/#/$ without expansion. Keep .env private and backed up with PG/data.\n",
            encoding="utf-8",
        )
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
