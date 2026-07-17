from __future__ import annotations

import json
import platform
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import date, datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # Zero-dependency launcher fallback.
    yaml = None


COMPATIBILITY_BASELINE_VERSION = "1.0.0"
REPORT_SCHEMA_VERSION = "1.0.0"


class StableValueEnum(str, Enum):
    def __str__(self) -> str:
        return self.value


class TargetOutcome(StableValueEnum):
    TARGET_COMPATIBLE = "target_compatible"
    TARGET_NOT_COMPATIBLE = "target_not_compatible"
    EVALUATION_INCONCLUSIVE = "evaluation_inconclusive"
    NEEDS_RE_EVALUATION = "needs_re_evaluation"


class CompatibilityStatus(StableValueEnum):
    PASSED = "passed"
    COMPATIBILITY_FAILURE = "compatibility_failure"
    KNOWN_UNSUPPORTED = "known_unsupported"
    UNKNOWN_CAPABILITY = "unknown_capability"
    SKIPPED = "skipped"
    NOT_RUN = "not_run"
    EXECUTION_ERROR = "execution_error"
    CAPABILITY_DECLARATION_MISMATCH = "capability_declaration_mismatch"
    OBSERVED = "observed"
    KNOWN_DIFFERENCE = "known_difference"
    ADAPTER_INCOMPLETE = "adapter_incomplete"
    CLEANUP_FAILURE = "cleanup_failure"


class ScenarioCategory(StableValueEnum):
    CORE = "core"
    EXTENDED = "extended"
    OBSERVATIONAL = "observational"
    VENDOR_EXTENSION = "vendor_extension"


class RunKind(StableValueEnum):
    FORMAL_COMPATIBILITY_EVALUATION = "formal_compatibility_evaluation"
    DIAGNOSTIC_RUN = "diagnostic_run"


DEPRECATED_SCENARIO_IDS: dict[str, str | None] = {}
REPORTABLE_TEST_ROOT = Path("src/test/java/com/jdbctest")
SCENARIO_INVENTORY_PATH = Path("compatibility/v1/scenario-inventory.yaml")


@dataclass(frozen=True)
class Scenario:
    scenario_id: str
    category: ScenarioCategory
    contract: str
    class_name: str
    method_name: str
    required_capabilities: tuple[str, ...] = ()
    deprecated: bool = False
    replacement_scenario_id: str | None = None

    def to_report_dict(self) -> dict[str, Any]:
        return {
            "scenario_id": self.scenario_id,
            "category": self.category.value,
            "contract": self.contract,
            "source_class": self.class_name,
            "source_method": self.method_name,
            "required_capabilities": list(self.required_capabilities),
            "deprecated": self.deprecated,
            "replacement_scenario_id": self.replacement_scenario_id,
        }


def build_scenario_inventory(project_root: Path) -> dict[tuple[str, str], Scenario]:
    inventory_path = project_root / SCENARIO_INVENTORY_PATH
    if inventory_path.exists() and yaml is not None:
        source_inventory = discover_source_scenarios(project_root)
        stable_inventory = load_scenario_inventory(inventory_path)
        validate_inventory_covers_source(source_inventory, stable_inventory)
        return {
            key: Scenario(
                scenario_id=stable_inventory[key].scenario_id,
                category=source.category,
                contract=stable_inventory[key].contract,
                class_name=source.class_name,
                method_name=source.method_name,
                required_capabilities=source.required_capabilities,
                deprecated=stable_inventory[key].deprecated,
                replacement_scenario_id=stable_inventory[key].replacement_scenario_id,
            )
            for key, source in source_inventory.items()
        }
    return discover_source_scenarios(project_root)


