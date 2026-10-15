"""Contracts for syntax, occurrence baselines and fail-closed execution; no application runtime."""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

import check_java_rules as checker


class JavaRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiled = tempfile.TemporaryDirectory(prefix="jdbc-s1-contract-classes-")
        cls.addClassCleanup(cls.compiled.cleanup)
        result = subprocess.run(
            ["javac", "--release", "21", "-proc:none", "-encoding", "UTF-8", "-d", cls.compiled.name,
             str(checker.REPOSITORY / checker.CHECKER)],
            capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="jdbc-s1-contract-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def raw(self, source):
        path = self.root / "Sample.java"
        path.write_text(source, encoding="utf-8")
        return self.raw_paths(path)

    def raw_paths(self, *paths):
        result = subprocess.run(
            ["java", "-cp", self.compiled.name, "CheckJavaRules", *map(str, paths)],
            capture_output=True, text=True, encoding="utf-8", timeout=30)
        return result, [json.loads(line) for line in result.stdout.splitlines()]

    def project(self, source="class Sample {}"):
        for relative in checker.ROOTS:
            directory = self.root / relative
            directory.mkdir(parents=True)
            (directory / "Sample.java").write_text(source, encoding="utf-8")
        path = self.root / checker.CHECKER
        path.parent.mkdir()
        shutil.copyfile(checker.REPOSITORY / checker.CHECKER, path)
        self.baseline([])

    def baseline(self, rows):
        path = self.root / "code-style/java-rules-baseline.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps({"version": 1, "findings": rows}), encoding="utf-8")
        return path

    def gate(self, *args):
        output, error = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(error):
            status = checker.main(["--root", str(self.root), *args])
        return status, output.getvalue(), error.getvalue()

    def test_records_text_blocks_comments_and_strings_are_not_pseudocode_findings(self):
        result, findings = self.raw('''
            // import java.util.*; @SuppressWarnings("all") catch (Exception e) {}
            record Sample(String name) {
              static final String TEXT = """
                  import java.util.*;
                  @SuppressWarnings({"all"}) catch (Exception e) {}
                  """;
              String value() { return "import java.io.*;"; }
            }
        ''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], findings)

    def test_records_and_text_blocks_do_not_hide_real_violations(self):
        result, findings = self.raw('''
            import java.util.*;
            record Sample(String name) {
              static final String TEXT = """
                  harmless text
                  """;
              void run() { try {} catch (Exception ignored) { /* still empty */ } }
            }
        ''')
        self.assertEqual(1, result.returncode)
        self.assertEqual(["wildcard-import", "empty-catch"], [f["rule"] for f in findings])
        self.assertEqual("Sample/run()", findings[1]["owner"])

    def test_static_wildcards_are_detected_but_explicit_imports_are_accepted(self):
        result, findings = self.raw('''
            import java.util.List;
            import static java.util.Collections.emptyList;
            import static java.util.Collections.*;
            class Sample {}
        ''')
        self.assertEqual(1, result.returncode)
        self.assertEqual(1, len(findings))
        self.assertEqual("import static java.util.Collections.*;", findings[0]["detail"])

    def test_single_array_named_and_qualified_suppress_all_forms_fail(self):
        for annotation in ['@SuppressWarnings("all")', '@SuppressWarnings({"all"})',
                           '@SuppressWarnings(value = {"unchecked", "all"})',
                           '@java.lang.SuppressWarnings(value = ("all"))']:
            with self.subTest(annotation=annotation):
                result, findings = self.raw(annotation + " record Sample(int value) {}")
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertEqual(["suppress-all"], [f["rule"] for f in findings])

    def test_narrow_annotations_are_not_assumed_redundant(self):
        for annotation in ['@SuppressWarnings("unchecked")',
                           '@SuppressWarnings({"unchecked", "deprecation"})',
                           '@SuppressWarnings(value = "rawtypes")']:
            with self.subTest(annotation=annotation):
                result, findings = self.raw(annotation + " class Sample {}")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([], findings)

    def test_constant_expressions_and_unresolved_types_are_not_semantic_analysis(self):
        result, findings = self.raw('''
            @SuppressWarnings(Sample.ALL)
            class Sample { static final String ALL = "all"; UnknownType value; }
        ''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], findings)

    def test_javac_parser_folds_adjacent_string_literals_before_scanning(self):
        result, findings = self.raw('@SuppressWarnings("a" + "ll") class Sample {}')
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(["suppress-all"], [f["rule"] for f in findings])

    def test_same_named_custom_annotation_is_not_resolved_semantically(self):
        result, findings = self.raw('''
            @interface SuppressWarnings { String[] value(); }
            @SuppressWarnings("all") class Sample {}
        ''')
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(["suppress-all"], [f["rule"] for f in findings])

    def test_real_catch_statements_pass_only_the_syntax_rule(self):
        result, findings = self.raw('''
            class Sample {
              void run() {
                try {} catch (Exception e) { throw new RuntimeException(e); }
                try {} catch (Exception e) { return; }
              }
            }
        ''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], findings)

    def test_parse_failure_is_error_and_does_not_publish_partial_findings(self):
        result, findings = self.raw("import java.util.*; class Sample { void run( }")
        self.assertEqual(2, result.returncode)
        self.assertTrue(result.stderr)
        self.assertEqual([], findings)

    def test_raw_invalid_missing_duplicate_or_empty_paths_are_errors(self):
        valid = self.root / "Valid.java"
        valid.write_text("class Valid {}", encoding="utf-8")
        wrong_extension = self.root / "NotJava.txt"
        wrong_extension.write_text("class Valid {}", encoding="utf-8")
        for paths in [(), (self.root / "Missing.java",), (self.root,),
                      (wrong_extension,), (valid, valid)]:
            with self.subTest(paths=paths):
                result, findings = self.raw_paths(*paths)
                self.assertEqual(2, result.returncode)
                self.assertEqual([], findings)

    def test_cli_runs_clean_and_new_violation_projects_with_correct_exit_codes(self):
        self.project()
        command = [sys.executable, "-B", str(checker.REPOSITORY / "scripts/check_java_rules.py"),
                   "--root", str(self.root)]
        clean = subprocess.run(command, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, clean.returncode, clean.stderr)
        path = self.root / checker.ROOTS[0] / "Sample.java"
        path.write_text("import java.util.*; class Sample {}", encoding="utf-8")
        bad = subprocess.run(command, capture_output=True, text=True, timeout=120)
        self.assertEqual(1, bad.returncode, bad.stderr)
        self.assertIn("NEW 1x", bad.stderr)
        (self.root / "code-style/java-rules-baseline.json").unlink()
        error = subprocess.run(command, capture_output=True, text=True, timeout=120)
        self.assertEqual(2, error.returncode)

    def test_baseline_counts_additions_moves_and_fixes_are_not_file_exemptions(self):
        self.project()
        path = self.root / checker.ROOTS[0] / "Sample.java"
        one = "class Sample { void run() { try {} catch (Exception e) {} } }"
        path.write_text(one, encoding="utf-8")
        row = [path.relative_to(self.root).as_posix(), "empty-catch", "Sample/run()",
               "Exception e", 1]
        self.baseline([row])
        self.assertEqual(0, self.gate()[0])
        path.write_text("\n\n" + one, encoding="utf-8")
        self.assertEqual(0, self.gate()[0], "line changes are not new findings")
        path.write_text(one.replace("try {}", "try {} catch (Exception e) {} try {}"),
                        encoding="utf-8")
        status, _, error = self.gate()
        self.assertEqual(1, status)
        self.assertIn("NEW 1x", error)
        path.write_text(one.replace("run()", "other()"), encoding="utf-8")
        status, _, error = self.gate()
        self.assertEqual(1, status)
        self.assertIn("NEW 1x", error)
        self.assertIn("STALE BASELINE", error)
        path.write_text("class Sample {}", encoding="utf-8")
        self.assertIn("STALE BASELINE", self.gate()[2])
        self.baseline([])
        self.assertEqual(0, self.gate()[0])

    def test_inventory_does_not_create_or_update_a_baseline(self):
        self.project("import java.util.*; class Sample {}")
        path = self.root / "code-style/java-rules-baseline.json"
        before = path.read_bytes()
        status, output, _ = self.gate("--inventory")
        self.assertEqual(1, status)
        self.assertEqual(8, len(output.splitlines()))
        self.assertEqual(before, path.read_bytes())

    def test_missing_empty_symlinked_scan_roots_and_checker_are_errors(self):
        self.project()
        path = self.root / checker.ROOTS[0] / "Sample.java"
        path.unlink()
        self.assertEqual(2, self.gate()[0])
        path.parent.rmdir()
        self.assertEqual(2, self.gate()[0])
        path.parent.mkdir()
        path.write_text("class Sample {}", encoding="utf-8")
        (self.root / checker.CHECKER).unlink()
        self.assertEqual(2, self.gate()[0])

    def test_symlinked_sources_are_errors(self):
        self.project()
        target = self.root / checker.ROOTS[0] / "Sample.java"
        try:
            (target.parent / "Link.java").symlink_to(target)
        except OSError as error:
            self.skipTest(f"OS does not permit test symlinks: {error}")
        self.assertEqual(2, self.gate()[0])

    def test_unreadable_subdirectories_cannot_be_silently_skipped(self):
        self.project()

        def unreadable(directory, onerror=None):
            self.assertIsNotNone(onerror, "os.walk must propagate traversal errors")
            onerror(PermissionError("controlled unreadable directory"))

        with mock.patch.object(checker.os, "walk", side_effect=unreadable):
            self.assertEqual(2, self.gate()[0])

    def test_malformed_duplicate_and_out_of_scope_baselines_are_errors(self):
        self.project()
        row = [checker.ROOTS[0] + "/Sample.java", "empty-catch", "Sample/run()", "Exception e", 1]
        path = self.baseline([])
        for contents in ["null", "[]", "{broken", '{"version":1,"version":1,"findings":[]}',
                         json.dumps({"version": True, "findings": []}),
                         json.dumps({"version": 1, "findings": [row, row]}),
                         json.dumps({"version": 1, "findings": [["../escape.java", *row[1:]]]}),
                         json.dumps({"version": 1, "findings": [[*row[:4], True]]}),
                         json.dumps({"version": 1, "findings": [[*row[:1], "unknown", *row[2:]]]})]:
            with self.subTest(contents=contents):
                path.write_text(contents, encoding="utf-8")
                self.assertEqual(2, self.gate()[0])

    def test_missing_tools_compile_failure_and_timeout_are_errors_with_cleanup(self):
        self.project()
        for missing in ("java", "javac"):
            with self.subTest(missing=missing):
                with mock.patch.object(checker.shutil, "which",
                                       side_effect=lambda tool: None if tool == missing else tool):
                    self.assertEqual(2, self.gate()[0])
        before = set(Path(tempfile.gettempdir()).glob("jdbc-s1-java-rules-*"))
        failure = subprocess.CompletedProcess([], 1, "", "controlled compilation failure")
        with mock.patch.object(checker.subprocess, "run", return_value=failure):
            self.assertEqual(2, self.gate()[0])
        with mock.patch.object(checker.subprocess, "run",
                               side_effect=subprocess.TimeoutExpired("javac", 120)):
            self.assertEqual(2, self.gate()[0])
        with mock.patch.object(checker.subprocess, "run", side_effect=OSError("cannot start tool")):
            self.assertEqual(2, self.gate()[0])
        self.assertEqual(before, set(Path(tempfile.gettempdir()).glob("jdbc-s1-java-rules-*")))

    def test_scanner_error_invalid_output_and_inconsistent_status_are_errors(self):
        self.project()
        compiled = subprocess.CompletedProcess([], 0, "", "")
        for result in [subprocess.CompletedProcess([], 2, "", "parse failed"),
                       subprocess.CompletedProcess([], 1, "", ""),
                       subprocess.CompletedProcess([], 0, "not json", ""),
                       subprocess.CompletedProcess([], 0, "null\n", ""),
                       subprocess.CompletedProcess([], 0, "", "unexpected diagnostic")]:
            with self.subTest(result=result):
                with mock.patch.object(checker.subprocess, "run", side_effect=[compiled, result]):
                    self.assertEqual(2, self.gate()[0])

    def test_real_parse_failure_propagates_through_gate(self):
        self.project("class Sample { broken(")
        self.assertEqual(2, self.gate()[0])

    def test_java_editorconfig_matches_effective_project_xml_options(self):
        scheme = ET.parse(checker.REPOSITORY / "code-style/java.xml").getroot()
        java = scheme.find("codeStyleSettings[@language='JAVA']")
        options = {node.attrib["name"]: node.attrib["value"] for node in java.findall("option")}
        options.update({node.attrib["name"]: node.attrib["value"]
                        for node in java.find("indentOptions").findall("option")})
        config = (checker.REPOSITORY / ".editorconfig").read_text(encoding="utf-8")
        values = dict(line.strip().split(" = ", 1) for line in config.splitlines() if " = " in line)
        self.assertEqual("space", values["indent_style"])
        self.assertEqual("false", options["USE_TAB_CHARACTER"])
        for key, option in {"indent_size": "INDENT_SIZE", "tab_width": "TAB_SIZE",
                            "max_line_length": "RIGHT_MARGIN",
                            "ij_continuation_indent_size": "CONTINUATION_INDENT_SIZE"}.items():
            self.assertEqual(options[option], values[key])

    def test_formatter_wrapper_passes_dry_run_and_exact_checker_path_to_fixture(self):
        if sys.platform == "win32" or shutil.which("bash") is None:
            self.skipTest("POSIX wrapper fixture requires bash and executable script support")
        formatter = self.root / "idea/bin/format.sh"
        formatter.parent.mkdir(parents=True)
        formatter.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n', encoding="utf-8")
        formatter.chmod(0o700)
        environment = dict(os.environ, IDEA_HOME=str(formatter.parent.parent))
        path = checker.REPOSITORY / checker.CHECKER
        before = path.read_bytes()
        command = ["bash", str(checker.REPOSITORY / "scripts/format_java.sh"), "check"]
        result = subprocess.run([*command, checker.CHECKER], env=environment,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-d", result.stdout.splitlines())
        self.assertEqual(checker.CHECKER, result.stdout.splitlines()[-1])
        self.assertIn(str(checker.REPOSITORY / "code-style/java.xml"), result.stdout.splitlines())
        self.assertEqual(before, path.read_bytes())
        for invalid in ["scripts/Other.java", "scripts/../scripts/CheckJavaRules.java",
                        checker.ROOTS[0] + "/Missing.java"]:
            result = subprocess.run([*command, invalid], env=environment,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(2, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
