#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen


def request(base, path, body=None):
    payload = None if body is None else json.dumps(body).encode()
    with urlopen(Request(base + path, data=payload, headers={"Content-Type": "application/json"}), timeout=45) as response:
        return response.read()


def resource(base, path, body=None):
    return json.loads(request(base, path, body))


def snapshot(base, checkpoint=None):
    if checkpoint is None:
        drivers = resource(base, "/api/drivers?size=100")["items"]
        connections = resource(base, "/api/connections?size=100")["items"]
        runs = resource(base, "/api/runs?size=100")["items"]
        assert len(drivers) == 1 and len(connections) == 1 and len(runs) == 2, "Capture immediately after fresh real E2E"
        driver, connection = drivers[0], connections[0]
    else:
        driver = resource(base, f'/api/drivers/{checkpoint["driverId"]}')
        connection = resource(base, f'/api/connections/{checkpoint["connectionId"]}')
        runs = [resource(base, f'/api/runs/{report["id"]}') for report in checkpoint["reports"]]
    assert connection["hasSavedPassword"]
    assert sorted(run["totalCases"] for run in runs) == [17, 23]
    reports = []
    for run in runs:
        detail = resource(base, f'/api/runs/{run["id"]}')
        assert detail["status"] == "SUCCEEDED"
        results = resource(base, f'/api/runs/{run["id"]}/results?size=100')
        raw_report = request(base, f'/api/runs/{run["id"]}/report.json')
        assert detail["passed"] == detail["totalCases"] and detail["failed"] == 0
        assert results["totalElements"] == detail["totalCases"]
        assert all(row["status"] == "PASSED" for row in results["items"])
        reports.append({"id": run["id"], "total": detail["totalCases"], "reportSha256": hashlib.sha256(raw_report).hexdigest()})
    return {"driverId": driver["id"], "fingerprint": driver["packageFingerprint"],
            "artifactHashes": [artifact["sha256"] for artifact in driver["artifacts"]],
            "connectionId": connection["id"], "reports": sorted(reports, key=lambda report: report["id"])}


def main():
    parser = argparse.ArgumentParser(description="Fresh acceptance deployment persistence checkpoint (no secrets stored)")
    parser.add_argument("mode", choices=["capture", "verify"])
    parser.add_argument("checkpoint", type=Path)
    arguments = parser.parse_args()
    base = os.environ.get("JDBC_TEST_BASE_URL", "http://127.0.0.1:8080")
    checkpoint = None if arguments.mode == "capture" else json.loads(arguments.checkpoint.read_text())
    current = snapshot(base, checkpoint)
    if arguments.mode == "capture":
        arguments.checkpoint.write_text(json.dumps(current, indent=2) + "\n")
    else:
        assert current == checkpoint, "Persisted resource or report changed"
        validated = resource(base, f'/api/drivers/{current["driverId"]}/validate', {})
        assert validated["driverClass"] == "org.postgresql.Driver"
        probe = resource(base, f'/api/connections/{current["connectionId"]}/test', {})
        assert probe["success"] and probe["driverVersion"] == "42.7.5"
    print(f'{arguments.mode}: Driver artifacts, saved credential, Statement23/Connection17, results and report hashes PASS')


if __name__ == "__main__":
    main()
