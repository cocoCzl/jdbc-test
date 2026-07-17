#!/usr/bin/env python3
from __future__ import annotations

import argparse
import html
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from adapter_runtime import (
    build_runtime_config,
    discover_adapters,
    load_adapter,
    resolve_driver,
    runtime_dependencies,
)
from compatibility_v1 import (
    CompatibilityStatus,
    build_v1_report,
    load_v1_report,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
REPORT_SCHEMA_VERSION = "1.0.0"


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] not in {"init", "run", "compare", "adapters", "--help", "-h"}:
        argv.insert(0, "run")
    parser = _parser()
    args = parser.parse_args(argv)
    if args.command == "init":
        return command_init(args)
    if args.command == "run":
        return command_run(args)
    if args.command == "compare":
        return command_compare(args)
    if args.command == "adapters":
        return command_adapters()
    parser.print_help()
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="JDBC 4.3 driver compatibility assessment")
    sub = parser.add_subparsers(dest="command")
    init = sub.add_parser("init", help="Generate secure local configuration")
    init.add_argument("--adapter")
    init.add_argument("--url")
    init.add_argument("--username")
    init.add_argument("--password-env", default="DB_PASSWORD")
    init.add_argument("--namespace")
    init.add_argument("--consent", action="store_true")
    init.add_argument("--output", default="configs/config.local.json")

    run = sub.add_parser("run", help="Run compatibility assessment")
    run.add_argument("config")
    run.add_argument("--experimental-override", action="store_true")

    compare = sub.add_parser("compare", help="Compare an assessment with an approved baseline")
    compare.add_argument("--baseline", required=True)
    compare.add_argument("--current", required=True)
    compare.add_argument("--output")
    sub.add_parser("adapters", help="List bundled adapters")
    return parser


def command_adapters() -> int:
    for adapter_id, path in discover_adapters(PROJECT_ROOT).items():
        manifest = json.loads((path / "adapter.json").read_text(encoding="utf-8"))
        print(f"{adapter_id:12} {manifest.get('trust', ''):10} {manifest.get('name', '')}")
    return 0


def command_init(args: argparse.Namespace) -> int:
    discovered = discover_adapters(PROJECT_ROOT)
    if not discovered:
        print("[错误] 没有可用数据库适配包", file=sys.stderr)
        return 2
    adapter_ref = args.adapter or _prompt("Adapter", ", ".join(discovered), next(iter(discovered)))
    try:
        adapter = load_adapter(PROJECT_ROOT, adapter_ref)
    except ValueError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 2
    url = args.url or _prompt("JDBC URL")
    username = args.username or _prompt("Username")
    namespace = args.namespace
    consent = bool(args.consent)
    if not args.consent and sys.stdin.isatty():
        consent = _prompt("Allow creation/deletion of the dedicated test namespace?", "yes/no", "no").lower() == "yes"
    config = {
        "schema_version": "1.0.0",
        "adapter": adapter_ref,
        "db": {
            "url": url,
            "username": username,
            "password_env": args.password_env,
            "driver": adapter.manifest["driver"]["source"],
        },
        "namespace": {
            "mode": adapter.manifest["namespace"]["mode"],
            "name": namespace,
            "destructive_consent": consent,
        },
        "execution": {"mode": "local"},
        "report": {"output_dir": "report", "format": ["json", "html", "markdown"]},
        "test_filter": {"include_tests": [], "exclude_tests": [], "timeout": 60000},
    }
    output = Path(args.output)
    if not output.is_absolute():
        output = PROJECT_ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        output.chmod(0o600)
    except OSError:
        pass
    print(f"[生成] {output}")
    print(f"[提示] 运行前设置环境变量 {args.password_env}")
    if not consent:
        print("[安全] destructive_consent=false；run 将拒绝执行数据库变更")
    return 0


def command_run(args: argparse.Namespace) -> int:
    config_path = Path(args.config)
    if not config_path.is_absolute():
        config_path = PROJECT_ROOT / config_path
    try:
        user_config = _load_user_config(config_path)
        if args.experimental_override:
            user_config["experimental_override"] = True
        dependencies = _preflight_dependencies()
        adapter = load_adapter(PROJECT_ROOT, str(user_config.get("adapter", "")))
        driver = resolve_driver(PROJECT_ROOT, user_config, adapter)
        runtime = build_runtime_config(PROJECT_ROOT, user_config, adapter, driver)
    except (OSError, ValueError) as exc:
        print(f"[预检失败] {exc}", file=sys.stderr)
        return 2

    runtime["evaluation_dependencies"] = dependencies
    execution: dict[str, Any]
    runtime_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".json", prefix="jdbc-test-", delete=False, encoding="utf-8") as handle:
            json.dump(runtime, handle, ensure_ascii=False)
            runtime_path = Path(handle.name)
        try:
            runtime_path.chmod(0o600)
        except OSError:
            pass
        execution = _run_maven(runtime, runtime_path, Path(driver["path"]))
        suites, env_info = _parse_surefire_reports()
        report = build_v1_report(runtime, execution, suites, env_info, PROJECT_ROOT)
        report["report_schema_version"] = REPORT_SCHEMA_VERSION
        report["formal_eligible"] = _validated_formal_eligibility(report, adapter.manifest)
        report_dir = _archive_report(runtime, report, execution, suites)
        print(f"[报告] {report_dir}")
        print(f"[结果] {report.get('target_outcome')} | formal_eligible={report.get('formal_eligible')}")
        return _assessment_exit_code(report)
    finally:
        if runtime_path is not None:
            runtime_path.unlink(missing_ok=True)


