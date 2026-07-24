import json
import os
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from adapter_runtime import AdapterPackage, build_runtime_config, discover_adapters, load_adapter, resolve_driver, validate_adapter
from build_release import project_version, release_files
from compatibility_v1 import RunKind, aggregate_target_outcome, build_v1_report, collect_preflight_issues
from runner import _assessment_exit_code, _custom_adapter_manifest, _parse_connection_properties, compare_reports


PROJECT_ROOT = Path(__file__).resolve().parent.parent


class AdapterRuntimeTest(unittest.TestCase):

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
            {"postgresql", "mysql", "oracle", "gaussdb", "sqlserver"},
            set(adapters),
        )
        for adapter_id in adapters:
            self.assertEqual(adapter_id, load_adapter(PROJECT_ROOT, adapter_id).adapter_id)

    def test_local_adapter_path_is_valid_without_java_core_change(self):
        package = load_adapter(PROJECT_ROOT, "examples/adapters/postgresql-local")
        self.assertEqual("postgresql-local", package.adapter_id)
        self.assertEqual("local", package.manifest["trust"])

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
            "namespace": {"mode": "existing", "destructive_consent": True},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret", "VENDOR_TOKEN": "token-value"}):
            runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})
        self.assertEqual("driver_manager", runtime["db"]["connection_mode"])
        self.assertEqual("jdbc-test", runtime["db"]["properties"]["vendor.app.name"])
        self.assertEqual("token-value", runtime["db"]["properties"]["vendor.token"])
        self.assertNotIn("property_env", runtime["db"])

    def test_runtime_config_resolves_password_without_persisting_connection_identity_in_adapter(self):
        adapter = load_adapter(PROJECT_ROOT, "postgresql")
        user = {
            "db": {"url": "jdbc:postgresql://example/test", "username": "tester", "password_env": "TEST_DB_PASSWORD"},
            "namespace": {"mode": "auto", "destructive_consent": True},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret"}):
            runtime = build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})
        self.assertEqual("secret", runtime["db"]["password"])
        self.assertTrue(runtime["namespace"]["name"].startswith("jdbc_test_"))
        self.assertEqual("official", runtime["adapter"]["trust"])

    def test_run_requires_explicit_destructive_consent(self):
        adapter = load_adapter(PROJECT_ROOT, "mysql")
        user = {
            "db": {"url": "jdbc:mysql://example/", "username": "tester", "password_env": "TEST_DB_PASSWORD"},
            "namespace": {"mode": "auto", "destructive_consent": False},
        }
        with patch.dict(os.environ, {"TEST_DB_PASSWORD": "secret"}):
            with self.assertRaisesRegex(ValueError, "destructive_consent"):
                build_runtime_config(PROJECT_ROOT, user, adapter, {"sha256": "abc", "path": "/tmp/driver.jar"})

    def test_compare_blocks_new_failure_and_rejects_cross_suite_versions(self):
        base = {
            "formal_eligible": True,
            "compatibility_baseline_version": "1.0.0",
            "scenario_results": {"connection.open": {"compatibility_status": "passed"}},
        }
        current = {
            "formal_eligible": True,
            "compatibility_baseline_version": "1.0.0",
            "scenario_results": {"connection.open": {"compatibility_status": "compatibility_failure"}},
        }
        compared = compare_reports(base, current)
        self.assertTrue(compared["comparable"])
        self.assertTrue(compared["blocking_changes"])

        current["compatibility_baseline_version"] = "2.0.0"
        self.assertFalse(compare_reports(base, current)["comparable"])

    def test_known_difference_records_are_audited_and_official_records_classify_failures(self):
        package = load_adapter(PROJECT_ROOT, "postgresql")
        invalid = copy.deepcopy(package.manifest)
        invalid["trust"] = "local"
        invalid["known_differences"] = [{"scenario_id": "connection.get_auto_commit"}]
        with self.assertRaisesRegex(ValueError, "缺少"):
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
