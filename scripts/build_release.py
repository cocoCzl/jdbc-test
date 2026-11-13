#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
import zipfile
from pathlib import Path

from documentation import repository_links


PROJECT_ROOT = Path(__file__).resolve().parent.parent
EXCLUDED_PARTS = {".git", ".idea", ".vscode", "target", "report", "configs", "__pycache__", ".venv"}
INCLUDED_PATHS = (
    ".gitignore",
    "Dockerfile.cli",
    "LICENSE",
    "README.en.md",
    "README.zh-CN.md",
    "README.md",
    "adapters",
    "compatibility",
    "ddl",
    "dml",
    "examples",
    "pom.xml",
    "pool",
    "scripts",
    "jdbc-test-core",
    "jdbc-test-suite",
    "jdbc-test-worker",
    "jdbc-test-server",
    "mvnw",
    "mvnw.cmd",
    ".mvn",
)


def project_version() -> str:
    text = (PROJECT_ROOT / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<artifactId>jdbc-test</artifactId>\s*<version>([^<]+)</version>", text)
    if not match:
        raise RuntimeError("Cannot read project version")
    return match.group(1).replace("-SNAPSHOT", "")


def build(output: Path) -> tuple[Path, Path]:
    version = project_version()
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"jdbc-test-{version}.zip"
    prefix = f"jdbc-test-{version}"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        files = release_files()
        included = {path.relative_to(PROJECT_ROOT).as_posix() for path in files}
        for path in files:
            relative = path.relative_to(PROJECT_ROOT)
            destination = f"{prefix}/{relative.as_posix()}"
            if path.suffix == ".md":
                content = repository_links(path.read_text(encoding="utf-8"), relative.as_posix(), included)
                bundle.writestr(destination, content)
            else:
                bundle.write(path, destination)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    checksum = archive.with_suffix(archive.suffix + ".sha256")
    checksum.write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
    return archive, checksum


def release_files() -> list[Path]:
    files: list[Path] = []
    for relative in INCLUDED_PATHS:
        root = PROJECT_ROOT / relative
        if root.is_file():
            files.append(root)
        elif root.is_dir():
            files.extend(path for path in root.rglob("*") if path.is_file())
    return sorted(
        path for path in files
        if not EXCLUDED_PARTS.intersection(path.relative_to(PROJECT_ROOT).parts)
        and path.name not in {".DS_Store", "Thumbs.db"}
        and path.suffix != ".jar"
        and not path.name.endswith(".local.json")
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Build driver-free JDBC Test release archive")
    parser.add_argument("--output", default="dist")
    args = parser.parse_args()
    archive, checksum = build(PROJECT_ROOT / args.output)
    print(f"[生成] {archive}")
    print(f"[生成] {checksum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
