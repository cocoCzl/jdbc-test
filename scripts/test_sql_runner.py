"""Offline CLI contracts. Execution exits 0/1 are additionally checked on disposable PostgreSQL."""
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import regression
import runner
from adapter_runtime import build_runtime_config, load_adapter


class SqlRunnerTest(unittest.TestCase):
    def test_validation_exit_zero_and_two_use_shared_worker_validator(self):
        output = io.StringIO()
        with patch("sql_runner._validate", return_value={"testDomain": "SQL_FEATURE"}) as validate:
            with contextlib.redirect_stdout(output):
                self.assertEqual(0, runner.main(["sql-validate", "--package", "fixture.json"]))
            self.assertEqual(Path("fixture.json"), validate.call_args.args[0])
        with patch("sql_runner._validate", side_effect=ValueError("cases[].expect: unsupported FLOAT")):
            with contextlib.redirect_stderr(output):
                self.assertEqual(2, runner.main(["sql-validate", "--package", "bad.json"]))
        self.assertIn("cases[].expect", output.getvalue())

    def test_invalid_run_does_not_resolve_a_driver_or_connect(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-sql-cli-contract-") as folder:
            config = Path(folder) / "config.json"
            config.write_text('{"namespace":{"mode":"auto","destructive_consent":true}}')
            with patch("sql_runner._validate", return_value={}):
                with patch("sql_runner.resolve_driver") as driver, contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, runner.main(["sql-run", "--package", "fixture.json",
                        "--config", str(config), "--case", "query", "--output", str(Path(folder) / "out")]))
                    driver.assert_not_called()

    def test_sql_configuration_does_not_collect_jdbc_comparison_evidence_or_key(self):
        adapter = load_adapter(runner.PROJECT_ROOT, "postgresql")
        config = {"db": {"url": "jdbc:fixture:never-contacted", "username": "fixture",
                         "password_env": "JDBC_SQL_FIXTURE_PASSWORD", "connection_mode": "driver_manager"},
                  "namespace": {"mode": "existing", "name": "fixture", "destructive_consent": True}}
        with patch.dict(os.environ, {"JDBC_SQL_FIXTURE_PASSWORD": "synthetic-only"}):
            with patch("adapter_runtime.runtime_inputs", side_effect=AssertionError("JDBC key collector called")):
                runtime = build_runtime_config(runner.PROJECT_ROOT, config, adapter, {}, regression_evidence=False)
                self.assertNotIn("regression_inputs", runtime)

    def test_sql_report_cannot_become_a_jdbc_baseline_even_with_forged_extra_fields(self):
        with self.assertRaisesRegex(ValueError, "SQL_FEATURE"):
            regression.select_baseline({"testDomain": "SQL_FEATURE", "formal_eligible": True})


if __name__ == "__main__":
    unittest.main()