def discover_source_scenarios(project_root: Path) -> dict[tuple[str, str], Scenario]:
    inventory: dict[tuple[str, str], Scenario] = {}
    java_root = project_root / REPORTABLE_TEST_ROOT
    for java_file in sorted(java_root.glob("**/*Test.java")):
        text = java_file.read_text(encoding="utf-8")
        if "@ExtendWith(JdbcTestExtension.class)" not in text:
            continue

        class_match = re.search(r"\bclass\s+(\w+)", text)
        package_match = re.search(r"\bpackage\s+([\w.]+)\s*;", text)
        if not class_match or not package_match:
            continue

        simple_class = class_match.group(1)
        class_name = f"{package_match.group(1)}.{simple_class}"
        area = _scenario_area(simple_class)
        class_capabilities = _annotation_capabilities(_prefix_before_class(text, simple_class))

        for method_name, method_prefix in _test_methods(text):
            required = tuple(dict.fromkeys(class_capabilities + _annotation_capabilities(method_prefix)))
            category = ScenarioCategory.EXTENDED if required else ScenarioCategory.CORE
            scenario_id = f"{area}.{_contract_name(method_name)}"
            replacement = DEPRECATED_SCENARIO_IDS.get(scenario_id)
            inventory[(class_name, method_name)] = Scenario(
                scenario_id=scenario_id,
                category=category,
                contract=_contract_text(area, method_name, required),
                class_name=class_name,
                method_name=method_name,
                required_capabilities=required,
                deprecated=scenario_id in DEPRECATED_SCENARIO_IDS,
                replacement_scenario_id=replacement,
            )

    _validate_inventory(inventory)
    return inventory


