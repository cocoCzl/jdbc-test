"""Synthetic evidence and CLI contracts only; these tests never connect to a database."""
import copy
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import regression
import runner
from compatibility_v1 import assess_capabilities, normalize_status, execution_conclusion


def synthetic_report(trust="local"):
    evidence = {field: regression.fingerprint("fixture-" + field) for field in regression.REQUIRED}
    evidence.update(version=1, purpose="driver-upgrade", selected_scenarios=["a", "b"],
                    driver_artifacts=[{"name": "driver-a.jar", "sha256": "a" * 64}],
                    identity_source="operator_attestation", capture_errors=[])
    return {"report_schema_version": "1.0.0", "outcome_semantics_version": 2,
            "compatibility_baseline_version": "1.1.0", "adapter": {"id": "private-db", "trust": trust},
            "formal_eligible": trust == "official", "regression_evidence": evidence,
            "diagnostics": {"maven_exit_code": 0},
            "scenario_results": {name: {"compatibility_status": "passed", "phase": "EXECUTION",
                                         "execution_status": "passed", "conclusion": "CONFORMS",
                                         "contract_conforms": True} for name in ("a", "b")}}


def fail(report, name="b"):
    report["scenario_results"][name].update(compatibility_status="compatibility_failure",
                                           execution_status="failure", conclusion="CONTRACT_VIOLATION",
                                           contract_conforms=False)
    report["diagnostics"]["maven_exit_code"] = 1


