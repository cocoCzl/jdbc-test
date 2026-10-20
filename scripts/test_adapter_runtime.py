import json
import hashlib
import os
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from adapter_runtime import AdapterPackage, build_runtime_config, discover_adapters, load_adapter, resolve_driver, validate_adapter
from build_release import project_version, release_files
from compatibility_v1 import RunKind, aggregate_target_outcome, build_v1_report, collect_preflight_issues
from runner import _assessment_exit_code, _custom_adapter_manifest, _parse_connection_properties, _profile_test_patterns, compare_reports


PROJECT_ROOT = Path(__file__).resolve().parent.parent


class AdapterRuntimeTest(unittest.TestCase):
    def test_all_scenario_requirements_are_declared_by_official_adapters(self):
        from compatibility_v1 import build_scenario_inventory

        required = {
            capability
            for scenario in build_scenario_inventory(PROJECT_ROOT).values()
            for capability in scenario.required_capabilities
        }
        for adapter_id in ("mysql", "postgresql", "oracle", "sqlserver", "gaussdb", "kingbase"):
            package = load_adapter(PROJECT_ROOT, adapter_id)
            self.assertTrue(required <= set(package.manifest["capabilities"]), adapter_id)


    def test_release_files_are_allowlisted_and_patch_versioned(self):
        self.assertEqual("1.2.1", project_version())
        included = {path.relative_to(PROJECT_ROOT).as_posix() for path in release_files()}
        self.assertIn("README.md", included)
        self.assertIn("scripts/runner.py", included)
        forbidden = {
            "CONTEXT.md",
            "config.yaml.example",
            "lib/README.md",
            ".DS_Store",
            ".classpath",
            ".project",
        }
        self.assertTrue(forbidden.isdisjoint(included))
        self.assertFalse(any(path.startswith(("docs/", "report/", "configs/", "target/", "profile/")) for path in included))

    def test_bundled_adapters_are_discoverable_and_valid(self):
        adapters = discover_adapters(PROJECT_ROOT)
        self.assertEqual(
            {"postgresql", "mysql", "oracle", "gaussdb", "sqlserver", "kingbase"},
            set(adapters),
        )
        for adapter_id in adapters:
            self.assertEqual(adapter_id, load_adapter(PROJECT_ROOT, adapter_id).adapter_id)

    def test_local_adapter_path_is_valid_without_java_core_change(self):
        package = load_adapter(PROJECT_ROOT, "examples/adapters/postgresql-local")
        self.assertEqual("postgresql-local", package.adapter_id)
        self.assertEqual("local", package.manifest["trust"])

    def test_kingbase_official_records_real_scope_without_claiming_all_tests_passed(self):
        package = load_adapter(PROJECT_ROOT, "kingbase")
        self.assertEqual("official", package.manifest["trust"])
        combinations = package.manifest["validated_combinations"]
        self.assertEqual(1, len(combinations))
        combination = combinations[0]
        evidence = json.loads((package.root / combination["evidence"]).read_text())
        for field in ("database_version", "driver_version", "server_version", "database_mode"):
            self.assertEqual(evidence[field], combination[field])
        self.assertEqual("FAILED", evidence["run_status"])
        self.assertNotIn("target_outcome", evidence)
        self.assertEqual(270, evidence["selected"])
        self.assertEqual(evidence["selected"], evidence["completed"])
        cases = evidence["non_passing_cases"]
        self.assertEqual(evidence["failed"], sum(c["status"] == "FAILED" for c in cases))
        self.assertEqual(evidence["aborted"], sum(c["status"] == "ABORTED" for c in cases))
        self.assertEqual(270, evidence["passed"] + len(cases))
        self.assertEqual(261, evidence["contracts_invoked"])
        followups = evidence["preparation_followups"]
        pending = {item["scenario_id"]: item for item in followups}
        preparation = {c["scenario_id"]: c for c in cases if c["phase"] == "PREPARATION"}
        self.assertEqual(9, len(followups))
        self.assertEqual(set(preparation), set(pending))
        self.assertEqual(4, sum(f["dependency_kind"] == "direct_missing" for f in followups))
        self.assertEqual(5, sum(f["dependency_kind"] == "blocked_shared_fixture" for f in followups))
        for scenario_id, followup in pending.items():
            self.assertEqual("pending", followup["state"])
            self.assertEqual(preparation[scenario_id]["recorded_conclusion"],
                             followup["recorded_conclusion"])
            self.assertTrue(preparation[scenario_id]["message"].endswith(followup["resource"]))
            for guide in ("development.md", "development.zh-CN.md"):
                self.assertIn(scenario_id, (PROJECT_ROOT / "docs" / guide).read_text())
        self.assertEqual("SUCCESS", evidence["cleanup_status"])
        self.assertEqual(0, evidence["cleanup_failures"])
        self.assertTrue(all(c["review"] for c in cases))
        self.assertFalse(evidence["provenance"]["original_worker_report_available"])
        canonical = json.dumps(evidence["provenance"]["source_records"],
                               ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        self.assertEqual(hashlib.sha256(canonical.encode()).hexdigest(),
                         evidence["provenance"]["source_records_sha256"])

    def test_custom_adapter_reuses_selected_dialect_assets(self):
        for dialect in ("oracle", "mysql", "postgresql"):
            manifest = _custom_adapter_manifest("database-a", dialect, "example.jdbc.Driver", "local-drivers/database-a")
            package = AdapterPackage(PROJECT_ROOT / "configs" / "custom-adapters" / "database-a", manifest)
            validate_adapter(package, PROJECT_ROOT)
            self.assertEqual("local", manifest["trust"])
            self.assertEqual(dialect, manifest["assets"]["directory"])
            self.assertEqual("example.jdbc.Driver", manifest["driver"]["class"])

    def test_local_driver_directory_resolves_all_jars(self):
        adapter = load_adapter(PROJECT_ROOT, "postgresql")
        with tempfile.TemporaryDirectory() as directory:
            driver_dir = Path(directory)
            (driver_dir / "dependency.jar").write_bytes(b"dependency")
            (driver_dir / "driver.jar").write_bytes(b"driver")
            driver = resolve_driver(
                PROJECT_ROOT,
                {"db": {"driver": {"kind": "local", "path": str(driver_dir)}}},
                adapter,
            )
        self.assertEqual("local", driver["source"])
        self.assertEqual(["dependency.jar", "driver.jar"], [Path(path).name for path in driver["classpath"]])
        self.assertTrue(driver["sha256"])

    def test_connection_properties_support_values_and_environment_references(self):
        properties, property_env = _parse_connection_properties(
            ["vendor.app.name=jdbc-test"],
            ["vendor.token=VENDOR_TOKEN"],
        )
        self.assertEqual({"vendor.app.name": "jdbc-test"}, properties)
        self.assertEqual({"vendor.token": "VENDOR_TOKEN"}, property_env)
        with self.assertRaisesRegex(ValueError, "不能重复配置"):
            _parse_connection_properties(["user=develop"], [])

    def test_core_profile_selects_only_reportable_core_methods(self):
        selectors = _profile_test_patterns("core", [], [])
        self.assertTrue(selectors)
        self.assertTrue(all("#test" in selector for selector in selectors))
        self.assertNotIn("com.jdbctest.lifecycle.LifecycleTest#testRequestBoundaries", selectors)
        self.assertIn("com.jdbctest.lifecycle.LifecycleTest#testClosedConnectionRejectsOperations", selectors)

    def test_runtime_resolves_property_environment_without_persisting_reference(self):
        adapter = load_adapter(PROJECT_ROOT, "oracle")
        user = {
            "db": {
                "url": "jdbc:oracle:thin:@example:1521/test",
                "username": "tester",
                "password_env": "TEST_DB_PASSWORD",
                "connection_mode": "driver_manager",
                "properties": {"vendor.app.name": "jdbc-test"},
                "property_env": {"vendor.token": "VENDOR_TOKEN"},
            },
            "namespace": {"mode": "existing", "name": "QA_TEST", "destructive_consent": True},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret", "VENDOR_TOKEN": "token-value"}):
            runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})
        self.assertEqual("driver_manager", runtime["db"]["connection_mode"])
        self.assertEqual("jdbc-test", runtime["db"]["properties"]["vendor.app.name"])
        self.assertEqual("token-value", runtime["db"]["properties"]["vendor.token"])
        self.assertNotIn("property_env", runtime["db"])

    def test_explicit_provider_configuration_reaches_runtime_and_enables_only_configured_interfaces(self):
        adapter = load_adapter(PROJECT_ROOT, "postgresql")
        providers = {"dataSource": {"className": "example.jdbc.Source", "urlProperty": "jdbcUrl"}}
        user = {
            "db": {"url": "jdbc:postgresql://example/test", "username": "tester",
                   "password_env": "TEST_DB_PASSWORD", "data_sources": providers},
            "namespace": {"mode": "auto", "destructive_consent": True},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret"}):
            runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc"})
        self.assertEqual(providers, runtime["db"]["data_sources"])
        self.assertTrue(runtime["adapter"]["capabilities"]["data_source"])
        self.assertFalse(runtime["adapter"]["capabilities"]["connection_pool"])
        self.assertFalse(adapter.manifest["capabilities"]["data_source"])

    def test_runtime_config_resolves_password_without_persisting_connection_identity_in_adapter(self):
        adapter = load_adapter(PROJECT_ROOT, "postgresql")
        user = {
            "db": {"url": "jdbc:postgresql://example/test", "username": "tester", "password_env": "TEST_DB_PASSWORD"},
            "namespace": {"mode": "auto", "destructive_consent": True},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": " fixture-${MISSING}-$\\password "}):
            runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})
        self.assertEqual(" fixture-${MISSING}-$\\password ", runtime["db"]["password"])
        self.assertTrue(runtime["namespace"]["name"].startswith("jdbc_test_"))
        self.assertEqual("official", runtime["adapter"]["trust"])

    def test_identity_and_version_declarations_reach_runtime_for_every_builtin(self):
        for adapter_id in discover_adapters(PROJECT_ROOT):
            adapter = load_adapter(PROJECT_ROOT, adapter_id)
            user = {
                "db": {"url": "jdbc:fixture:test", "username": "fixture", "password_env": "TEST_DB_PASSWORD"},
                "namespace": {"mode": "existing", "name": "owned_scope", "destructive_consent": True},
            }
            with patch.dict(os.environ, {"TEST_DB_PASSWORD": ""}):
                runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "fixture"})
            manifest = adapter.manifest
            self.assertEqual(manifest["identity"]["database_product_regex"], runtime["db"]["expected_database_product_regex"])
            self.assertEqual(manifest["identity"]["driver_name_regex"], runtime["db"]["expected_driver_name_regex"])
            for target in ("database", "driver"):
                for endpoint in ("min", "max"):
                    self.assertEqual(manifest["supported_versions"].get(target, {}).get(endpoint, ""),
                                     runtime["db"][f"{target}_version_{endpoint}"])
            self.assertEqual(manifest["dialect"], runtime["db"]["dialect"])
            self.assertEqual(manifest["assets"].get("directory", adapter_id), runtime["db"]["asset_id"])

    def test_run_requires_explicit_destructive_consent(self):
        adapter = load_adapter(PROJECT_ROOT, "mysql")
        user = {
            "db": {"url": "jdbc:mysql://example/", "username": "tester", "password_env": "TEST_DB_PASSWORD"},
            "namespace": {"mode": "auto", "destructive_consent": False},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret"}):
            with self.assertRaisesRegex(ValueError, "destructive_consent"):
                build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})

    def test_legacy_formal_report_requires_new_evidence_without_silent_promotion(self):
        base = {"formal_eligible": True, "compatibility_baseline_version": "1.0.0",
                "scenario_results": {"connection.open": {"compatibility_status": "passed"}}}
        compared = compare_reports(base, copy.deepcopy(base))
        self.assertFalse(compared["comparable"])
        self.assertEqual(2, compared["exit_code"])
        self.assertTrue(any("evidence" in item for item in compared["errors"]))

    def test_known_difference_records_are_audited_and_official_records_classify_failures(self):
        package = load_adapter(PROJECT_ROOT, "postgresql")
        invalid = copy.deepcopy(package.manifest)
        invalid["trust"] = "local"
        invalid["known_differences"] = [{"scenario_id": "connection.get_auto_commit"}]
        with self.assertRaisesRegex(ValueError, "ADAPTER_DECLARATION_INVALID"):
            validate_adapter(AdapterPackage(package.root, invalid), PROJECT_ROOT)

        config = {
            "db": {"type": "postgresql"},
            "adapter": {"trust": "official", "capabilities": package.manifest["capabilities"], "validated_combinations": [{}]},
            "known_deviations": [{
                "scenario_id": "connection.get_auto_commit",
                "trust": "official",
                "reason": "tracked vendor behavior",
                "issue_url": "https://example.invalid/issue/1",
                "review_after": "2027-01-01",
            }],
            "execution": {"mode": "local"},
            "test_filter": {},
        }
        execution = {"exit_code": 1, "stdout": "", "stderr": ""}
        suites = [{"testcases": [{
            "classname": "com.jdbctest.connection.ConnectionTest",
            "name": "testGetAutoCommit",
            "status": "failure",
            "time_seconds": 0.01,
            "error_message": "contract failed",
        }]}]
        report = build_v1_report(config, execution, suites, {"jdbc.test.databaseProductName": "PostgreSQL"}, PROJECT_ROOT)
        self.assertEqual("known_difference", report["scenario_results"]["connection.get_auto_commit"]["compatibility_status"])
        self.assertEqual(
            "target_compatible",
            aggregate_target_outcome(
                {"connection.get_auto_commit": {"category": "core", "compatibility_status": "known_difference"}},
                RunKind.FORMAL_COMPATIBILITY_EVALUATION,
                {"mismatch": False},
            ).value,
        )

    def test_cleanup_failure_blocks_ci_even_when_scenarios_pass(self):
        report = {
            "scenario_results": {"connection.open": {"compatibility_status": "passed"}},
            "environment_cleanup_issues": [{"asset_type": "namespace"}],
        }
        self.assertEqual(1, _assessment_exit_code(report))

    def test_expired_known_difference_cannot_mask_a_failure(self):
        package = load_adapter(PROJECT_ROOT, "postgresql")
        config = {
            "db": {"type": "postgresql"},
            "adapter": {"trust": "official", "capabilities": package.manifest["capabilities"], "validated_combinations": [{}]},
            "known_deviations": [{
                "scenario_id": "connection.get_auto_commit",
                "trust": "official",
                "database_version": {"min": "1", "max": "99"},
                "driver_version": {"min": "1", "max": "99"},
                "reason": "expired behavior",
                "issue_url": "https://example.invalid/issue/expired",
                "review_after": "2020-01-01",
            }],
            "execution": {"mode": "local"},
            "test_filter": {},
        }
        suites = [{"testcases": [{
            "classname": "com.jdbctest.connection.ConnectionTest",
            "name": "testGetAutoCommit",
            "status": "failure",
            "time_seconds": 0.01,
            "error_message": "contract failed",
        }]}]
        report = build_v1_report(config, {"exit_code": 1, "stdout": "", "stderr": ""}, suites, {
            "jdbc.test.databaseProductName": "PostgreSQL",
            "jdbc.test.databaseProductVersion": "15",
            "jdbc.test.driverVersion": "42",
        }, PROJECT_ROOT)
        self.assertEqual("compatibility_failure", report["scenario_results"]["connection.get_auto_commit"]["compatibility_status"])
        self.assertEqual(1, len(report["expired_known_deviations"]))

    def test_structured_missing_privilege_is_collected(self):
        execution = {
            "stderr": '[JDBC_PREFLIGHT_ISSUE] {"kind":"missing_privilege","privilege":"CREATE TABLE","message":"denied"}'
        }
        self.assertEqual("CREATE TABLE", collect_preflight_issues(execution)[0]["privilege"])


if __name__ == "__main__":
    unittest.main()