def load_scenario_inventory(path: Path) -> dict[tuple[str, str], Scenario]:
    if yaml is None:
        raise RuntimeError("PyYAML is required only to validate the checked-in scenario inventory")
    with path.open("r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    scenarios = raw.get("scenarios", [])
    deprecated = {
        item.get("scenario_id"): item.get("replacement_scenario_id")
        for item in raw.get("deprecated_scenario_ids", [])
    }
    inventory: dict[tuple[str, str], Scenario] = {}
    for item in scenarios:
        key = (item["source_class"], item["source_method"])
        inventory[key] = Scenario(
            scenario_id=item["scenario_id"],
            category=ScenarioCategory(item["category"]),
            contract=item["contract"],
            class_name=item["source_class"],
            method_name=item["source_method"],
            required_capabilities=tuple(item.get("required_capabilities", [])),
            deprecated=item["scenario_id"] in deprecated,
            replacement_scenario_id=deprecated.get(item["scenario_id"]),
        )
    _validate_inventory(inventory)
    return inventory


def validate_inventory_covers_source(
    source_inventory: dict[tuple[str, str], Scenario],
    inventory: dict[tuple[str, str], Scenario],
) -> None:
    missing = sorted(set(source_inventory) - set(inventory))
    if missing:
        formatted = ", ".join(f"{class_name}.{method_name}" for class_name, method_name in missing)
        raise ValueError(f"Scenario inventory is missing reportable test methods: {formatted}")


def enrich_test_cases(
    test_suites: list[dict[str, Any]],
    inventory: dict[tuple[str, str], Scenario],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    enriched: list[dict[str, Any]] = []
    unmapped: list[dict[str, Any]] = []
    for suite in test_suites:
        for case in suite.get("testcases", []):
            raw_method_name = case.get("name", "")
            key = (case.get("classname", ""), _normalize_junit_method_name(raw_method_name))
            scenario = inventory.get(key)
            if scenario is None:
                unmapped.append({
                    "class_name": key[0],
                    "method_name": key[1],
                    "raw_method_name": raw_method_name,
                    "raw_status": case.get("status", ""),
                })
                continue
            enriched.append({
                **case,
                "raw_method_name": raw_method_name,
                **scenario.to_report_dict(),
            })
    return enriched, unmapped


def class_setup_errors(test_suites: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    errors: dict[str, dict[str, Any]] = {}
    for suite in test_suites:
        for case in suite.get("testcases", []):
            method_name = _normalize_junit_method_name(case.get("name", ""))
            if case.get("status") == "error" and not method_name:
                errors[case.get("classname", "")] = case
    return errors


def normalize_status(case: dict[str, Any], capability_declarations: dict[str, Any]) -> str:
    raw = case.get("status", "")
    category = case.get("category", "")
    required_capabilities = case.get("required_capabilities", [])

    if category == ScenarioCategory.EXTENDED.value:
        missing, unsupported = _capability_gaps(required_capabilities, capability_declarations)
        if missing:
            return CompatibilityStatus.UNKNOWN_CAPABILITY.value
        if unsupported:
            return CompatibilityStatus.KNOWN_UNSUPPORTED.value
        if raw == "skipped":
            return CompatibilityStatus.CAPABILITY_DECLARATION_MISMATCH.value

    if category == ScenarioCategory.OBSERVATIONAL.value:
        return (
            CompatibilityStatus.OBSERVED.value
            if raw == "passed"
            else CompatibilityStatus.EXECUTION_ERROR.value
        )

    if raw == "passed":
        return CompatibilityStatus.PASSED.value
    if raw == "failure":
        return CompatibilityStatus.COMPATIBILITY_FAILURE.value
    if raw == "error":
        return CompatibilityStatus.EXECUTION_ERROR.value
    if raw == "skipped":
        return CompatibilityStatus.SKIPPED.value
    return CompatibilityStatus.NOT_RUN.value


def build_v1_report(
    config: dict[str, Any],
    execution: dict[str, Any],
    test_suites: list[dict[str, Any]],
    env_info: dict[str, str],
    project_root: Path,
) -> dict[str, Any]:
    inventory = build_scenario_inventory(project_root)
    enriched_cases, unmapped = enrich_test_cases(test_suites, inventory)
    setup_errors = class_setup_errors(test_suites)
    capability_declarations = load_capability_declarations(config, project_root)
    run_kind = classify_run(config)
    target = build_compatibility_target(config, env_info)
    evaluation_context = build_evaluation_context(config, execution, env_info, project_root)
    identity_mismatch = detect_target_identity_mismatch(config, target)

    scenario_results: dict[str, dict[str, Any]] = {}
    for case in enriched_cases:
        compatibility_status = normalize_status(case, capability_declarations)
        deviations = applicable_known_deviations(config, target, case["scenario_id"])
        if compatibility_status == CompatibilityStatus.COMPATIBILITY_FAILURE.value and any(
                d.get("trust") == "official" for d in deviations):
            compatibility_status = CompatibilityStatus.KNOWN_DIFFERENCE.value
        scenario_results[case["scenario_id"]] = {
            "scenario_id": case["scenario_id"],
            "category": case["category"],
            "contract": case["contract"],
            "compatibility_status": compatibility_status,
            "source_class": case["source_class"],
            "source_method": case["source_method"],
            "timing": {"duration_seconds": case.get("time_seconds", 0.0)},
            "diagnostic": {
                "raw_status": case.get("status", ""),
                "message": _sanitize_message(case.get("error_message") or case.get("skip_reason") or ""),
                "error_type": case.get("error_type", ""),
                "details": "local diagnostic log",
            },
            "required_capabilities": case.get("required_capabilities", []),
            "known_deviations": deviations,
        }

    for scenario in inventory.values():
        if scenario.scenario_id in scenario_results:
            continue
        setup_error = setup_errors.get(scenario.class_name)
        raw_status = "error" if setup_error else "not_run"
        status = normalize_status({**scenario.to_report_dict(), "status": raw_status}, capability_declarations)
        scenario_results[scenario.scenario_id] = {
            "scenario_id": scenario.scenario_id,
            "category": scenario.category.value,
            "contract": scenario.contract,
            "compatibility_status": status,
            "source_class": scenario.class_name,
            "source_method": scenario.method_name,
            "timing": {"duration_seconds": 0.0},
            "diagnostic": {
                "raw_status": raw_status,
                "message": _sanitize_message(setup_error.get("error_message", "")) if setup_error else "",
                "error_type": setup_error.get("error_type", "") if setup_error else "",
                "details": "local diagnostic log" if setup_error else "",
            },
            "required_capabilities": list(scenario.required_capabilities),
            "known_deviations": applicable_known_deviations(config, target, scenario.scenario_id),
        }

    capability_profile = build_capability_profile(scenario_results, capability_declarations)
    target_outcome = aggregate_target_outcome(scenario_results, run_kind, identity_mismatch)

    return {
        "report_schema_version": REPORT_SCHEMA_VERSION,
        "compatibility_baseline_version": COMPATIBILITY_BASELINE_VERSION,
        "run_kind": run_kind.value,
        "target_outcome": target_outcome.value,
        "target_identity_mismatch": identity_mismatch,
        "compatibility_target": target,
        "evaluation_context": evaluation_context,
        "scenario_results": dict(sorted(scenario_results.items())),
        "capability_profile": capability_profile,
        "known_deviations": collect_report_known_deviations(scenario_results),
        "expired_known_deviations": collect_expired_known_deviations(config),
        "environment_cleanup_issues": collect_cleanup_issues(execution, test_suites),
        "environment_preflight_issues": collect_preflight_issues(execution, test_suites),
        "formal_eligible": _formal_eligible(config, run_kind, identity_mismatch),
        "adapter": config.get("adapter", {}),
        "driver_artifact": config.get("driver_artifact", {}),
        "diagnostics": {
            "unmapped_test_cases": unmapped,
            "maven_exit_code": execution.get("exit_code"),
            "local_log": "diagnostics.log",
        },
    }


def classify_run(config: dict[str, Any]) -> RunKind:
    test_filter = config.get("test_filter", {}) or {}
    if test_filter.get("include_tests") or test_filter.get("exclude_tests"):
        return RunKind.DIAGNOSTIC_RUN
    return RunKind.FORMAL_COMPATIBILITY_EVALUATION


def aggregate_target_outcome(
    scenario_results: dict[str, dict[str, Any]],
    run_kind: RunKind,
    identity_mismatch: dict[str, Any],
) -> TargetOutcome:
    if identity_mismatch.get("mismatch"):
        return TargetOutcome.EVALUATION_INCONCLUSIVE
    if run_kind == RunKind.DIAGNOSTIC_RUN:
        return TargetOutcome.EVALUATION_INCONCLUSIVE

    core_statuses = [
        result["compatibility_status"]
        for result in scenario_results.values()
        if result["category"] == ScenarioCategory.CORE.value
    ]
    if not core_statuses:
        return TargetOutcome.EVALUATION_INCONCLUSIVE
    if any(s in (CompatibilityStatus.COMPATIBILITY_FAILURE.value, CompatibilityStatus.KNOWN_UNSUPPORTED.value)
           for s in core_statuses):
        return TargetOutcome.TARGET_NOT_COMPATIBLE
    if any(s in (CompatibilityStatus.EXECUTION_ERROR.value, CompatibilityStatus.NOT_RUN.value,
                 CompatibilityStatus.SKIPPED.value, CompatibilityStatus.UNKNOWN_CAPABILITY.value)
           for s in core_statuses):
        return TargetOutcome.EVALUATION_INCONCLUSIVE
    if all(s in (CompatibilityStatus.PASSED.value, CompatibilityStatus.KNOWN_DIFFERENCE.value)
           for s in core_statuses):
        return TargetOutcome.TARGET_COMPATIBLE
    return TargetOutcome.EVALUATION_INCONCLUSIVE


def build_compatibility_target(config: dict[str, Any], env_info: dict[str, str]) -> dict[str, Any]:
    db_config = config.get("db", {}) or {}
    configured_type = db_config.get("type", "")
    return {
        "configured_database_type": configured_type,
        "database_product": _explicit(env_info.get("jdbc.test.databaseProductName"), db_config.get("expected_database_product")),
        "database_version": _explicit(env_info.get("jdbc.test.databaseProductVersion"), db_config.get("expected_database_version")),
        "jdbc_driver_name": _explicit(env_info.get("jdbc.test.driverName"), _configured_driver_name(configured_type)),
        "jdbc_driver_version": _explicit(env_info.get("jdbc.test.driverVersion"), db_config.get("expected_driver_version")),
        "jdbc_url": {"value": "redacted", "source": "redacted"},
    }


def build_evaluation_context(
    config: dict[str, Any],
    execution: dict[str, Any],
    env_info: dict[str, str],
    project_root: Path,
) -> dict[str, Any]:
    return {
        "java_version": env_info.get("java.version", ""),
        "java_vendor": env_info.get("java.vm.vendor", ""),
        "os_name": env_info.get("os.name", platform.system()),
        "os_arch": env_info.get("os.arch", platform.machine()),
        "os_version": env_info.get("os.version", platform.version()),
        "python_version": sys.version,
        "project_version": read_project_version(project_root),
        "git_commit": read_git_commit(project_root),
        "execution_mode": (config.get("execution", {}) or {}).get("mode", "local"),
        "report_generated_at": datetime.now(timezone.utc).isoformat(),
        "start_time": execution.get("start_time", ""),
        "end_time": execution.get("end_time", ""),
        "elapsed_seconds": execution.get("elapsed_seconds", 0),
    }


def detect_target_identity_mismatch(config: dict[str, Any], target: dict[str, Any]) -> dict[str, Any]:
    db_config = config.get("db", {}) or {}
    checks = [
        ("database_product", db_config.get("expected_database_product")),
        ("jdbc_driver_name", db_config.get("expected_driver_name")),
        ("jdbc_driver_version", db_config.get("expected_driver_version")),
    ]
    mismatches = []
    for field, expected in checks:
        if expected in (None, ""):
            continue
        observed = target.get(field, {}).get("value", "")
        if observed and expected.lower() not in observed.lower():
            mismatches.append({"field": field, "expected": expected, "observed": observed})
    return {"mismatch": bool(mismatches), "details": mismatches}


def build_capability_profile(
    scenario_results: dict[str, dict[str, Any]],
    declarations: dict[str, Any],
) -> dict[str, Any] | None:
    extended = [
        result for result in scenario_results.values()
        if result["category"] == ScenarioCategory.EXTENDED.value
    ]
    if not extended:
        return None
    entries = []
    complete = True
    for result in sorted(extended, key=lambda r: r["scenario_id"]):
        missing, unsupported = _capability_gaps(result.get("required_capabilities", []), declarations)
        if missing or result["compatibility_status"] == CompatibilityStatus.UNKNOWN_CAPABILITY.value:
            complete = False
        entries.append({
            "scenario_id": result["scenario_id"],
            "required_capabilities": result.get("required_capabilities", []),
            "declared": {
                capability: declarations.get(capability)
                for capability in result.get("required_capabilities", [])
            },
            "missing_capabilities": missing,
            "unsupported_capabilities": unsupported,
            "compatibility_status": result["compatibility_status"],
        })
    return {
        "completeness": "complete" if complete else "incomplete",
        "scenarios": entries,
    }


def load_capability_declarations(config: dict[str, Any], project_root: Path) -> dict[str, Any]:
    adapter_capabilities = (config.get("adapter", {}) or {}).get("capabilities")
    if isinstance(adapter_capabilities, dict):
        return adapter_capabilities
    return {}


def applicable_known_deviations(
    config: dict[str, Any],
    target: dict[str, Any],
    scenario_id: str,
) -> list[dict[str, Any]]:
    deviations = (config.get("known_deviations") or [])
    if not isinstance(deviations, list):
        return []
    target_product = target.get("database_product", {}).get("value", "")
    target_db_version = target.get("database_version", {}).get("value", "")
    target_driver_version = target.get("jdbc_driver_version", {}).get("value", "")
    applicable = []
    for deviation in deviations:
        if deviation.get("scenario_id") != scenario_id:
            continue
        if _known_deviation_expired(deviation):
            continue
        product = deviation.get("database_product")
        if product and product.lower() not in target_product.lower():
            continue
        if not _version_in_range(target_db_version, deviation.get("database_version")):
            continue
        if not _version_in_range(target_driver_version, deviation.get("driver_version")):
            continue
        applicable.append(deviation)
    return applicable


def collect_expired_known_deviations(config: dict[str, Any]) -> list[dict[str, Any]]:
    deviations = config.get("known_deviations") or []
    return [dict(item) for item in deviations if isinstance(item, dict) and _known_deviation_expired(item)]


def _known_deviation_expired(deviation: dict[str, Any]) -> bool:
    value = deviation.get("review_after")
    if not value:
        return False
    try:
        return date.fromisoformat(str(value)) < date.today()
    except ValueError:
        return True


def _formal_eligible(config: dict[str, Any], run_kind: RunKind, identity_mismatch: dict[str, Any]) -> bool:
    adapter = config.get("adapter", {}) or {}
    return (
        run_kind == RunKind.FORMAL_COMPATIBILITY_EVALUATION
        and not identity_mismatch.get("mismatch")
        and adapter.get("trust") == "official"
        and not adapter.get("experimental_override", False)
    )


def _sanitize_message(value: str) -> str:
    text = (value or "").replace("\r", " ").replace("\n", " ")
    text = re.sub(r"jdbc:[^\s]+", "jdbc:[redacted]", text, flags=re.IGNORECASE)
    text = re.sub(r"(?i)(password|pwd|token)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", text)
    return text[:240]


def collect_report_known_deviations(scenario_results: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    records = []
    for result in scenario_results.values():
        for deviation in result.get("known_deviations", []):
            records.append({"scenario_id": result["scenario_id"], **deviation})
    return records


def collect_cleanup_issues(
    execution: dict[str, Any],
    test_suites: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    issues = []
    streams = [execution.get("stderr", "") or ""]
    for suite in test_suites or []:
        streams.append(suite.get("system_err", "") or "")
        streams.append(suite.get("system_out", "") or "")
    for line in "\n".join(streams).splitlines():
        if line.startswith("[JDBC_CLEANUP_ISSUE]"):
            payload = line.removeprefix("[JDBC_CLEANUP_ISSUE]").strip()
            try:
                issues.append(json.loads(payload))
            except json.JSONDecodeError:
                issues.append({"message": payload})
            continue
        if "清理" in line and ("失败" in line or "[WARN]" in line):
            issues.append({"message": line})
    return issues


def collect_preflight_issues(
    execution: dict[str, Any],
    test_suites: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    issues = []
    streams = [execution.get("stderr", "") or ""]
    for suite in test_suites or []:
        streams.append(suite.get("system_err", "") or "")
        streams.append(suite.get("system_out", "") or "")
    for line in "\n".join(streams).splitlines():
        if not line.startswith("[JDBC_PREFLIGHT_ISSUE]"):
            continue
        payload = line.removeprefix("[JDBC_PREFLIGHT_ISSUE]").strip()
        try:
            issues.append(json.loads(payload))
        except json.JSONDecodeError:
            issues.append({"message": payload})
    return issues


def generate_matrix(reports: list[dict[str, Any]]) -> dict[str, Any]:
    if not reports:
        return {"compatibility_baseline_version": COMPATIBILITY_BASELINE_VERSION, "targets": [], "rows": []}
    baseline = reports[0]["compatibility_baseline_version"]
    baseline_versions = [r["compatibility_baseline_version"] for r in reports]
    incompatible = [
        version for version in baseline_versions
        if _major_minor(version) != _major_minor(baseline)
    ]
    targets = [_target_label(r.get("compatibility_target", {})) for r in reports]
    scenario_ids = sorted({
        scenario_id
        for report in reports
        for scenario_id, result in report.get("scenario_results", {}).items()
        if result.get("category") != ScenarioCategory.VENDOR_EXTENSION.value
    })
    rows = []
    for scenario_id in scenario_ids:
        row = {"scenario_id": scenario_id, "cells": {}}
        for label, report in zip(targets, reports):
            result = report.get("scenario_results", {}).get(scenario_id)
            row["cells"][label] = result.get("compatibility_status") if result else CompatibilityStatus.NOT_RUN.value
        rows.append(row)
    return {
        "compatibility_baseline_version": baseline,
        "baseline_versions": sorted(set(baseline_versions)),
        "mixed_incompatible_baselines": bool(incompatible),
        "targets": targets,
        "rows": rows,
    }


def vendor_extension_report(reports: list[dict[str, Any]]) -> dict[str, Any]:
    targets = []
    for report in reports:
        rows = [
            result for result in report.get("scenario_results", {}).values()
            if result.get("category") == ScenarioCategory.VENDOR_EXTENSION.value
        ]
        targets.append({"target": _target_label(report.get("compatibility_target", {})), "scenario_results": rows})
    return {"targets": targets}


def _validate_inventory(inventory: dict[tuple[str, str], Scenario]) -> None:
    ids = {}
    for scenario in inventory.values():
        existing = ids.setdefault(scenario.scenario_id, scenario)
        if existing is not scenario:
            raise ValueError(f"Duplicate scenario_id: {scenario.scenario_id}")
        if re.search(r"\b(mysql|oracle|postgresql|postgres|sqlserver|gaussdb)\b", scenario.scenario_id):
            raise ValueError(f"Database-specific suffix is not allowed in scenario_id: {scenario.scenario_id}")


def _normalize_junit_method_name(method_name: str) -> str:
    return re.sub(r"\([^)]*\)$", "", method_name)


def _test_methods(text: str) -> list[tuple[str, str]]:
    matches: list[tuple[str, str]] = []
    annotations: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("@"):
            annotations.append(stripped)
            continue
        method_match = re.search(r"(?:public\s+|private\s+|protected\s+)?(?:static\s+)?void\s+(\w+)\s*\(", stripped)
        if method_match:
            annotation_text = "\n".join(annotations)
            if any(a.startswith("@Test") for a in annotations):
                matches.append((method_match.group(1), annotation_text))
            annotations = []
            continue
        if stripped and not stripped.startswith("//"):
            annotations = []
    return matches


def _prefix_before_class(text: str, simple_class: str) -> str:
    class_pos = text.find(f"class {simple_class}")
    return text[:class_pos] if class_pos >= 0 else ""


def _annotation_capabilities(text: str) -> list[str]:
    capabilities: list[str] = []
    for match in re.finditer(r"@RequiresFeature\(([^)]*)\)", text):
        capabilities.extend(re.findall(r'"([^"]+)"', match.group(1)))
    return capabilities


def _scenario_area(simple_class: str) -> str:
    base = simple_class.removesuffix("Test")
    acronyms = {
        "SQLXML": "sqlxml",
        "BlobClob": "blob_clob",
        "DataSource": "datasource",
        "DatabaseMetaData": "database_metadata",
        "ParameterMetaData": "parameter_metadata",
        "PreparedStatement": "prepared_statement",
        "ResultSet": "result_set",
        "ResultSetMetaData": "result_set_metadata",
        "CallableStatement": "callable_statement",
        "AdvancedType": "advanced_type",
        "RowSet": "rowset",
    }
    return acronyms.get(base, _to_snake(base))


def _contract_name(method_name: str) -> str:
    return _to_snake(method_name.removeprefix("test"))


def _contract_text(area: str, method_name: str, required: tuple[str, ...]) -> str:
    words = _contract_name(method_name).replace("_", " ")
    capability = f" when capabilities {', '.join(required)} are declared" if required else ""
    return f"{area.replace('_', ' ')} {words} follows the JDBC-facing API contract{capability}."


def _to_snake(value: str) -> str:
    value = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", value)
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", value)
    return value.lower().strip("_")


def _capability_gaps(required: list[str], declarations: dict[str, Any]) -> tuple[list[str], list[str]]:
    missing = []
    unsupported = []
    for capability in required:
        if capability not in declarations:
            missing.append(capability)
        elif declarations.get(capability) is False:
            unsupported.append(capability)
    return missing, unsupported


def _explicit(observed: str | None, fallback: str | None = None) -> dict[str, Any]:
    if observed:
        return {"value": observed, "source": "runtime"}
    if fallback:
        return {"value": fallback, "source": "configuration"}
    return {"value": None, "source": "missing"}


def _configured_driver_name(db_type: str) -> str:
    return {
        "postgresql": "PostgreSQL JDBC Driver",
        "mysql": "MySQL Connector/J",
        "oracle": "Oracle JDBC driver",
        "sqlserver": "Microsoft JDBC Driver for SQL Server",
    }.get(db_type, "")


def _target_label(target: dict[str, Any]) -> str:
    product = target.get("database_product", {}).get("value") or target.get("configured_database_type") or "unknown"
    driver = target.get("jdbc_driver_version", {}).get("value") or "unknown-driver"
    return f"{product} / {driver}"


def _version_in_range(observed: str, expected: Any) -> bool:
    if expected in (None, ""):
        return True
    if isinstance(expected, str):
        return expected.lower() in (observed or "").lower()
    if not isinstance(expected, dict):
        return True
    observed_tuple = _version_tuple(observed)
    if not observed_tuple:
        return True
    minimum = _version_tuple(expected.get("min", ""))
    maximum = _version_tuple(expected.get("max", ""))
    if minimum and observed_tuple < minimum:
        return False
    if maximum and observed_tuple > maximum:
        return False
    return True


def _version_tuple(value: str) -> tuple[int, ...]:
    parts = re.findall(r"\d+", value or "")
    return tuple(int(part) for part in parts[:4])


def _major_minor(value: str) -> tuple[int, int]:
    parts = _version_tuple(value)
    if not parts:
        return (0, 0)
    if len(parts) == 1:
        return (parts[0], 0)
    return (parts[0], parts[1])


def read_project_version(project_root: Path) -> str:
    pom = project_root / "pom.xml"
    if not pom.exists():
        return ""
    text = pom.read_text(encoding="utf-8")
    match = re.search(r"<version>([^<]+)</version>", text)
    return match.group(1) if match else ""


def read_git_commit(project_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=project_root,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


def load_v1_report(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)
