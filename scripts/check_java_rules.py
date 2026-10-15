#!/usr/bin/env python3
"""Run three syntax rules with a reviewed, occurrence-specific baseline; never rewrite sources."""
from __future__ import annotations

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


REPOSITORY = Path(__file__).resolve().parent.parent
RULES = {"wildcard-import", "empty-catch", "suppress-all"}
ROOTS = tuple(f"jdbc-test-{module}/src/{scope}/java"
              for module in ("core", "suite", "worker", "server")
              for scope in ("main", "test"))
CHECKER = "scripts/CheckJavaRules.java"


def source_files(root: Path) -> list[Path]:
    def walk_error(error):
        raise error

    files = []
    for relative in ROOTS:
        directory = root / relative
        if not directory.is_dir() or any(p.is_symlink() for p in
                                        (directory, *directory.parents)):
            raise ValueError(f"Missing or symlinked scan root: {directory}")
        selected = []
        for current, directories, names in os.walk(directory, onerror=walk_error):
            if any((Path(current) / name).is_symlink() for name in directories + names):
                raise ValueError(f"Symlink in scan root: {current}")
            selected.extend(Path(current) / name for name in names if name.endswith(".java"))
        if not selected:
            raise ValueError(f"No Java sources in scan root: {directory}")
        files.extend(selected)
    checker = root / CHECKER
    if not checker.is_file() or any(p.is_symlink() for p in (checker, *checker.parents)):
        raise ValueError(f"Missing or symlinked checker source: {checker}")
    return sorted([*files, checker])


def read_baseline(path: Path) -> Counter:
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError(f"Symlinked Java rule baseline: {path}")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate baseline key: {key}")
            result[key] = value
        return result

    data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    if (not isinstance(data, dict) or set(data) != {"version", "findings"}
            or type(data["version"]) is not int or data["version"] != 1
            or not isinstance(data["findings"], list)):
        raise ValueError("Invalid Java rule baseline schema")
    counts = Counter()
    for row in data["findings"]:
        if (not isinstance(row, list) or len(row) != 5
                or not all(isinstance(value, str) for value in row[:4])
                or row[1] not in RULES or type(row[4]) is not int or row[4] < 1):
            raise ValueError(f"Invalid Java rule baseline entry: {row}")
        path_value = Path(row[0])
        if (path_value.is_absolute() or ".." in path_value.parts or "\\" in row[0]
                or path_value.as_posix() != row[0]
                or not (row[0] == CHECKER or any(row[0].startswith(p + "/") for p in ROOTS))
                or not row[0].endswith(".java")):
            raise ValueError(f"Invalid baseline source path: {row[0]}")
        key = tuple(row[:4])
        if key in counts:
            raise ValueError(f"Duplicate Java rule baseline entry: {key}")
        counts[key] = row[4]
    return counts


def scan(root: Path, files: list[Path]) -> list[dict]:
    for tool in ("java", "javac"):
        if shutil.which(tool) is None:
            raise ValueError(f"Missing required JDK 21 tool: {tool}")
    with tempfile.TemporaryDirectory(prefix="jdbc-s1-java-rules-") as temporary:
        compiled = subprocess.run(
            ["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-d", temporary,
             str(REPOSITORY / CHECKER)], capture_output=True, text=True, timeout=120)
        if compiled.returncode != 0:
            raise ValueError(f"Checker compilation failed ({compiled.returncode}):\n"
                             + compiled.stdout + compiled.stderr)
        result = subprocess.run(
            ["java", "-cp", temporary, "CheckJavaRules", *map(str, files)],
            capture_output=True, text=True, encoding="utf-8", timeout=120)
        if result.returncode not in (0, 1):
            raise ValueError(f"Checker execution failed ({result.returncode}):\n"
                             + result.stdout + result.stderr)
        findings = []
        known = set(files)
        for line in result.stdout.splitlines():
            finding = json.loads(line)
            if (not isinstance(finding, dict)
                    or set(finding) != {"path", "rule", "owner", "detail", "line"}
                    or not all(isinstance(finding[k], str)
                               for k in ("path", "rule", "owner", "detail"))
                    or finding["rule"] not in RULES or type(finding["line"]) is not int
                    or finding["line"] < 1 or Path(finding["path"]) not in known):
                raise ValueError("Invalid checker output")
            finding["path"] = Path(finding["path"]).relative_to(root).as_posix()
            findings.append(finding)
        if result.stderr or bool(findings) != (result.returncode == 1):
            raise ValueError("Inconsistent checker status/output: " + result.stderr)
        return findings


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPOSITORY)
    parser.add_argument("--inventory", action="store_true",
                        help="List all findings without a baseline; exits 1 when findings exist")
    args = parser.parse_args(argv)
    try:
        root = args.root.resolve(strict=True)
        files = source_files(root)
        baseline = Counter() if args.inventory else read_baseline(
            root / "code-style/java-rules-baseline.json")
        findings = scan(root, files)
        actual = Counter(tuple(item[k] for k in ("path", "rule", "owner", "detail"))
                         for item in findings)
        if args.inventory:
            for item in findings:
                print(json.dumps(item, ensure_ascii=True, sort_keys=True))
            return 1 if findings else 0
        added, stale = actual - baseline, baseline - actual
        for label, counts in (("NEW", added), ("STALE BASELINE (remove/reduce manually)", stale)):
            for key, count in sorted(counts.items()):
                print(f"{label} {count}x: {key}", file=sys.stderr)
        if added or stale:
            return 1
        print(f"Java syntax rules: {len(files)} files, {sum(actual.values())} existing findings; "
              "no new findings or stale baseline entries")
        return 0
    except (OSError, ValueError, TypeError, subprocess.SubprocessError) as error:
        print(f"Java rule check ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
