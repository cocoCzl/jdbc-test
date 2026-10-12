#!/usr/bin/env python3
"""Acceptance check for all three deployment modes using disposable PostgreSQL/data."""
from __future__ import annotations

import argparse
import base64
import json
import os
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

from build_distribution import PROJECT_ROOT, project_version


PASSWORD = "acceptance$#password"


def run(*command, **kwargs):
    return subprocess.run(command, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def unused_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def request(base, path, data=None, content_type="application/json"):
    body = json.dumps(data).encode() if data is not None and not isinstance(data, bytes) else data
    operation = urllib.request.Request(base + path, data=body, headers={"Content-Type": content_type})
    with urllib.request.urlopen(operation, timeout=40) as response:
        return json.load(response)


def wait_for_health(base, process=None):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError("Native Server exited before becoming healthy")
        try:
            if request(base, "/actuator/health")["status"] == "UP":
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Health did not become UP: {base}")


def verify_application(base, driver_jar, jdbc_url):
    run("sh", str(PROJECT_ROOT / "scripts/docker-smoke.sh"), env={**os.environ, "JDBC_TEST_BASE_URL": base})
    boundary = uuid.uuid4().hex
    metadata = json.dumps({"name": "acceptance", "vendor": "PostgreSQL", "version": "fixture", "driverClass": "org.postgresql.Driver", "urlPrefix": "jdbc:postgresql:"}).encode()
    payload = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'.encode()
        + metadata
        + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="postgresql.jar"\r\nContent-Type: application/java-archive\r\n\r\n'.encode()
        + driver_jar
        + f"\r\n--{boundary}--\r\n".encode()
    )
    driver = request(base, "/api/drivers", payload, f"multipart/form-data; boundary={boundary}")
    validation = request(base, f"/api/drivers/{driver['id']}/validate", {})
    if validation["status"] != "VALIDATED":
        raise RuntimeError(f"Worker driver validation failed: {validation}")
    connection = request(base, "/api/connections", {
        "name": "acceptance", "databaseType": "postgresql", "adapterId": "postgresql",
        "jdbcUrl": jdbc_url, "username": "fixture_user", "password": PASSWORD,
        "savePassword": True, "defaultDriverId": driver["id"],
    })
    verify_saved_connection(base, connection["id"])
    return connection["id"]


def verify_saved_connection(base, connection_id):
    if not request(base, f"/api/connections/{connection_id}")["hasSavedPassword"]:
        raise RuntimeError("Saved connection did not persist")
    if not request(base, f"/api/connections/{connection_id}/test", {})["success"]:
        raise RuntimeError("Worker saved-password probe failed")


