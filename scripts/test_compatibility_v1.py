import unittest
from pathlib import Path
import sys
import tempfile
import json

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

import runner
from compatibility_v1 import (
    COMPATIBILITY_BASELINE_VERSION,
    REPORT_SCHEMA_VERSION,
    CompatibilityStatus,
    ScenarioCategory,
    TargetOutcome,
    aggregate_target_outcome,
    build_scenario_inventory,
    build_v1_report,
    class_setup_errors,
    classify_run,
    collect_cleanup_issues,
    discover_source_scenarios,
    enrich_test_cases,
    generate_matrix,
    load_scenario_inventory,
    normalize_status,
    validate_inventory_covers_source,
    vendor_extension_report,
)


class CompatibilityV1Test(unittest.TestCase):
    def test_stable_enum_values(self):
        self.assertEqual("1.1.0", COMPATIBILITY_BASELINE_VERSION)
        self.assertEqual("1.0.0", REPORT_SCHEMA_VERSION)
        self.assertEqual(
            {
                "target_compatible",
                "target_not_compatible",
                "evaluation_inconclusive",
                "needs_re_evaluation",
            },
            {item.value for item in TargetOutcome},
        )
        self.assertEqual(
            {
                "passed",
                "compatibility_failure",
                "known_unsupported",
                "unknown_capability",
                "skipped",
                "not_run",
                "execution_error",
                "capability_declaration_mismatch",
                "observed",
                "known_difference",
                "adapter_incomplete",
                "cleanup_failure",
            },
            {item.value for item in CompatibilityStatus},
        )
        self.assertEqual(
            {"core", "extended", "observational", "vendor_extension"},
            {item.value for item in ScenarioCategory},
        )

    def test_inventory_maps_reportable_methods(self):
        inventory = build_scenario_inventory(PROJECT_ROOT)
        try:
            file_inventory = load_scenario_inventory(PROJECT_ROOT / "compatibility/v1/scenario-inventory.yaml")
        except RuntimeError:
            # The launcher remains usable without PyYAML; source discovery is its fallback contract.
            file_inventory = discover_source_scenarios(PROJECT_ROOT)

        self.assertIn(
            ("com.jdbctest.connection.ConnectionTest", "testGetAutoCommit"),
            inventory,
        )
        self.assertEqual(set(file_inventory), set(inventory))
        self.assertIn(
            ("com.jdbctest.sqlxml.SQLXMLTest", "testGetSQLXML"),
            inventory,
        )
        self.assertEqual(
            "connection.get_auto_commit",
            inventory[("com.jdbctest.connection.ConnectionTest", "testGetAutoCommit")].scenario_id,
        )
        self.assertEqual(
            "extended",
            inventory[("com.jdbctest.sqlxml.SQLXMLTest", "testGetSQLXML")].category.value,
        )
        self.assertTrue(all(s.contract for s in inventory.values()))

    def test_inventory_validation_fails_when_reportable_method_missing(self):
        source = discover_source_scenarios(PROJECT_ROOT)
        incomplete = dict(source)
        incomplete.pop(("com.jdbctest.connection.ConnectionTest", "testGetAutoCommit"))

        with self.assertRaisesRegex(ValueError, "missing reportable test methods"):
            validate_inventory_covers_source(source, incomplete)

    def test_enrich_surfaces_unmapped_test_cases(self):
        inventory = build_scenario_inventory(PROJECT_ROOT)
        suites = [
            {
                "testcases": [
                    {
                        "classname": "com.jdbctest.connection.ConnectionTest",
                        "name": "testGetAutoCommit",
                        "status": "passed",
                        "time_seconds": 0.01,
                    },
                    {
                        "classname": "com.jdbctest.extension.SqlSplitterTest",
                        "name": "semicolonSplitterIgnoresStringsAndComments",
                        "status": "passed",
                        "time_seconds": 0.01,
                    },
                ]
            }
        ]

        enriched, unmapped = enrich_test_cases(suites, inventory)

        self.assertEqual(1, len(enriched))
        self.assertEqual("connection.get_auto_commit", enriched[0]["scenario_id"])
        self.assertEqual(1, len(unmapped))
        self.assertEqual("com.jdbctest.extension.SqlSplitterTest", unmapped[0]["class_name"])

    def test_normalizes_statuses(self):
        core = {"status": "failure", "category": "core", "required_capabilities": []}
        self.assertEqual("compatibility_failure", normalize_status(core, {}))

        error = {"status": "error", "category": "core", "required_capabilities": []}
        self.assertEqual("execution_error", normalize_status(error, {}))

        extended_unknown = {
            "status": "skipped",
            "category": "extended",
            "required_capabilities": ["sqlxml"],
        }
        self.assertEqual("unknown_capability", normalize_status(extended_unknown, {}))

        extended_false = {
            "status": "skipped",
            "category": "extended",
            "required_capabilities": ["sqlxml"],
        }
        self.assertEqual("known_unsupported", normalize_status(extended_false, {"sqlxml": False}))

        extended_mismatch = {
            "status": "skipped",
            "category": "extended",
            "required_capabilities": ["sqlxml"],
        }
        self.assertEqual(
            "capability_declaration_mismatch",
            normalize_status(extended_mismatch, {"sqlxml": True}),
        )

    def test_aggregates_target_outcome_from_core_statuses(self):
        passed = {
            "connection.get_auto_commit": {
                "category": "core",
                "compatibility_status": "passed",
            },
            "sqlxml.get_sqlxml": {
                "category": "extended",
                "compatibility_status": "known_unsupported",
            },
        }
        self.assertEqual(
            TargetOutcome.TARGET_COMPATIBLE,
            aggregate_target_outcome(passed, classify_run({"test_filter": {}}), {"mismatch": False}),
        )

        failed = {
            "connection.get_auto_commit": {
                "category": "core",
                "compatibility_status": "compatibility_failure",
            }
        }
        self.assertEqual(
            TargetOutcome.TARGET_NOT_COMPATIBLE,
            aggregate_target_outcome(failed, classify_run({"test_filter": {}}), {"mismatch": False}),
        )

    def test_builds_v1_report_shape(self):
        config = {
            "db": {"type": "postgresql", "url": "jdbc:postgresql://example/test", "username": "develop"},
            "execution": {"mode": "local"},
            "test_filter": {},
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 0,
            "stdout": "",
            "stderr": "",
        }
        suites = [
            {
                "testcases": [
                    {
                        "classname": "com.jdbctest.connection.ConnectionTest",
                        "name": "testGetAutoCommit",
                        "status": "passed",
                        "time_seconds": 0.01,
                    }
                ]
            }
        ]
        env = {
            "jdbc.test.databaseProductName": "PostgreSQL",
            "jdbc.test.databaseProductVersion": "16",
            "jdbc.test.driverName": "PostgreSQL JDBC Driver",
            "jdbc.test.driverVersion": "42.7.2",
            "jdbc.test.jdbcUrl": "jdbc:postgresql://example/test",
            "java.version": "21",
        }

        report = build_v1_report(config, execution, suites, env, PROJECT_ROOT)

        self.assertEqual(REPORT_SCHEMA_VERSION, report["report_schema_version"])
        self.assertEqual(COMPATIBILITY_BASELINE_VERSION, report["compatibility_baseline_version"])
        self.assertEqual("formal_compatibility_evaluation", report["run_kind"])
        self.assertIn("connection.get_auto_commit", report["scenario_results"])
        self.assertEqual(
            "passed",
            report["scenario_results"]["connection.get_auto_commit"]["compatibility_status"],
        )
        self.assertEqual(1, report["coverage_summary"]["by_area"]["connection"]["passed"])
        self.assertEqual("PostgreSQL", report["compatibility_target"]["database_product"]["value"])

    def test_target_identity_mismatch_and_diagnostic_run_are_inconclusive(self):
        config = {
            "db": {
                "type": "postgresql",
                "url": "jdbc:postgresql://example/test",
                "username": "develop",
                "expected_database_product": "Oracle",
            },
            "execution": {"mode": "local"},
            "test_filter": {"include_tests": ["ConnectionTest"]},
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 0,
            "stdout": "",
            "stderr": "",
        }
        suites = [
            {
                "testcases": [
                    {
                        "classname": "com.jdbctest.connection.ConnectionTest",
                        "name": "testGetAutoCommit",
                        "status": "passed",
                        "time_seconds": 0.01,
                    }
                ]
            }
        ]
        env = {"jdbc.test.databaseProductName": "PostgreSQL"}

        report = build_v1_report(config, execution, suites, env, PROJECT_ROOT)

        self.assertEqual("diagnostic_run", report["run_kind"])
        self.assertTrue(report["target_identity_mismatch"]["mismatch"])
        self.assertEqual("evaluation_inconclusive", report["target_outcome"])

    def test_known_deviation_and_cleanup_do_not_change_status_or_outcome(self):
        config = {
            "db": {
                "type": "postgresql",
                "url": "jdbc:postgresql://example/test",
                "username": "develop",
            },
            "execution": {"mode": "local"},
            "test_filter": {},
            "known_deviations": [
                {
                    "scenario_id": "connection.get_auto_commit",
                    "database_product": "PostgreSQL",
                    "database_version": {"min": "15", "max": "17"},
                    "explanation": "fixture deviation",
                }
            ],
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 1,
            "stdout": "",
            "stderr": '[JDBC_CLEANUP_ISSUE] {"asset_type":"table","asset_name":"t","message":"drop failed"}',
        }
        suites = [
            {
                "testcases": [
                    {
                        "classname": "com.jdbctest.connection.ConnectionTest",
                        "name": "testGetAutoCommit",
                        "status": "failure",
                        "time_seconds": 0.01,
                        "error_message": "contract failed",
                    }
                ]
            }
        ]
        env = {
            "jdbc.test.databaseProductName": "PostgreSQL",
            "jdbc.test.databaseProductVersion": "16.1",
        }

        report = build_v1_report(config, execution, suites, env, PROJECT_ROOT)

        result = report["scenario_results"]["connection.get_auto_commit"]
        self.assertEqual("compatibility_failure", result["compatibility_status"])
        self.assertEqual("target_not_compatible", report["target_outcome"])
        self.assertEqual("fixture deviation", result["known_deviations"][0]["explanation"])
        self.assertEqual("drop failed", report["environment_cleanup_issues"][0]["message"])

    def test_cleanup_issue_collection_from_suite_output(self):
        issues = collect_cleanup_issues(
            {"stderr": ""},
            [{"system_err": '[JDBC_CLEANUP_ISSUE] {"asset_name":"x","message":"failed"}'}],
        )

        self.assertEqual("x", issues[0]["asset_name"])

    def test_cleanup_issue_does_not_mutate_passed_scenario_or_target_outcome(self):
        cases_by_class = {}
        for class_name, method_name in discover_source_scenarios(PROJECT_ROOT):
            cases_by_class.setdefault(class_name, []).append(
                {
                    "classname": class_name,
                    "name": method_name,
                    "status": "passed",
                    "time_seconds": 0.01,
                }
            )
        suites = [
            {
                "name": class_name,
                "time_seconds": 0.01 * len(testcases),
                "total": len(testcases),
                "failures": 0,
                "errors": 0,
                "skipped": 0,
                "system_err": '[JDBC_CLEANUP_ISSUE] {"asset_name":"x","message":"failed"}'
                if class_name == "com.jdbctest.connection.ConnectionTest"
                else "",
                "testcases": testcases,
            }
            for class_name, testcases in cases_by_class.items()
        ]
        config = {
            "db": {"type": "postgresql", "url": "jdbc:postgresql://example/test", "username": "develop"},
            "execution": {"mode": "local"},
            "test_filter": {},
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 0,
            "stdout": "",
            "stderr": "",
        }
        env = {"jdbc.test.databaseProductName": "PostgreSQL"}

        report = build_v1_report(config, execution, suites, env, PROJECT_ROOT)

        self.assertEqual("passed", report["scenario_results"]["connection.get_auto_commit"]["compatibility_status"])
        self.assertEqual("target_compatible", report["target_outcome"])
        self.assertEqual("failed", report["environment_cleanup_issues"][0]["message"])

    def test_matrix_excludes_vendor_extensions(self):
        report = {
            "compatibility_baseline_version": "1.0.0",
            "compatibility_target": {
                "database_product": {"value": "PostgreSQL"},
                "jdbc_driver_version": {"value": "42.7.2"},
            },
            "scenario_results": {
                "connection.get_auto_commit": {
                    "scenario_id": "connection.get_auto_commit",
                    "category": "core",
                    "compatibility_status": "passed",
                },
                "postgresql.copy_api": {
                    "scenario_id": "postgresql.copy_api",
                    "category": "vendor_extension",
                    "compatibility_status": "observed",
                },
            },
        }

        matrix = generate_matrix([report])
        vendor = vendor_extension_report([report])

        self.assertEqual(["connection.get_auto_commit"], [row["scenario_id"] for row in matrix["rows"]])
        self.assertEqual("passed", matrix["rows"][0]["cells"]["PostgreSQL / 42.7.2"])
        self.assertEqual("postgresql.copy_api", vendor["targets"][0]["scenario_results"][0]["scenario_id"])

    def test_matrix_flags_only_major_minor_baseline_mismatch(self):
        base = {
            "compatibility_baseline_version": "1.0.0",
            "compatibility_target": {"database_product": {"value": "A"}, "jdbc_driver_version": {"value": "1"}},
            "scenario_results": {},
        }
        patch = {
            "compatibility_baseline_version": "1.0.1",
            "compatibility_target": {"database_product": {"value": "B"}, "jdbc_driver_version": {"value": "1"}},
            "scenario_results": {},
        }
        minor = {
            "compatibility_baseline_version": "1.1.0",
            "compatibility_target": {"database_product": {"value": "C"}, "jdbc_driver_version": {"value": "1"}},
            "scenario_results": {},
        }

        self.assertFalse(generate_matrix([base, patch])["mixed_incompatible_baselines"])
        self.assertTrue(generate_matrix([base, minor])["mixed_incompatible_baselines"])

    def test_class_setup_error_marks_class_scenarios_execution_error(self):
        config = {
            "db": {"type": "mysql", "url": "jdbc:mysql://example/", "username": "root"},
            "execution": {"mode": "local"},
            "test_filter": {},
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 1,
            "stdout": "",
            "stderr": "",
        }
        suites = [
            {
                "testcases": [
                    {
                        "classname": "com.jdbctest.connection.ConnectionTest",
                        "name": "",
                        "status": "error",
                        "time_seconds": 0.01,
                        "error_message": "SQL file failed",
                        "error_type": "java.lang.RuntimeException",
                        "error_text": "No database selected",
                    }
                ]
            }
        ]

        self.assertIn("com.jdbctest.connection.ConnectionTest", class_setup_errors(suites))
        report = build_v1_report(config, execution, suites, {}, PROJECT_ROOT)

        self.assertEqual(
            "execution_error",
            report["scenario_results"]["connection.get_auto_commit"]["compatibility_status"],
        )
        self.assertEqual(
            "SQL file failed",
            report["scenario_results"]["connection.get_auto_commit"]["diagnostic"]["message"],
        )

    def test_end_to_end_fixture_report_human_views_and_matrix_cli(self):
        config = {
            "db": {"type": "postgresql", "url": "jdbc:postgresql://example/test", "username": "develop"},
            "execution": {"mode": "local"},
            "test_filter": {},
            "report": {"format": ["json", "html", "markdown"]},
        }
        execution = {
            "start_time": "2026-06-24T00:00:00",
            "end_time": "2026-06-24T00:00:01",
            "elapsed_seconds": 1,
            "exit_code": 0,
            "stdout": "",
            "stderr": "",
        }
        cases_by_class = {}
        for class_name, method_name in discover_source_scenarios(PROJECT_ROOT):
            cases_by_class.setdefault(class_name, []).append(
                {
                    "classname": class_name,
                    "name": method_name,
                    "status": "passed",
                    "time_seconds": 0.01,
                }
            )
        suites = [
            {
                "name": class_name,
                "time_seconds": 0.01 * len(testcases),
                "total": len(testcases),
                "failures": 0,
                "errors": 0,
                "skipped": 0,
                "testcases": testcases,
            }
            for class_name, testcases in cases_by_class.items()
        ]
        env = {
            "jdbc.test.databaseProductName": "PostgreSQL",
            "jdbc.test.databaseProductVersion": "16",
            "jdbc.test.driverName": "PostgreSQL JDBC Driver",
            "jdbc.test.driverVersion": "42.7.2",
        }

        report = runner.generate_json_report(config, execution, suites, env)
        report["v1_compatibility_report"] = build_v1_report(config, execution, suites, env, PROJECT_ROOT)
        html = runner.generate_html_report(report)
        markdown = runner.generate_markdown_report(report)
        matrix = generate_matrix([report["v1_compatibility_report"]])

        self.assertIn("Target Outcome", html)
        self.assertIn("target_compatible", markdown)
        self.assertIn("connection.get_auto_commit", [row["scenario_id"] for row in matrix["rows"]])

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_path = tmp_path / "compatibility-report-v1.json"
            matrix_path = tmp_path / "matrix.json"
            vendor_path = tmp_path / "vendor.json"
            report_path.write_text(json.dumps(report["v1_compatibility_report"]), encoding="utf-8")
            import compatibility_matrix

            original_argv = sys.argv
            try:
                sys.argv = [
                    "compatibility_matrix.py",
                    str(report_path),
                    "--matrix-out",
                    str(matrix_path),
                    "--vendor-out",
                    str(vendor_path),
                ]
                self.assertEqual(0, compatibility_matrix.main())
            finally:
                sys.argv = original_argv
            self.assertTrue(matrix_path.exists())
            self.assertTrue(vendor_path.exists())


if __name__ == "__main__":
    unittest.main()