class RegressionTest(unittest.TestCase):
    def test_extra_suite_failure_is_separate_from_jdbc_failure_and_execution_issues(self):
        base = synthetic_report()
        current = copy.deepcopy(base)
        fail(current)
        current["scenario_results"]["b"].update(conclusion="NOT_EVALUATED", requirement_scope="SUITE")
        current["scenario_results"]["b"].pop("contract_conforms")
        result = regression.compare(regression.select_baseline(base), current)
        self.assertEqual(1, result["exit_code"])
        self.assertEqual([], result["new_failures"])
        self.assertEqual([], result["execution_issues"])
        self.assertEqual(["b"], result["suite_requirements"]["new_unmet"])
        self.assertFalse(result["all_selected_passed"])
        snapshot = regression.select_baseline(current)
        unchanged = regression.compare(snapshot, current)
        self.assertEqual(0, unchanged["exit_code"])
        self.assertEqual(["b"], unchanged["suite_requirements"]["unmet"])
        self.assertEqual([], unchanged["unresolved_failures"])
        self.assertFalse(unchanged["all_selected_passed"])
        resolved = regression.compare(snapshot, base)
        self.assertEqual(["b"], resolved["suite_requirements"]["resolved"])
        self.assertEqual([], resolved["fixed"])
        # Unknown SQL/preparation failures cannot opt in merely by carrying a scope label.
        current["scenario_results"]["b"]["execution_status"] = "error"
        self.assertEqual(2, regression.compare(regression.select_baseline(base), current)["exit_code"])

    def test_historical_sql_reports_remain_excluded_after_package_retirement(self):
        report = synthetic_report()
        report["testDomain"] = "SQL_FEATURE"
        with self.assertRaisesRegex(ValueError, "SQL_FEATURE"):
            regression.select_baseline(report)
        result = runner.compare_reports(report, report, "internal")
        self.assertEqual(2, result["exit_code"])
        self.assertFalse(result["comparable"])
        self.assertTrue(any("SQL_FEATURE" in reason for reason in result["errors"]))

    def test_local_baseline_keeps_provenance_and_allows_driver_and_dependency_jar_upgrade(self):
        base = synthetic_report()
        snapshot = regression.select_baseline(base)
        current = copy.deepcopy(base)
        current["regression_evidence"].update(driver_version="B", driver_name="new name",
                                               driver_artifacts=[{"name": "driver-b.jar", "sha256": "b" * 64},
                                                                 {"name": "driver-dep.jar", "sha256": "c" * 64}])
        result = runner.compare_reports(snapshot, current, "internal")
        self.assertTrue(result["comparable"], result["errors"])
        self.assertEqual(0, result["exit_code"])
        self.assertEqual("local", result["baseline"]["adapter"]["trust"])
        self.assertFalse(result["baseline"]["formal_eligible"])
        self.assertEqual(["driver_version", "driver_name", "driver_artifacts"], result["allowed_changes"])

    def test_new_failure_fix_and_existing_failure_are_distinct(self):
        base = synthetic_report()
        fail(base, "a")
        current = copy.deepcopy(base)
        result = regression.compare(regression.select_baseline(base), current)
        self.assertEqual(0, result["exit_code"])
        self.assertEqual(["a"], result["unresolved_failures"])
        self.assertFalse(result["all_selected_passed"])
        fail(current)
        result = regression.compare(regression.select_baseline(base), current)
        self.assertEqual(1, result["exit_code"])
        self.assertEqual(["b"], result["new_failures"])
        fixed = synthetic_report()
        result = regression.compare(regression.select_baseline(base), fixed)
        self.assertEqual(["a"], result["fixed"])

    def test_all_fixed_environment_dimensions_block_even_with_matching_suite_version(self):
        base = synthetic_report()
        for field in regression.REQUIRED:
            if field in {"driver_version", "driver_name", "driver_artifacts", "selected_scenarios"}:
                continue
            with self.subTest(field=field):
                current = copy.deepcopy(base)
                current["regression_evidence"][field] = "changed"
                result = regression.compare(regression.select_baseline(base), current)
                self.assertFalse(result["comparable"])
                self.assertEqual(2, result["exit_code"])
                self.assertTrue(any(field in error for error in result["errors"]))

    def test_missing_evidence_and_legacy_reports_fail_closed(self):
        base = synthetic_report()
        for field in regression.REQUIRED:
            with self.subTest(field=field):
                current = copy.deepcopy(base)
                del current["regression_evidence"][field]
                self.assertEqual(2, regression.compare(regression.select_baseline(base), current)["exit_code"])
        legacy = {"formal_eligible": True, "compatibility_baseline_version": "1.1.0"}
        self.assertEqual(2, runner.compare_reports(legacy, legacy)["exit_code"])
        with self.assertRaisesRegex(ValueError, "version/purpose"):
            regression.select_baseline(legacy)

    def test_selection_removal_addition_and_missing_results_cannot_pass(self):
        base = synthetic_report()
        for mutate in (lambda r: r["regression_evidence"]["selected_scenarios"].remove("b"),
                       lambda r: r["regression_evidence"]["selected_scenarios"].append("new"),
                       lambda r: r["scenario_results"].pop("b")):
            current = copy.deepcopy(base)
            mutate(current)
            result = regression.compare(regression.select_baseline(base), current)
            self.assertFalse(result["no_new_regressions"])
            self.assertEqual(2, result["exit_code"])

    def test_execution_problems_block_even_when_unchanged(self):
        for status in regression.ISSUES:
            base = synthetic_report()
            current = copy.deepcopy(base)
            current["scenario_results"]["b"].update(compatibility_status=status, phase="PREPARATION",
                                                    conclusion="PREREQUISITE_UNMET")
            result = regression.compare(regression.select_baseline(base), current)
            self.assertEqual(2, result["exit_code"], status)
            self.assertTrue(result["execution_issues"])
            with self.assertRaises(ValueError):
                regression.select_baseline(current)
        for field in ("environment_cleanup_issues", "environment_preflight_issues"):
            current = synthetic_report()
            current[field] = [{"kind": "fixture issue"}]
            self.assertEqual(2, regression.compare(regression.select_baseline(synthetic_report()), current)["exit_code"])

    def test_legally_unsupported_can_still_be_a_support_regression(self):
        base = synthetic_report()
        current = copy.deepcopy(base)
        current["scenario_results"]["b"].update(compatibility_status="known_unsupported",
                                                execution_status="skipped", conclusion="UNSUPPORTED_ALLOWED")
        result = regression.compare(regression.select_baseline(base), current)
        self.assertEqual(1, result["exit_code"])
        self.assertEqual(["b"], result["support_losses"])
        self.assertFalse(result["all_selected_passed"])

    def test_formal_eligibility_is_still_required_only_for_formal_comparison(self):
        base = synthetic_report("official")
        self.assertEqual(0, runner.compare_reports(base, copy.deepcopy(base))["exit_code"])
        local = synthetic_report()
        self.assertEqual(2, runner.compare_reports(local, local)["exit_code"])
        self.assertEqual(2, regression.compare(local, local)["exit_code"])

    def test_suite_version_change_and_tampering_block(self):
        base = synthetic_report()
        current = copy.deepcopy(base)
        current["compatibility_baseline_version"] = "2.0.0"
        self.assertEqual(2, regression.compare(regression.select_baseline(base), current)["exit_code"])
        snapshot = regression.select_baseline(base)
        snapshot["report"]["adapter"]["trust"] = "official"
        self.assertEqual(2, regression.compare(snapshot, current)["exit_code"])

    def test_false_declarations_actual_success_and_plan_are_separate(self):
        rows = {"savepoint.set_savepoint": {"capability_evidence":
                {"metadata.savepoint": "false", "behavior.savepoint": "true"}}}
        config = {"plan_requirements": {"required_capabilities": ["savepoint"]}}
        assessment = assess_capabilities(rows, {"savepoint": False}, config)
        savepoint = next(x for x in assessment["capabilities"] if x["capability"] == "savepoint")
        self.assertEqual("met", savepoint["requirement_status"])
        self.assertTrue(savepoint["declaration_mismatch"])
        self.assertFalse(savepoint["adapter_declaration"])
        rows["savepoint.set_savepoint"]["capability_evidence"]["behavior.savepoint"] = "false"
        self.assertEqual(["savepoint"], assess_capabilities(rows, {}, config)["unmet"])
        rows["savepoint.set_savepoint"]["capability_evidence"].pop("behavior.savepoint")
        self.assertEqual(["savepoint"], assess_capabilities(rows, {}, config)["not_evaluated"])
        for error_type in ("com.jdbctest.extension.ExecutionPrerequisiteException", "java.lang.IllegalStateException"):
            case = {"status": "error", "error_type": error_type}
            self.assertNotIn(normalize_status(case, {"savepoint": False}), {"passed", "known_unsupported"})
            self.assertNotIn(execution_conclusion(case)["conclusion"], {"CONFORMS", "UNSUPPORTED_ALLOWED"})

    def test_cli_baseline_selection_compare_and_exit_codes(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-regression-contract-") as directory:
            root = Path(directory)
            source, baseline, current, output = (root / x for x in ("a.json", "baseline.json", "b.json", "diff.json"))
            report = synthetic_report()
            fail(report, "a")
            source.write_text(json.dumps(report))
            current.write_text(json.dumps(report))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, runner.main(["baseline", "create", "--report", str(source), "--output", str(baseline)]))
                self.assertEqual(2, runner.main(["baseline", "create", "--report", str(source), "--output", str(baseline)]))
                command = ["compare", "--scope", "internal", "--baseline", str(baseline),
                           "--current", str(current), "--output", str(output)]
                self.assertEqual(0, runner.main(command))
                fail(report)
                current.write_text(json.dumps(report))
                self.assertEqual(1, runner.main(command))
                self.assertEqual(["a"], json.loads(output.read_text())["unresolved_failures"])
                current.write_text("broken json")
                self.assertEqual(2, runner.main(command))
            self.assertEqual("local", json.loads(source.read_text())["adapter"]["trust"])

    def test_comparison_key_is_not_in_child_environment_or_archived_diagnostics(self):
        import types
        key = 'synthetic-comparison-key-"\n' + "x" * 40
        with tempfile.TemporaryDirectory(prefix="jdbc-key-contract-") as directory:
            runtime = {"db": {"adapter_id": "fixture"}, "report": {"output_dir": directory},
                       "regression": {"environment_key_env": "FIXTURE_COMPARISON_KEY"},
                       "integration_report_directory": directory, "test_filter": {}, "test_profile": "full"}
            with patch.dict(os.environ, {"FIXTURE_COMPARISON_KEY": key, "JDBC_TEST_REGRESSION_KEY": "another-private-key"}), \
                 patch.object(runner.subprocess, "run", return_value=types.SimpleNamespace(
                     returncode=0, stdout="", stderr="")) as execute, \
                 patch.object(runner, "_profile_test_patterns", return_value=[]):
                runner._run_maven(runtime, Path(directory) / "runtime.json", [])
                self.assertNotIn("FIXTURE_COMPARISON_KEY", execute.call_args.kwargs["env"])
                self.assertNotIn("JDBC_TEST_REGRESSION_KEY", execute.call_args.kwargs["env"])
                # Even an unexpected echo in captured output must not enter the retained log/report.
                report = synthetic_report()
                report["diagnostics"]["unexpected_echo"] = key
                target = runner._archive_report(runtime, report, {"stdout": key}, [{"output": key}])
                for path in target.iterdir():
                    self.assertNotIn(key, path.read_text(), path.name)
                    self.assertNotIn(json.dumps(key)[1:-1], path.read_text(), path.name)
                self.assertEqual("[REDACTED]", json.loads((target / "report.json").read_text())
                                 ["diagnostics"]["unexpected_echo"])

    def test_runtime_capture_fingerprints_all_jars_without_persisting_secrets(self):
        from adapter_runtime import build_runtime_config, load_adapter, sha256_bundle
        with tempfile.TemporaryDirectory(prefix="jdbc-evidence-contract-") as directory:
            root = Path(directory)
            jars = [root / name for name in ("driver.jar", "dep.jar", "framework.jar")]
            for path in jars:
                path.write_bytes(path.name.encode())
            artifact = {"classpath": [str(p) for p in jars[:2]], "sha256": sha256_bundle(jars[:2])}
            user = {"db": {"url": "jdbc:postgresql://fixture/private", "username": "fixture-user",
                           "password_env": "FIXTURE_DB_PASSWORD", "properties": {"secret": "fixture-property"}},
                    "namespace": {"mode": "auto", "destructive_consent": True},
                    "regression": {"database_identity": "isolated-fixture", "server_build": "1", "database_mode": "native"}}
            with patch.dict(os.environ, {"FIXTURE_DB_PASSWORD": "fixture-password",
                                         "JDBC_TEST_REGRESSION_KEY": "k" * 32}):
                config = build_runtime_config(runner.PROJECT_ROOT, user,
                                              load_adapter(runner.PROJECT_ROOT, "postgresql"), artifact)
                again = build_runtime_config(runner.PROJECT_ROOT, user,
                                             load_adapter(runner.PROJECT_ROOT, "postgresql"), artifact)
                self.assertEqual(config["regression_inputs"], again["regression_inputs"])
                env = {"surefire.test.class.path": os.pathsep.join(str(p) for p in jars)}
                captured = regression.build_evidence(runner.PROJECT_ROOT, config, env, {"a"})
                self.assertEqual(2, len(captured["driver_artifacts"]))
                self.assertFalse(captured["capture_errors"], captured["capture_errors"])
                jars[0].write_bytes(b"changed-after-resolution")
                changed_capture = regression.build_evidence(runner.PROJECT_ROOT, config, env, {"a"})
                self.assertIn("driver artifacts changed after resolution", changed_capture["capture_errors"])
                encoded = json.dumps(captured)
                for secret in ("fixture-password", "fixture-property", "fixture-user", "jdbc:postgresql", "k" * 32):
                    self.assertNotIn(secret, encoded)
                user["db"]["properties"]["secret"] = "different-value"
                changed = build_runtime_config(runner.PROJECT_ROOT, user,
                                               load_adapter(runner.PROJECT_ROOT, "postgresql"), artifact)
                self.assertNotEqual(config["regression_inputs"]["config_hmac"], changed["regression_inputs"]["config_hmac"])

    def test_unexplained_maven_errors_and_unknown_statuses_do_not_compare_as_passes(self):
        base = synthetic_report()
        for mutate in (lambda r: r["diagnostics"].update(maven_exit_code=1),
                       lambda r: r["scenario_results"]["b"].update(compatibility_status="future_status"),
                       lambda r: r["scenario_results"]["b"].update(conclusion="PLATFORM_ERROR")):
            current = copy.deepcopy(base)
            mutate(current)
            result = regression.compare(regression.select_baseline(base), current)
            self.assertEqual(2, result["exit_code"])
            self.assertFalse(result["all_selected_passed"])

    def test_new_unmet_plan_requirement_is_separate_from_a_contract_failure(self):
        base = synthetic_report()
        base["plan_assessment"] = {"unmet": [], "not_evaluated": []}
        current = copy.deepcopy(base)
        current["scenario_results"]["b"].update(compatibility_status="known_unsupported",
                                                execution_status="skipped", conclusion="UNSUPPORTED_ALLOWED")
        current["plan_assessment"]["unmet"] = ["savepoint"]
        result = regression.compare(regression.select_baseline(base), current)
        self.assertEqual(1, result["exit_code"])
        self.assertEqual(["savepoint"], result["new_unmet_requirements"])
        self.assertEqual([], result["new_failures"])
        self.assertTrue(current["scenario_results"]["b"]["contract_conforms"])

    def test_comparison_never_overwrites_its_input_report(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-readonly-report-") as directory:
            path = Path(directory) / "report.json"
            report = json.dumps(synthetic_report("official"))
            path.write_text(report)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, runner.main(["compare", "--baseline", str(path), "--current", str(path),
                                                "--output", str(path)]))
            self.assertEqual(report, path.read_text())

    def test_surefire_capability_and_target_evidence_are_parsed(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-xml-contract-") as directory:
            root = Path(directory)
            (root / "TEST-fixture.xml").write_text('''<testsuite>
              <testcase name="testSupportsSavepoints" classname="fixture">
                <system-out>[JDBC_CAPABILITY] metadata.savepoint=false
[JDBC_TARGET] {"databaseProductName":"FixtureDB","driverVersion":"A"}</system-out>
              </testcase></testsuite>''')
            with patch.object(runner, "_surefire_reports_directory", return_value=root):
                suites, env = runner._parse_surefire_reports()
            self.assertEqual("false", suites[0]["testcases"][0]["capability_evidence"]["metadata.savepoint"])
            self.assertEqual("FixtureDB", env["jdbc.test.databaseProductName"])


if __name__ == "__main__":
    unittest.main()