def check(image, distribution):
    bundle = distribution / f"jdbc-test-server-{project_version().replace('-SNAPSHOT', '')}"
    with zipfile.ZipFile(bundle / "server/jdbc-test-server.jar") as server:
        pg_driver = next(name for name in server.namelist() if name.startswith("BOOT-INF/lib/postgresql-") and name.endswith(".jar"))
        driver_jar = server.read(pg_driver)
    identity = f"jdbc-deploy-check-{uuid.uuid4().hex[:10]}"
    network, pg = identity + "-net", identity + "-pg"
    projects = []
    process = None
    native_log = None
    with tempfile.TemporaryDirectory(prefix=identity) as temporary:
        workspace = Path(temporary)
        key = base64.b64encode(os.urandom(32)).decode()
        environment = {key: value for key, value in os.environ.items() if not key.startswith(("POSTGRES_", "SPRING_", "JDBC_TEST_", "COMPOSE_"))}
        try:
            run("docker", "network", "create", network)
            run("docker", "run", "-d", "--name", pg, "--network", network, "--network-alias", "existing-pg", "-e", f"POSTGRES_PASSWORD={PASSWORD}", "-p", "127.0.0.1::5432", "postgres:16")
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                ready = subprocess.run(["docker", "exec", pg, "pg_isready", "-U", "postgres"], capture_output=True)
                if ready.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Acceptance PostgreSQL did not become ready")
            run("docker", "exec", "-i", pg, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", input=(
                f"CREATE ROLE fixture_user LOGIN PASSWORD '{PASSWORD}';\n"
                "CREATE DATABASE fixture_external OWNER fixture_user;\n"
                "CREATE DATABASE fixture_native OWNER fixture_user;\n"
            ))
            bindings = json.loads(run("docker", "inspect", "--format", "{{json .NetworkSettings.Ports}}", pg))
            pg_port = bindings["5432/tcp"][0]["HostPort"]

            for mode, entry in (("bundled", "docker-compose.yml"), ("external", "docker-compose.external.yml")):
                port = unused_port()
                settings = f"JDBC_TEST_MASTER_KEY={key}\nJDBC_TEST_PORT={port}\nJDBC_TEST_BIND_ADDRESS=127.0.0.1\n"
                jdbc_url = "jdbc:postgresql://postgres:5432/fixture_bundled" if mode == "bundled" else "jdbc:postgresql://existing-pg:5432/fixture_external"
                if mode == "bundled":
                    settings += f"POSTGRES_DB=fixture_bundled\nPOSTGRES_USER=fixture_user\nPOSTGRES_PASSWORD='{PASSWORD}'\n"
                else:
                    settings += f"SPRING_DATASOURCE_URL={jdbc_url}\nSPRING_DATASOURCE_USERNAME=fixture_user\nSPRING_DATASOURCE_PASSWORD='{PASSWORD}'\n"
                env_file = workspace / f"{mode}.env"
                env_file.write_text(settings, encoding="utf-8")
                env_file.chmod(0o600)
                override = workspace / f"{mode}.yml"
                override.write_text(
                    f"services:\n  jdbc-test:\n    image: {image}\n"
                    + (f"    networks: [acceptance]\nnetworks:\n  acceptance:\n    external: true\n    name: {network}\n" if mode == "external" else ""),
                    encoding="utf-8",
                )
                compose = ["docker", "compose", "--env-file", str(env_file), "-p", f"{identity}-{mode}", "-f", str(PROJECT_ROOT / entry), "-f", str(override)]
                projects.append(compose)
                run(*compose, "up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120", env=environment)
                base = f"http://127.0.0.1:{port}"
                connection_id = verify_application(base, driver_jar, jdbc_url)
                run(*compose, "restart", "jdbc-test", env=environment)
                wait_for_health(base)
                verify_saved_connection(base, connection_id)
                run(*compose, "down", env=environment)
                run(*compose, "up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120", env=environment)
                verify_saved_connection(base, connection_id)
                print(f"PASS: Docker + {mode} PG; migrations, Web/API, Worker, encrypted credentials, restart and down/up persistence", flush=True)

            # Extract a clean ZIP, including paths with spaces, like an end user's installation.
            native = workspace / "native deployment"
            with zipfile.ZipFile(bundle.with_name(bundle.name + ".zip")) as archive:
                archive.extractall(native)
            native = native / bundle.name
            port = unused_port()
            jdbc_url = f"jdbc:postgresql://127.0.0.1:{pg_port}/fixture_native"
            configuration = native / "config/application.properties"
            configuration.write_text(f"spring.datasource.url={jdbc_url}\nspring.datasource.username=fixture_user\nspring.datasource.password={PASSWORD}\njdbc-test.security.master-key={key}\nserver.port={port}\n", encoding="utf-8")
            configuration.chmod(0o600)
            native_log = (workspace / "native.log").open("w+")
            def start_native():
                return subprocess.Popen(["sh", str(native / "start.sh")], cwd=workspace, env=environment, stdout=native_log, stderr=subprocess.STDOUT)
            process = start_native()
            base = f"http://127.0.0.1:{port}"
            wait_for_health(base, process)
            connection_id = verify_application(base, driver_jar, jdbc_url)
            process.send_signal(signal.SIGTERM)
            process.wait(timeout=60)
            process = start_native()
            wait_for_health(base, process)
            verify_saved_connection(base, connection_id)
            print("PASS: native + existing PG; clean bundle, migrations, Web/API, Worker, encrypted credentials and restart persistence", flush=True)
        except Exception:
            for compose in projects:
                logs = subprocess.run([*compose, "logs", "--tail=30", "jdbc-test"], env=environment, capture_output=True, text=True).stdout
                print(logs.replace(PASSWORD, "[REDACTED]"))
            if native_log is not None:
                native_log.flush()
                native_log.seek(0)
                print(native_log.read()[-8000:].replace(PASSWORD, "[REDACTED]"))
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=60)
            if native_log is not None:
                native_log.close()
            # Only this check's UUID-named resources are removed.
            for compose in projects:
                subprocess.run([*compose, "down", "-v", "--remove-orphans"], env=environment, capture_output=True)
            subprocess.run(["docker", "rm", "-f", "-v", pg], capture_output=True)
            subprocess.run(["docker", "network", "rm", network], capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="jdbc-test:ci")
    parser.add_argument("--distribution", type=Path, default=PROJECT_ROOT / "dist")
    arguments = parser.parse_args()
    check(arguments.image, arguments.distribution.resolve())


if __name__ == "__main__":
    main()
