"""Evidence-gated driver upgrade comparisons; adapter provenance never grants eligibility."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
from pathlib import Path
from typing import Any

EVIDENCE_VERSION = 1
PURPOSE = "driver-upgrade"
ALLOWED_CHANGES = ["driver_version", "driver_name", "driver_artifacts"]
GOOD = {"passed", "known_unsupported", "observed"}
FAILURES = {"compatibility_failure", "capability_declaration_mismatch", "known_difference"}
ISSUES = {"execution_error", "adapter_incomplete", "not_run", "skipped", "unknown_capability",
          "cleanup_failure"}


def fingerprint(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode()).hexdigest()


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_digest(roots: dict[str, Path], suffixes: set[str]) -> str:
    rows = {}
    for label, root in sorted(roots.items()):
        files = [root] if root.is_file() else sorted(root.rglob("*"))
        for path in files:
            if path.is_file() and path.suffix in suffixes:
                relative = path.name if root.is_file() else path.relative_to(root).as_posix()
                rows[f"{label}/{relative}"] = file_digest(path)
    return fingerprint(rows) if rows else ""


def definition_digest(project: Path) -> str:
    roots = {"inventory": project / "compatibility/v1/scenario-inventory.yaml",
             "pom": project / "pom.xml", "scripts": project / "scripts"}
    for module in ("core", "suite", "worker"):
        root = project / f"jdbc-test-{module}"
        roots[f"{module}/sources"] = root / "src/main"
        roots[f"{module}/pom"] = root / "pom.xml"
    return tree_digest(roots, {".java", ".xml", ".yaml", ".yml", ".json", ".properties", ".py", ".sql"})


def runtime_inputs(project: Path, config: dict, manifest: dict) -> dict:
    """Never persist connection settings or unkeyed digests of secret properties."""
    settings = config.get("regression") or {}
    key = os.environ.get(settings.get("environment_key_env", "JDBC_TEST_REGRESSION_KEY"), "")
    connection = config["db"]
    namespace = config["namespace"]
    stable = {"db": {name: connection.get(name) for name in
                       ("url", "username", "connection_mode", "properties", "data_sources",
                        "driver_class", "identifier_quote", "dialect")},
              "namespace": {name: namespace.get(name) for name in
                            ("mode", "selection", "database", "create_sql", "drop_sql", "select_sql")},
              "test_filter": config["test_filter"], "test_profile": config["test_profile"],
              "plan_requirements": config.get("plan_requirements", {}),
              "execution": config.get("execution", {}),
              "runtime_environment": {key: os.environ.get(key) for key in
                  ("TZ", "LANG", "LC_ALL", "LC_TIME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS")}}
    # Auto namespaces and object prefixes change each run; an existing scope must stay identical.
    if namespace["mode"] != "auto":
        stable["namespace"]["name"] = namespace["name"]
    encoded = json.dumps(stable, sort_keys=True, ensure_ascii=False).encode()
    return {"definition_sha256": definition_digest(project),
            "adapter_sha256": fingerprint(manifest),
            "assets_sha256": tree_digest({area: Path(config[area][field]) for area, field in
                                            (("ddl", "base_path"), ("dml", "base_path"),
                                             ("pool", "profile_dir"))}, {".sql", ".yaml", ".yml", ".json", ".properties"}),
            "config_hmac": hmac.new(key.encode(), encoded, hashlib.sha256).hexdigest()
                if len(key.encode()) >= 32 else "",
            "key_id": fingerprint(key) if len(key.encode()) >= 32 else "",
            "database_identity": settings.get("database_identity", ""),
            "server_build": settings.get("server_build", ""),
            "database_mode": settings.get("database_mode", ""),
            "identity_source": "operator_attestation"}


def build_evidence(project: Path, config: dict, env: dict, selected: set[str]) -> dict:
    inputs = dict(config.get("regression_inputs") or {})
    errors = []
    if inputs.get("definition_sha256") != definition_digest(project):
        errors.append("test definitions missing or changed during execution")
    driver_paths = {Path(p).resolve() for p in config.get("driver_artifact", {}).get("classpath", [])}
    jars = []
    dependencies = []
    classpath = env.get("surefire.test.class.path", "")
    if not classpath:
        errors.append("missing runtime dependency classpath")
    for value in classpath.split(os.pathsep):
        path = Path(value)
        if path.suffix == ".jar":
            if not path.is_file():
                errors.append("runtime dependency unavailable for fingerprinting")
            elif path.resolve() not in driver_paths:
                dependencies.append({"name": path.name, "sha256": file_digest(path)})
    for value in config.get("driver_artifact", {}).get("classpath", []):
        path = Path(value).resolve()
        if path.is_file():
            jars.append({"name": path.name, "sha256": file_digest(path)})
        else:
            errors.append("driver artifact unavailable for fingerprinting")
    if not jars:
        errors.append("missing driver artifact fingerprints")
    if not dependencies:
        errors.append("missing framework dependency fingerprints")
    driver_bundle = hashlib.sha256()
    for artifact in jars:
        driver_bundle.update(artifact["name"].encode())
        driver_bundle.update(b"\0")
        driver_bundle.update(artifact["sha256"].encode("ascii"))
        driver_bundle.update(b"\0")
    if config.get("driver_artifact", {}).get("sha256") != driver_bundle.hexdigest():
        errors.append("driver artifacts changed after resolution")
    if env.get("jdbc.test.evidenceConflict"):
        errors.append("conflicting runtime target evidence")
    observed = {name: env.get(key, "") for name, key in
                (("database_product", "jdbc.test.databaseProductName"),
                 ("database_version", "jdbc.test.databaseProductVersion"),
                 ("driver_name", "jdbc.test.driverName"), ("driver_version", "jdbc.test.driverVersion"),
                 ("java_version", "java.runtime.version"), ("java_vendor", "java.vm.vendor"),
                 ("os_name", "os.name"), ("os_arch", "os.arch"), ("os_version", "os.version"),
                 ("time_zone", "jdbc.test.timeZone"), ("locale", "jdbc.test.locale"),
                 ("charset", "jdbc.test.charset"))}
    return {"version": EVIDENCE_VERSION, "purpose": PURPOSE, **inputs, **observed,
            "selected_scenarios": sorted(selected), "driver_artifacts": jars,
            "framework_sha256": fingerprint(sorted(dependencies, key=lambda row: (row["name"], row["sha256"])))
                if dependencies else "", "capture_errors": errors}


REQUIRED = ("definition_sha256", "adapter_sha256", "assets_sha256", "config_hmac", "key_id",
            "database_identity", "server_build", "database_mode", "database_product", "database_version",
            "java_version", "java_vendor", "os_name", "os_arch", "os_version", "time_zone", "locale", "charset",
            "framework_sha256",
            "driver_artifacts", "driver_name", "driver_version", "selected_scenarios")


def validate(report: dict) -> list[str]:
    if report.get("testDomain") == "SQL_FEATURE":
        return ["SQL_FEATURE reports are not supported by JDBC regression comparison"]
    evidence = report.get("regression_evidence") or {}
    errors = []
    if evidence.get("version") != EVIDENCE_VERSION or evidence.get("purpose") != PURPOSE:
        errors.append("regression evidence version/purpose missing or unsupported; rerun with current CLI")
    if report.get("outcome_semantics_version") != 2 or report.get("report_schema_version") != "1.0.0":
        errors.append("unsupported report/outcome semantics version; rerun with current CLI")
    if not report.get("compatibility_baseline_version"):
        errors.append("missing compatibility_baseline_version")
    for field in REQUIRED:
        if not evidence.get(field):
            errors.append(f"missing regression evidence: {field}")
    for field in ("definition_sha256", "adapter_sha256", "assets_sha256", "config_hmac", "key_id", "framework_sha256"):
        if evidence.get(field) and not re.fullmatch(r"[a-f0-9]{64}", str(evidence[field])):
            errors.append(f"invalid SHA-256 evidence: {field}")
    artifacts = evidence.get("driver_artifacts") or []
    if not isinstance(artifacts, list) or any(not isinstance(item, dict) or not item.get("name")
            or not re.fullmatch(r"[a-f0-9]{64}", str(item.get("sha256", ""))) for item in artifacts):
        errors.append("invalid driver_artifacts evidence")
    if evidence.get("identity_source") != "operator_attestation":
        errors.append("missing database identity attestation provenance")
    errors.extend(evidence.get("capture_errors") or [])
    selected = evidence.get("selected_scenarios") or []
    if not isinstance(selected, list) or any(not isinstance(x, str) for x in selected):
        errors.append("invalid selected scenario evidence")
        selected = []
    if len(selected) != len(set(selected)):
        errors.append("duplicate selected scenarios")
    rows = report.get("scenario_results") or {}
    for scenario in selected:
        row = rows.get(scenario) or {}
        if not row or row.get("conclusion") in {"USER_EXCLUDED", "NOT_APPLICABLE"}:
            errors.append(f"missing or excluded selected result: {scenario}")
        status = row.get("compatibility_status")
        if status in {"passed", "observed"} and (row.get("conclusion") != "CONFORMS"
                                                 or row.get("contract_conforms") is not True):
            errors.append(f"missing conforming execution evidence: {scenario}")
        if status == "known_unsupported" and (row.get("conclusion") != "UNSUPPORTED_ALLOWED"
                                              or row.get("contract_conforms") is not True):
            errors.append(f"missing API-scoped unsupported evidence: {scenario}")
        if row.get("compatibility_status") not in GOOD | FAILURES | ISSUES:
            errors.append(f"unknown status for selected result: {scenario}")
    if report.get("target_identity_mismatch", {}).get("mismatch"):
        errors.append("target identity rejected")
    if report.get("diagnostics", {}).get("unmapped_test_cases"):
        errors.append("unmapped test cases")
    if not report.get("adapter", {}).get("trust"):
        errors.append("missing adapter provenance")
    for name, row in rows.items():
        if row.get("phase") == "EXECUTION" and name not in selected:
            errors.append(f"unselected scenario was executed: {name}")
    return errors


def identity(report: dict) -> dict:
    return {"adapter": report.get("adapter"), "formal_eligible": report.get("formal_eligible", False),
            "report_sha256": fingerprint(report), "evidence": report.get("regression_evidence", {})}


def suite_requirement(row: dict) -> bool:
    return row.get("conclusion") == "NOT_EVALUATED" and row.get("requirement_scope") == "SUITE" \
        and row.get("execution_status") == "failure" and row.get("phase") == "EXECUTION"


def execution_issues(report: dict) -> list[dict]:
    selected = report.get("regression_evidence", {}).get("selected_scenarios", [])
    rows = report.get("scenario_results", {})
    issues = [{"scenario_id": name, "status": rows.get(name, {}).get("compatibility_status"),
               "conclusion": rows.get(name, {}).get("conclusion")}
              for name in selected if not suite_requirement(rows.get(name, {})) and (
              rows.get(name, {}).get("compatibility_status") in ISSUES
              or rows.get(name, {}).get("phase") != "EXECUTION"
              or rows.get(name, {}).get("conclusion") in {"NOT_EVALUATED", "PLATFORM_ERROR", "TIMEOUT",
                                                          "PREREQUISITE_UNMET", "BLOCKED_PREREQUISITE",
                                                          "CONNECTION_FAILURE"})]
    for field in ("environment_cleanup_issues", "environment_preflight_issues"):
        issues.extend({"scope": field, "detail": item} for item in report.get(field, []) or [])
    maven_code = report.get("diagnostics", {}).get("maven_exit_code")
    explained = any(row.get("compatibility_status") in FAILURES or
                    row.get("execution_status") == "error"
                    for name, row in rows.items() if name in selected)
    if maven_code not in (0, 1) or maven_code == 1 and not explained:
        issues.append({"scope": "maven", "detail": "missing or unexplained execution failure"})
    return issues


def select_baseline(report: dict) -> dict:
    errors = validate(report)
    issues = execution_issues(report) if not errors else []
    if report.get("plan_assessment", {}).get("not_evaluated"):
        errors.append("plan requirements were not evaluated")
    if errors or issues:
        raise ValueError("Cannot select internal baseline: " + "; ".join(errors + [str(i) for i in issues]))
    # Snapshot, never rewrite the source report or promote its trust.
    return {"baseline_schema_version": 1, "scope": "internal", "purpose": PURPOSE,
            "report_sha256": fingerprint(report), "report": report,
            "notice": "Internal baseline selection is not certification or maintainer endorsement."}


def compare(baseline: dict, current: dict, scope: str = "internal") -> dict:
    errors = []
    if scope == "internal":
        if baseline.get("baseline_schema_version") != 1 or baseline.get("scope") != "internal" \
                or baseline.get("purpose") != PURPOSE:
            errors.append("explicit internal baseline selection required; use baseline create")
        source = baseline.get("report") or {}
        if baseline.get("report_sha256") != fingerprint(source):
            errors.append("internal baseline report fingerprint mismatch")
        baseline = source
    else:
        for label, report in (("baseline", baseline), ("current", current)):
            if not report.get("formal_eligible"):
                errors.append(f"{label}: report is not formal eligible")
    for label, report in (("baseline", baseline), ("current", current)):
        errors.extend(f"{label}: {error}" for error in validate(report))
    before = baseline.get("regression_evidence") or {}
    after = current.get("regression_evidence") or {}
    checks = []
    for field in (*REQUIRED, "compatibility_baseline_version"):
        if field in ("driver_artifacts", "driver_name", "driver_version", "selected_scenarios"):
            continue
        a, b = (baseline.get(field), current.get(field)) if field == "compatibility_baseline_version" \
            else (before.get(field), after.get(field))
        matches = bool(a) and a == b
        checks.append({"dimension": field, "matches": matches})
        if not matches:
            errors.append(f"incompatible or missing evidence: {field}")
    selected_before = set(before.get("selected_scenarios") or [])
    selected_after = set(after.get("selected_scenarios") or [])
    removed, added = sorted(selected_before - selected_after), sorted(selected_after - selected_before)
    if removed or added:
        errors.append("selected scenario set changed; establish a separate baseline")
    baseline_issues = execution_issues(baseline) if not validate(baseline) else []
    if baseline_issues:
        errors.append("baseline contains execution/preparation problems; select a complete baseline")
    current_issues = execution_issues(current) if not validate(current) else []
    changes, new_failures, fixed, unresolved = [], [], [], []
    suite_before, suite_after = [], []
    for name in sorted(selected_before | selected_after):
        old_row = baseline.get("scenario_results", {}).get(name, {})
        new_row = current.get("scenario_results", {}).get(name, {})
        old_suite, new_suite = suite_requirement(old_row), suite_requirement(new_row)
        if old_suite:
            suite_before.append(name)
        if new_suite:
            suite_after.append(name)
        a = old_row.get("compatibility_status", "not_run")
        b = new_row.get("compatibility_status", "not_run")
        if a != b:
            changes.append({"scenario_id": name, "before": a, "after": b})
        if b in FAILURES and not new_suite:
            (unresolved if a in FAILURES and not old_suite else new_failures).append(name)
        elif a in FAILURES and not old_suite and b in GOOD:
            fixed.append(name)
    comparable = not errors
    # A formerly supported API becoming legally unsupported is still a regression in support.
    support_losses = [row["scenario_id"] for row in changes
                      if row["before"] == "passed" and row["after"] == "known_unsupported"]
    unmet = current.get("plan_assessment", {}).get("unmet", [])
    uncertain = current.get("plan_assessment", {}).get("not_evaluated", [])
    new_unmet = sorted(set(unmet) - set(baseline.get("plan_assessment", {}).get("unmet", [])))
    new_suite = sorted(set(suite_after) - set(suite_before))
    exit_code = 2 if errors or current_issues or uncertain else 1 if new_failures or support_losses or new_unmet or new_suite else 0
    all_passed = comparable and not current_issues and not unmet and not uncertain and bool(selected_after) \
        and all(current["scenario_results"][x]["compatibility_status"] == "passed" for x in selected_after)
    return {"comparison_schema_version": 1, "purpose": PURPOSE, "scope": scope,
            "allowed_changes": ALLOWED_CHANGES, "comparable": comparable, "checks": checks,
            "errors": errors, "coverage_changes": {"added": added, "removed": removed},
            "new_failures": new_failures, "fixed": fixed, "unresolved_failures": unresolved,
            "suite_requirements": {"unmet": suite_after, "new_unmet": new_suite,
                                   "resolved": sorted(set(suite_before) - set(suite_after))},
            "support_losses": support_losses, "execution_issues": current_issues,
            "baseline_execution_issues": baseline_issues, "changes": changes,
            "plan_assessment": current.get("plan_assessment", {}), "new_unmet_requirements": new_unmet,
            "all_selected_passed": all_passed,
            "no_new_regressions": exit_code == 0, "exit_code": exit_code,
            "blocking_changes": exit_code != 0, "baseline": identity(baseline), "current": identity(current),
            "limitations": ["Database identity, server build and compatibility mode are operator attestations; "
                            "the CLI also checks runtime JDBC metadata and keyed connection/config fingerprints.",
                            "No new regressions does not mean all tests passed.",
                            "Internal comparison does not certify JDBC compliance or imply maintainer endorsement."]}
