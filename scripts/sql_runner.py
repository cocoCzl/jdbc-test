"""SQL feature CLI delegates validation and execution to the same Core/Worker as Web."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile

from adapter_archive import pack_adapter
from adapter_runtime import build_runtime_config, load_adapter, resolve_driver


def _validate(package: Path, worker: Path) -> dict:
    if not worker.is_file():
        raise ValueError("Build the Worker first: ./mvnw -pl jdbc-test-worker -am package -DskipTests")
    if package.stat().st_size > 8 * 1048576:
        raise ValueError("SQL package exceeds 8 MiB")
    result = subprocess.run(["java", "-jar", str(worker), "--validate-sql-package"],
                            input=package.read_bytes(), capture_output=True, timeout=30)
    events = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    valid = next((event for event in events if event.get("type") == "SQL_PACKAGE_VALIDATED"), None)
    if result.returncode != 0 or valid is None:
        raise ValueError(next((event.get("message") for event in events), "SQL package validation failed"))
    return valid


def _adapter_snapshot(root: Path, adapter, folder: Path) -> dict:
    archive = folder / "adapter.zip"
    pack_adapter(root, str(adapter.root), archive)
    with zipfile.ZipFile(archive) as bundle:
        manifest = bundle.read("adapter.json").decode("utf-8")
        resources = {name: base64.b64encode(bundle.read(name)).decode("ascii")
                     for name in bundle.namelist() if name != "adapter.json"}
    digest = hashlib.sha256(manifest.encode("utf-8"))
    for name, value in sorted(resources.items()):
        digest.update(f"\n{name}\n{value}\n".encode("utf-8"))
    return {"manifest": manifest, "resources": resources, "fingerprint": digest.hexdigest()}


def command_sql(args, root: Path) -> int:
    try:
        worker = Path(args.worker).resolve()
        valid = _validate(Path(args.package), worker)
        if args.command == "sql-validate":
            print(json.dumps(valid, ensure_ascii=False, indent=2))
            return 0
        if not 1 <= args.budget_seconds <= 7200:
            raise ValueError("budget-seconds must be 1..7200")
        config = json.loads(Path(args.config).read_text(encoding="utf-8"))
        namespace = config.get("namespace") or {}
        if namespace.get("mode") != "existing" or not namespace.get("destructive_consent"):
            raise ValueError("SQL v1 requires an existing authorized namespace and destructive_consent=true")
        if (config.get("db") or {}).get("connection_mode", "driver_manager") != "driver_manager":
            raise ValueError("SQL v1 supports driver_manager mode only; set it explicitly")
        adapter = load_adapter(root, config["adapter"])
        driver = resolve_driver(root, config, adapter)
        runtime = build_runtime_config(root, config, adapter, driver, regression_evidence=False)
        output = Path(args.output).resolve()
        output.mkdir(parents=True, exist_ok=False)
        with tempfile.TemporaryDirectory(prefix="jdbc-sql-run-") as temporary:
            workspace = Path(temporary)
            snapshot = _adapter_snapshot(root, adapter, workspace)
            manifest = json.loads(snapshot["manifest"])
            execution = {"id": manifest["id"], "assetId": manifest["assets"].get("directory", manifest["id"]),
                         "dialect": manifest["dialect"], "identifierQuote": manifest["identifier_quote"],
                         "capabilities": manifest["capabilities"],
                         "metadataConstraints": {key: runtime["db"][key] for key in (
                             "expected_database_product_regex", "expected_driver_name_regex",
                             "database_version_min", "database_version_max", "driver_version_min", "driver_version_max")}}
            request = {"protocolVersion": 2, "runId": str(uuid.uuid4()),
                       "driver": {"driverClass": runtime["db"]["driver_class"], "artifacts": driver["classpath"],
                                  "dataSources": runtime["db"]["data_sources"]},
                       "connection": {"adapterId": adapter.adapter_id, "jdbcUrl": runtime["db"]["url"],
                                      "username": runtime["db"]["username"], "password": runtime["db"]["password"],
                                      "properties": runtime["db"]["properties"]},
                       "selection": {"profile": "CUSTOM", "modules": ["sql_feature"],
                                     "includeScenarios": args.cases, "excludeScenarios": []},
                       "workspace": str(workspace / "work"), "timeoutMs": args.budget_seconds * 1000,
                       "scope": {"database": namespace.get("database"), "schema": namespace["name"]},
                       "objectPrefix": runtime["namespace"]["object_prefix"],
                       "capabilities": runtime["adapter"]["capabilities"], "destructiveConsent": True,
                       "adapterExecution": execution, "adapterPackage": snapshot,
                       "adapterSnapshot": {"id": adapter.adapter_id, "version": manifest["version"],
                                           "origin": "BUILTIN" if adapter.root.parent == root / "adapters" else "CLI_LOCAL",
                                           "status": manifest["trust"].upper() if adapter.root.parent == root / "adapters" else "LOCAL", "fingerprint": snapshot["fingerprint"]},
                       "sqlPackage": valid["sqlPackage"]}
            child_env = os.environ.copy()
            child_env.pop("JDBC_TEST_REGRESSION_KEY", None)
            key_name = (config.get("regression") or {}).get("environment_key_env")
            if key_name:
                child_env.pop(key_name, None)
            try:
                result = subprocess.run(["java", "-jar", str(worker)], input=json.dumps(request).encode(),
                                        capture_output=True, timeout=args.budget_seconds + 10, env=child_env)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                result = None
            report = workspace / "work/report/report.json"
            ledger = workspace / "work/object-ledger.yaml"
            if ledger.exists():
                shutil.copy2(ledger, output / "object-ledger.yaml")
            if not report.exists():
                print("SQL execution incomplete; inspect ownership ledger. No passing conclusion.", file=sys.stderr)
                return 2
            payload = json.loads(report.read_text())
            payload["driverArtifactFingerprint"] = driver["sha256"]
            (output / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
            summary = payload["summary"]
            print(f"SQL_FEATURE: {summary}; cleanup={payload['cleanupEvidence'].get('status')}; report={output / 'report.json'}")
            if result is None or payload["notRun"] or payload["incomplete"]:
                return 2
            return 0 if result.returncode == 0 and payload["terminationReason"] == "COMPLETED" else 1
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # Do not print connection/configuration values or raw driver output.
        print(f"SQL command failed: {error}" if args.command == "sql-validate" else "SQL command failed: check package, driver, namespace and configuration", file=sys.stderr)
        return 2