def command_compare(args: argparse.Namespace) -> int:
    baseline = load_v1_report(Path(args.baseline))
    current = load_v1_report(Path(args.current))
    comparison = compare_reports(baseline, current)
    payload = json.dumps(comparison, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(payload, encoding="utf-8")
        print(f"[生成] {args.output}")
    else:
        print(payload, end="")
    return 1 if comparison["blocking_changes"] else 0


def compare_reports(baseline: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    if baseline.get("compatibility_baseline_version") != current.get("compatibility_baseline_version"):
        errors.append("测试套件版本不兼容；请在当前套件上重新建立基线")
    if not baseline.get("formal_eligible"):
        errors.append("基线报告不是正式可比较报告")
    if not current.get("formal_eligible"):
        errors.append("当前报告不是正式可比较报告")
    if errors:
        return {"comparable": False, "errors": errors, "changes": [], "blocking_changes": True}
    previous = baseline.get("scenario_results", {})
    now = current.get("scenario_results", {})
    changes = []
    blocking_statuses = {
        CompatibilityStatus.COMPATIBILITY_FAILURE.value,
        CompatibilityStatus.EXECUTION_ERROR.value,
        CompatibilityStatus.UNKNOWN_CAPABILITY.value,
        CompatibilityStatus.CAPABILITY_DECLARATION_MISMATCH.value,
        CompatibilityStatus.NOT_RUN.value,
    }
    for scenario_id in sorted(set(previous) | set(now)):
        before = (previous.get(scenario_id) or {}).get("compatibility_status", "not_run")
        after = (now.get(scenario_id) or {}).get("compatibility_status", "not_run")
        if before != after:
            changes.append({
                "scenario_id": scenario_id,
                "before": before,
                "after": after,
                "blocking": after in blocking_statuses and before != after,
            })
    cleanup = current.get("environment_cleanup_issues") or []
    return {
        "comparable": True,
        "baseline": _report_identity(baseline),
        "current": _report_identity(current),
        "changes": changes,
        "cleanup_failures": cleanup,
        "blocking_changes": any(change["blocking"] for change in changes) or bool(cleanup),
    }


def _preflight_dependencies() -> dict[str, str]:
    found = runtime_dependencies()
    missing = [name for name, path in found.items() if not path]
    if missing:
        guidance = {
            "python": "Install Python 3.9+ / 安装 Python 3.9+",
            "java": "Install JDK 21 / 安装 JDK 21",
            "maven": "Install Maven 3.8+ / 安装 Maven 3.8+",
        }
        raise ValueError("; ".join(guidance[name] for name in missing))
    java = subprocess.run([str(found["java"]), "-version"], capture_output=True, text=True)
    version_text = java.stderr + java.stdout
    match = re.search(r'version "(\d+)', version_text)
    if not match or int(match.group(1)) != 21:
        raise ValueError(f"正式评估要求 JDK 21，当前: {version_text.splitlines()[0] if version_text else 'unknown'}")
    return {name: str(path) for name, path in found.items() if path}


def _run_maven(runtime: dict[str, Any], runtime_path: Path, driver_path: Path) -> dict[str, Any]:
    surefire = PROJECT_ROOT / "target" / "surefire-reports"
    if surefire.exists():
        for path in surefire.glob("TEST-*.xml"):
            path.unlink(missing_ok=True)
    command = [
        "mvn", "-q", "test",
        f"-Dconfig.yaml={runtime_path}",
        f"-Dsurefire.additionalClasspath={driver_path}",
    ]
    filters = runtime.get("test_filter") or {}
    includes = filters.get("include_tests") or []
    excludes = filters.get("exclude_tests") or []
    patterns = list(includes)
    if excludes:
        if not patterns:
            patterns.append("*Test")
        patterns.extend(f"!{value}" for value in excludes)
    if patterns:
        command.append(f"-Dtest={','.join(patterns)}")
    started = datetime.now(timezone.utc)
    result = subprocess.run(command, cwd=PROJECT_ROOT, capture_output=True, text=True)
    ended = datetime.now(timezone.utc)
    return {
        "start_time": started.isoformat(),
        "end_time": ended.isoformat(),
        "elapsed_seconds": round((ended - started).total_seconds(), 3),
        "exit_code": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    }


def _parse_surefire_reports() -> tuple[list[dict[str, Any]], dict[str, str]]:
    directory = PROJECT_ROOT / "target" / "surefire-reports"
    suites: list[dict[str, Any]] = []
    env: dict[str, str] = {}
    for xml_file in sorted(directory.glob("TEST-*.xml")) if directory.exists() else []:
        root = ET.parse(xml_file).getroot()
        if not env:
            properties = root.find("properties")
            if properties is not None:
                env = {item.get("name", ""): item.get("value", "") for item in properties.findall("property")}
        suite = {
            "name": root.get("name", ""),
            "time_seconds": float(root.get("time", 0)),
            "total": int(root.get("tests", 0)),
            "failures": int(root.get("failures", 0)),
            "errors": int(root.get("errors", 0)),
            "skipped": int(root.get("skipped", 0)),
            "system_err": root.findtext("system-err", default=""),
            "system_out": root.findtext("system-out", default=""),
            "testcases": [],
        }
        for node in root.findall("testcase"):
            case = {
                "name": node.get("name", ""),
                "classname": node.get("classname", ""),
                "time_seconds": float(node.get("time", 0)),
            }
            failure = node.find("failure")
            error = node.find("error")
            skipped = node.find("skipped")
            if error is not None:
                case.update(status="error", error_message=error.get("message", ""), error_type=error.get("type", ""), error_text=error.text or "")
            elif failure is not None:
                case.update(status="failure", error_message=failure.get("message", ""), error_type=failure.get("type", ""), error_text=failure.text or "")
            elif skipped is not None:
                case.update(status="skipped", skip_reason=skipped.get("message", "") or skipped.text or "")
            else:
                case["status"] = "passed"
            suite["testcases"].append(case)
        suites.append(suite)
    return suites, env


def _archive_report(runtime: dict[str, Any], report: dict[str, Any], execution: dict[str, Any], suites: list[dict[str, Any]]) -> Path:
    root = Path((runtime.get("report") or {}).get("output_dir", "report"))
    if not root.is_absolute():
        root = PROJECT_ROOT / root
    stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    directory = root / f"{runtime['db']['adapter_id']}_{stamp}"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (directory / "report.md").write_text(_markdown(report), encoding="utf-8")
    (directory / "report.html").write_text(_html(report), encoding="utf-8")
    diagnostic = {
        "execution": execution,
        "test_suites": suites,
        "notice": "Local diagnostic log. Review before sharing.",
    }
    (directory / "diagnostics.log").write_text(json.dumps(diagnostic, ensure_ascii=False, indent=2), encoding="utf-8")
    return directory


def _markdown(report: dict[str, Any]) -> str:
    target = report.get("compatibility_target", {})
    lines = [
        "# JDBC 4.3 Compatibility Assessment", "",
        f"- Target outcome: `{report.get('target_outcome', '')}`",
        f"- Formal eligible: `{report.get('formal_eligible', False)}`",
        f"- Database: `{(target.get('database_product') or {}).get('value', '')}`",
        f"- Database version: `{(target.get('database_version') or {}).get('value', '')}`",
        f"- Driver version: `{(target.get('jdbc_driver_version') or {}).get('value', '')}`",
        "", "## Outcome matrix", "",
        "| Scenario ID | Category | Outcome |", "|---|---|---|",
    ]
    for scenario_id, result in report.get("scenario_results", {}).items():
        lines.append(f"| {scenario_id} | {result.get('category', '')} | {result.get('compatibility_status', '')} |")
    return "\n".join(lines) + "\n"


def _html(report: dict[str, Any]) -> str:
    rows = "".join(
        f"<tr><td>{html.escape(sid)}</td><td>{html.escape(str(result.get('category', '')))}</td>"
        f"<td>{html.escape(str(result.get('compatibility_status', '')))}</td></tr>"
        for sid, result in report.get("scenario_results", {}).items()
    )
    return (
        "<!doctype html><html><head><meta charset='utf-8'><title>JDBC Compatibility Assessment</title>"
        "<style>body{font-family:system-ui;margin:2rem}table{border-collapse:collapse;width:100%}"
        "td,th{border:1px solid #ddd;padding:.45rem;text-align:left}th{background:#f3f4f6}</style></head><body>"
        f"<h1>JDBC 4.3 Compatibility Assessment</h1><p>Target Outcome: <b>{html.escape(str(report.get('target_outcome', '')))}</b></p>"
        f"<p>Formal eligible: {str(report.get('formal_eligible', False)).lower()}</p>"
        f"<table><thead><tr><th>Scenario ID</th><th>Category</th><th>Outcome</th></tr></thead><tbody>{rows}</tbody></table>"
        "</body></html>"
    )


def _validated_formal_eligibility(report: dict[str, Any], manifest: dict[str, Any]) -> bool:
    if not report.get("formal_eligible") or not manifest.get("validated_combinations"):
        return False
    product_version = str((report.get("compatibility_target", {}).get("database_version") or {}).get("value", ""))
    driver_version = str((report.get("compatibility_target", {}).get("jdbc_driver_version") or {}).get("value", ""))
    return any(
        _version_matches(product_version, item.get("database_version", ""))
        and _version_matches(driver_version, item.get("driver_version", ""))
        for item in manifest.get("validated_combinations", [])
    )


def _version_matches(observed: str, expected: str) -> bool:
    return bool(expected) and str(expected) in observed


def _assessment_exit_code(report: dict[str, Any]) -> int:
    blocking = {
        CompatibilityStatus.COMPATIBILITY_FAILURE.value,
        CompatibilityStatus.EXECUTION_ERROR.value,
        CompatibilityStatus.UNKNOWN_CAPABILITY.value,
        CompatibilityStatus.CAPABILITY_DECLARATION_MISMATCH.value,
        CompatibilityStatus.NOT_RUN.value,
        CompatibilityStatus.ADAPTER_INCOMPLETE.value,
        CompatibilityStatus.CLEANUP_FAILURE.value,
    }
    if report.get("environment_cleanup_issues"):
        return 1
    if report.get("environment_preflight_issues"):
        return 1
    return 1 if any(item.get("compatibility_status") in blocking for item in report.get("scenario_results", {}).values()) else 0


def _report_identity(report: dict[str, Any]) -> dict[str, Any]:
    target = report.get("compatibility_target", {})
    return {
        "adapter": report.get("adapter", {}),
        "database_version": (target.get("database_version") or {}).get("value"),
        "driver_version": (target.get("jdbc_driver_version") or {}).get("value"),
        "driver_sha256": (report.get("driver_artifact") or {}).get("sha256"),
    }


def _load_user_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise ValueError(f"配置文件不存在: {path}")
    if path.suffix.lower() == ".json":
        return json.loads(path.read_text(encoding="utf-8"))
    try:
        import yaml
    except ImportError as exc:
        raise ValueError("旧 YAML 配置需要 PyYAML；请使用 runner.py init 生成零依赖 JSON 配置") from exc
    with path.open("r", encoding="utf-8") as stream:
        legacy = yaml.safe_load(stream) or {}
    adapter = (legacy.get("db") or {}).get("type", "")
    password_value = (legacy.get("db") or {}).get("password", "")
    match = re.fullmatch(r"\$\{([^}]+)}", str(password_value))
    return {
        "adapter": adapter,
        "db": {
            "url": (legacy.get("db") or {}).get("url", ""),
            "username": (legacy.get("db") or {}).get("username", ""),
            "password_env": match.group(1) if match else "DB_PASSWORD",
        },
        "namespace": {"mode": "auto", "destructive_consent": False},
        "report": legacy.get("report") or {},
        "test_filter": legacy.get("test_filter") or {},
        "execution": legacy.get("execution") or {},
    }


def _prompt(label: str, hint: str = "", default: str = "") -> str:
    suffix = f" [{default}]" if default else ""
    if hint:
        suffix += f" ({hint})"
    value = input(f"{label}{suffix}: ").strip()
    return value or default


# Backward-compatible report helpers for callers of the former runner module.
def generate_json_report(config: dict[str, Any], execution: dict[str, Any], test_suites: list[dict[str, Any]], env_info: dict[str, str]) -> dict[str, Any]:
    return {
        "report_schema_version": REPORT_SCHEMA_VERSION,
        "execution": {key: execution.get(key) for key in ("start_time", "end_time", "elapsed_seconds", "exit_code")},
        "test_suites": test_suites,
        "environment": {
            "database_product": env_info.get("jdbc.test.databaseProductName", ""),
            "database_version": env_info.get("jdbc.test.databaseProductVersion", ""),
            "driver_name": env_info.get("jdbc.test.driverName", ""),
            "driver_version": env_info.get("jdbc.test.driverVersion", ""),
            "java_version": env_info.get("java.version", ""),
        },
    }


def generate_html_report(report: dict[str, Any]) -> str:
    return _html(report.get("v1_compatibility_report", report))


def generate_markdown_report(report: dict[str, Any]) -> str:
    return _markdown(report.get("v1_compatibility_report", report))


if __name__ == "__main__":
    raise SystemExit(main())
