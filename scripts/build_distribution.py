#!/usr/bin/env python3
"""Build a ready-to-run Web/Server/Worker bundle, separate from the CLI source ZIP."""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
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


def build(output: Path, *, skip_build: bool = False) -> tuple[Path, Path, Path]:
    version = project_version()
    output = output.resolve()
    name = f"jdbc-test-server-{version.replace('-SNAPSHOT', '')}"
    directory = output / name
    if directory.exists():
        raise RuntimeError(f"{directory} already exists. Choose another --output directory; existing configuration/data will not be overwritten.")
    if not skip_build:
        wrapper = PROJECT_ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        subprocess.run([str(wrapper), "-B", "-DskipTests", "package"], cwd=PROJECT_ROOT, check=True)
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        if not npm:
            raise RuntimeError("Node >=22.12.0 and npm are required to build the frontend")
        subprocess.run([npm, "ci"], cwd=PROJECT_ROOT / "jdbc-test-web", check=True)
        subprocess.run([npm, "run", "build"], cwd=PROJECT_ROOT / "jdbc-test-web", check=True)

    artifacts = {
        "server/jdbc-test-server.jar": PROJECT_ROOT / "jdbc-test-server" / "target" / f"jdbc-test-server-{version}-exec.jar",
        "worker/jdbc-test-worker.jar": PROJECT_ROOT / "jdbc-test-worker" / "target" / f"jdbc-test-worker-{version}-all.jar",
    }
    web = PROJECT_ROOT / "jdbc-test-web" / "dist"
    for artifact in [*artifacts.values(), web / "index.html"]:
        if not artifact.is_file():
            raise RuntimeError(f"Missing build artifact: {artifact}. Run without --skip-build.")

    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".jdbc-test-server-", dir=output) as temporary:
        staging = Path(temporary) / name
        for relative, artifact in artifacts.items():
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(artifact, destination)
        shutil.copytree(web, staging / "web")
        (staging / "config").mkdir()
        shutil.copy2(PROJECT_ROOT / "deploy/native/application.properties.example", staging / "config")
        for launcher in ("start.sh", "start.ps1"):
            shutil.copy2(PROJECT_ROOT / "deploy/native" / launcher, staging / launcher)
        (staging / "start.sh").chmod(0o755)
        shutil.copy2(PROJECT_ROOT / "LICENSE", staging / "LICENSE")
        (staging / "docs").mkdir()
        for document in ("deployment.md", "deployment.zh-CN.md", "execution-safety.md", "execution-safety.zh-CN.md"):
            content = (PROJECT_ROOT / "docs" / document).read_text(encoding="utf-8")
            # Development sources are available online, outside the runtime bundle.
            for guide in ("development.md", "development.zh-CN.md"):
                content = content.replace(f"({guide}#", f"(https://github.com/cocoCzl/jdbc-test/blob/master/docs/{guide}#")
            (staging / "docs" / document).write_text(content, encoding="utf-8")
        (staging / "README.md").write_text(
            "# JDBC Test Server\n\n"
            "Requires Java 21 and an existing PostgreSQL management database.\n\n"
            "1. Copy `config/application.properties.example` to `config/application.properties`.\n"
            "2. Set the PostgreSQL URL/user/password and a persistent 32-byte Base64 master key.\n"
            "3. Run `sh start.sh` (Linux/macOS) or `./start.ps1` (PowerShell); open http://localhost:8080.\n\n"
            "Full deployment, backup and upgrade instructions: [English](docs/deployment.md#native) | "
            "[简体中文](docs/deployment.zh-CN.md#native)。\n\n"
            "Data is stored in `data/`. Stop with Ctrl+C. Keep configuration, data and database backups together.\n",
            encoding="utf-8",
        )
        shutil.move(str(staging), str(directory))

    archive = output / f"{name}.tar.gz"
    with tarfile.open(archive, "w:gz") as bundle:
        bundle.add(directory, arcname=name)
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
    parser.add_argument("--skip-build", action="store_true", help="Package artifacts already built from this revision (CI)")
    args = parser.parse_args()
    try:
        for path in build(args.output, skip_build=args.skip_build):
            print(f"[created] {path}")
    except (RuntimeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
