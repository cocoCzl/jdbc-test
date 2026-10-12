from __future__ import annotations

import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import build_distribution


ROOT = Path(__file__).resolve().parent.parent


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="jdbc deployment ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>1.2.1-SNAPSHOT</version></project>',
            encoding="utf-8",
        )
        for module, suffix in (("server", "exec"), ("worker", "all")):
            target = self.source / f"jdbc-test-{module}" / "target"
            target.mkdir(parents=True)
            (target / f"jdbc-test-{module}-1.2.1-SNAPSHOT-{suffix}.jar").write_bytes(b"fixture-jar")
        web = self.source / "jdbc-test-web" / "dist"
        (web / "assets").mkdir(parents=True)
        (web / "index.html").write_text('<div id="root"></div>', encoding="utf-8")
        (web / "assets/app.js").write_text("fixture", encoding="utf-8")
        shutil.copytree(ROOT / "deploy", self.source / "deploy")
        shutil.copytree(ROOT / "docs", self.source / "docs")
        shutil.copy2(ROOT / "LICENSE", self.source / "LICENSE")
        # Runtime secrets/data must never enter the distribution.
        (self.source / ".env").write_text("fixture-private-secret", encoding="utf-8")
        (self.source / "data").mkdir()
        (self.source / "data/private.txt").write_text("fixture-private-data", encoding="utf-8")

    def bundle(self):
        with patch.object(build_distribution, "PROJECT_ROOT", self.source):
            return build_distribution.build(self.root / "output", skip_build=True)

    def test_archives_are_complete_and_exclude_runtime_secrets(self):
        directory, archive, windows_archive = self.bundle()
        required = {"server/jdbc-test-server.jar", "worker/jdbc-test-worker.jar", "web/index.html", "web/assets/app.js", "start.sh", "start.ps1", "config/application.properties.example", "docs/deployment.md", "docs/deployment.zh-CN.md"}
        with zipfile.ZipFile(windows_archive) as bundle:
            contents = {name.split("/", 1)[1] for name in bundle.namelist()}
            self.assertTrue(required <= contents)
            self.assertNotIn(".env", contents)
            self.assertNotIn("config/application.properties", contents)
            self.assertFalse(any(name.startswith("data/") for name in contents))
        with tarfile.open(archive) as bundle:
            self.assertTrue(bundle.getmember(f"{directory.name}/start.sh").mode & 0o111)
        for path in (archive, windows_archive):
            self.assertEqual(
                path.with_name(path.name + ".sha256").read_text().split()[0],
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )

    def test_rebuilding_cannot_overwrite_an_existing_installation(self):
        directory, _, _ = self.bundle()
        configuration = directory / "config/application.properties"
        configuration.write_text("fixture-saved-configuration", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            self.bundle()
        self.assertEqual(configuration.read_text(), "fixture-saved-configuration")

    def test_launcher_uses_bundle_directory_and_keeps_passwords_out_of_arguments(self):
        directory, _, _ = self.bundle()
        configuration = directory / "config/application.properties"
        configuration.write_text("spring.datasource.password=fixture$#secret", encoding="utf-8")
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir()
        recorder = self.root / "record.py"
        recorder.write_text(
            "import json, os, sys\nfrom pathlib import Path\n"
            "Path(os.environ['TEST_LAUNCH_RECORD']).write_text(json.dumps({'cwd': os.getcwd(), 'args': sys.argv[1:]}))\n",
            encoding="utf-8",
        )
        if os.name == "nt":
            powershell = shutil.which("pwsh") or shutil.which("powershell")
            if not powershell:
                self.skipTest("PowerShell is unavailable")
            java = fake_bin / "java.cmd"
            java.write_text("@echo off\n" + subprocess.list2cmdline([sys.executable, str(recorder)]) + " %*\n", encoding="utf-8")
            launcher = [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(directory / "start.ps1")]
        else:
            java = fake_bin / "java"
            java.write_text(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(recorder))} \"$@\"\n", encoding="utf-8")
            java.chmod(0o755)
            launcher = ["sh", str(directory / "start.sh")]
        record = self.root / "record.json"
        environment = {**os.environ, "PATH": f"{fake_bin}{os.pathsep}{os.environ['PATH']}", "TEST_LAUNCH_RECORD": str(record)}
        result = subprocess.run([*launcher, "--server.port=19080"], cwd=self.root, env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        invocation = json.loads(record.read_text())
        self.assertEqual(Path(invocation["cwd"]).resolve(), directory.resolve())
        self.assertIn("--spring.config.additional-location=file:./config/application.properties", invocation["args"])
        self.assertIn("--server.port=19080", invocation["args"])
        self.assertNotIn("fixture$#secret", json.dumps(invocation))
        configuration.unlink()
        record.unlink()
        result = subprocess.run(launcher, cwd=self.root, env=environment, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Copy config/application.properties.example", result.stderr)
        self.assertFalse(record.exists())


class ComposeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        docker = shutil.which("docker")
        if not docker:
            raise unittest.SkipTest("Docker CLI is unavailable")
        check = subprocess.run([docker, "compose", "version"], capture_output=True)
        if check.returncode:
            raise unittest.SkipTest("Compose is unavailable")
        cls.docker = docker

    def render(self, entry, settings, *overrides):
        with tempfile.TemporaryDirectory() as temporary:
            env_file = Path(temporary) / ".env"
            env_file.write_text(settings, encoding="utf-8")
            # Explicit file avoids accidentally reading local deployment credentials.
            environment = {key: value for key, value in os.environ.items() if not key.startswith(("POSTGRES_", "SPRING_", "JDBC_TEST_", "COMPOSE_"))}
            command = [self.docker, "compose", "--env-file", str(env_file), "-f", str(ROOT / entry)]
            for override in overrides:
                command.extend(["-f", str(ROOT / override)])
            result = subprocess.run([*command, "config", "--format", "json"], cwd=ROOT, env=environment, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout)

    def test_bundled_pg_generates_datasource_and_waits_for_database(self):
        configuration = self.render("docker-compose.yml", "POSTGRES_DB=fixture_db\nPOSTGRES_USER=fixture_user\nPOSTGRES_PASSWORD='fixture$#password'\nJDBC_TEST_MASTER_KEY=fixture-only\n")
        self.assertEqual(set(configuration["services"]), {"jdbc-test", "postgres"})
        application = configuration["services"]["jdbc-test"]
        self.assertEqual(application["environment"]["SPRING_DATASOURCE_URL"], "jdbc:postgresql://postgres:5432/fixture_db")
        # `compose config` escapes dollars for round-trip serialization.
        self.assertEqual(application["environment"]["SPRING_DATASOURCE_PASSWORD"].replace("$$", "$"), "fixture$#password")
        self.assertEqual(application["depends_on"]["postgres"]["condition"], "service_healthy")
        self.assertEqual(Path(application["build"]["context"]), ROOT)

    def test_external_pg_needs_no_bundled_pg_credentials_or_volume(self):
        configuration = self.render("docker-compose.external.yml", "SPRING_DATASOURCE_URL=jdbc:postgresql://existing.invalid:5432/fixture\nSPRING_DATASOURCE_USERNAME=fixture_user\nSPRING_DATASOURCE_PASSWORD='fixture$#password'\nJDBC_TEST_MASTER_KEY=fixture-only\n", "docker-compose.host-gateway.yml")
        self.assertEqual(set(configuration["services"]), {"jdbc-test"})
        self.assertEqual(set(configuration["volumes"]), {"jdbc_test_data"})
        application = configuration["services"]["jdbc-test"]
        self.assertFalse(application.get("depends_on"))
        self.assertEqual(application["environment"]["SPRING_DATASOURCE_PASSWORD"].replace("$$", "$"), "fixture$#password")
        self.assertEqual(Path(application["build"]["context"]), ROOT)
        self.assertIn("host.docker.internal", str(application["extra_hosts"]))


if __name__ == "__main__":
    unittest.main()
