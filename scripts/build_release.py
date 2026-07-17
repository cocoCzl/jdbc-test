#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import re
import zipfile
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
EXCLUDED_PARTS = {".git", ".idea", ".vscode", "target", "report", "configs", "__pycache__", ".venv"}


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
        for path in sorted(PROJECT_ROOT.rglob("*")):
            relative = path.relative_to(PROJECT_ROOT)
            if path == output or output in path.parents:
                continue
            if path.is_dir() or EXCLUDED_PARTS.intersection(relative.parts):
                continue
            if path.suffix == ".jar" or path.name.endswith(".local.json"):
                continue
            bundle.write(path, f"{prefix}/{relative.as_posix()}")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    checksum = archive.with_suffix(archive.suffix + ".sha256")
    checksum.write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
    return archive, checksum


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
