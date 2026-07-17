from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
from datetime import date
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REQUIRED_ADAPTER_FIELDS = (
    "id", "name", "version", "trust", "dialect", "driver", "identity",
    "capabilities", "assets", "namespace", "minimum_privileges", "privilege_checks",
    "supported_versions", "validated_combinations", "known_differences",
)
KNOWN_DIFFERENCE_FIELDS = (
    "scenario_id", "database_version", "driver_version", "reason",
    "issue_url", "review_after", "trust",
)
REQUIRED_ASSET_AREAS = {
    "connection", "statement", "preparedstatement", "callablestatement",
    "resultset", "metadatatest", "savepoint", "rowset", "blobclob",
    "sqlxml", "advancedtype",
}


@dataclass(frozen=True)
class AdapterPackage:
    root: Path
    manifest: dict[str, Any]

    @property
    def adapter_id(self) -> str:
        return str(self.manifest["id"])


def discover_adapters(project_root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    adapters_root = project_root / "adapters"
    if not adapters_root.exists():
        return result
    for manifest in sorted(adapters_root.glob("*/adapter.json")):
        try:
            adapter_id = json.loads(manifest.read_text(encoding="utf-8")).get("id")
        except (OSError, json.JSONDecodeError):
            continue
        if adapter_id:
            result[str(adapter_id)] = manifest.parent
    return result


def load_adapter(project_root: Path, reference: str) -> AdapterPackage:
    path = Path(reference)
    if not path.is_absolute():
        candidate = project_root / path
        if candidate.exists():
            path = candidate
        else:
            discovered = discover_adapters(project_root)
            if reference not in discovered:
                raise ValueError(f"未找到数据库适配包: {reference}")
            path = discovered[reference]
    manifest_path = path if path.name == "adapter.json" else path / "adapter.json"
    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"适配包清单不存在: {manifest_path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"适配包清单不是有效 JSON: {manifest_path}: {exc}") from exc
    package = AdapterPackage(manifest_path.parent.resolve(), raw)
    validate_adapter(package, project_root)
    return package


def validate_adapter(package: AdapterPackage, project_root: Path) -> None:
    manifest = package.manifest
    missing = [field for field in REQUIRED_ADAPTER_FIELDS if field not in manifest]
    if missing:
        raise ValueError(f"适配包不完整，缺少字段: {', '.join(missing)}")
    if manifest["trust"] not in {"official", "local", "candidate"}:
        raise ValueError("adapter.trust 必须是 official/local/candidate")
    if not re.fullmatch(r"[a-z][a-z0-9_-]{1,31}", str(manifest["id"])):
        raise ValueError("适配包 id 格式无效")
    capabilities = manifest.get("capabilities")
    if not isinstance(capabilities, dict) or not all(isinstance(v, bool) for v in capabilities.values()):
        raise ValueError("适配包 capabilities 必须是布尔值映射")

    assets = manifest.get("assets") or {}
    ddl_root = _asset_root(project_root, package.root, assets.get("ddl"))
    dml_root = _asset_root(project_root, package.root, assets.get("dml"))
    declared_areas = set(assets.get("areas") or [])
    missing_areas = sorted(REQUIRED_ASSET_AREAS - declared_areas)
    if missing_areas:
        raise ValueError(f"适配包不完整，缺少测试资产区域: {', '.join(missing_areas)}")
    asset_directory = str(assets.get("directory") or manifest["id"])
    for area in sorted(declared_areas):
        if area == "sqlxml" and capabilities.get("sqlxml") is False:
            continue
        ddl = ddl_root / asset_directory / f"{area}_ddl.sql"
        dml = dml_root / asset_directory / f"{area}_dml.sql"
        if not ddl.exists() or not dml.exists():
            raise ValueError(f"适配包不完整，缺少 {area} SQL 资产")

    namespace = manifest.get("namespace") or {}
    if namespace.get("mode") not in {"auto", "existing"}:
        raise ValueError("namespace.mode 必须是 auto 或 existing")
    if namespace.get("mode") == "auto" and not namespace.get("create_sql"):
        raise ValueError("自动命名空间缺少 create_sql")
    if namespace.get("drop_on_exit") and not namespace.get("drop_sql"):
        raise ValueError("自动清理命名空间缺少 drop_sql")
    privileges = set(manifest.get("minimum_privileges") or [])
    covered_privileges = {item.get("privilege") for item in manifest.get("privilege_checks") or []}
    if namespace.get("create_privilege"):
        covered_privileges.add(namespace["create_privilege"])
    missing_privilege_checks = sorted(privileges - covered_privileges)
    if missing_privilege_checks:
        raise ValueError(f"适配包缺少最小权限预检: {', '.join(missing_privilege_checks)}")
    for check in manifest.get("privilege_checks") or []:
        if not check.get("privilege") or not check.get("sql"):
            raise ValueError("每个 privilege_check 必须包含 privilege 和 sql")

    for record in manifest.get("known_differences") or []:
        absent = [field for field in KNOWN_DIFFERENCE_FIELDS if not record.get(field)]
        if absent:
            raise ValueError(f"已知差异 {record.get('scenario_id', '<unknown>')} 缺少: {', '.join(absent)}")
        try:
            date.fromisoformat(str(record["review_after"]))
        except ValueError as exc:
            raise ValueError(f"已知差异 {record['scenario_id']} 的 review_after 必须是 ISO 日期") from exc
        if not re.fullmatch(r"https?://.+", str(record["issue_url"])):
            raise ValueError(f"已知差异 {record['scenario_id']} 的 issue_url 必须是 HTTP(S) URL")
    if manifest["trust"] == "official":
        combinations = manifest.get("validated_combinations") or []
        if not combinations:
            raise ValueError("官方适配包必须至少包含一个已验证组合")
        for combination in combinations:
            evidence = combination.get("evidence")
            if not combination.get("database_version") or not combination.get("driver_version") or not evidence:
                raise ValueError("已验证组合必须包含数据库版本、驱动版本和证据")
            if not (package.root / evidence).is_file():
                raise ValueError(f"官方适配证据不存在: {evidence}")


def build_runtime_config(
    project_root: Path,
    user_config: dict[str, Any],
    adapter: AdapterPackage,
    driver_artifact: dict[str, Any],
) -> dict[str, Any]:
    manifest = adapter.manifest
    db = user_config.get("db") or {}
    password_env = db.get("password_env", "DB_PASSWORD")
    password = os.environ.get(password_env, "")
    if not password:
        raise ValueError(f"环境变量 {password_env} 未设置")
    identity = manifest["identity"]
    versions = manifest["supported_versions"]
    namespace_spec = dict(manifest["namespace"])
    namespace_user = user_config.get("namespace") or {}
    namespace_mode = namespace_user.get("mode", namespace_spec.get("mode", "existing"))
    namespace_name = namespace_user.get("name")
    if not namespace_name:
        if namespace_mode == "auto":
            namespace_name = f"jdbc_test_{secrets.token_hex(6)}"
        elif namespace_spec.get("name_from") == "username":
            namespace_name = db.get("username", "")
        else:
            namespace_name = namespace_spec.get("name", "")

    assets = manifest["assets"]
    ddl_root = _asset_root(project_root, adapter.root, assets["ddl"])
    dml_root = _asset_root(project_root, adapter.root, assets["dml"])
    pool_root = _asset_root(project_root, adapter.root, assets.get("pool", "pool"))

    runtime = {
        "schema_version": "1.0.0",
        "db": {
            "type": manifest["id"],
            "adapter_id": manifest["id"],
            "asset_id": str(assets.get("directory") or manifest["id"]),
            "dialect": manifest["dialect"],
            "url": db.get("url", ""),
            "username": db.get("username", ""),
            "password": password,
            "driver_class": manifest["driver"]["class"],
            "identifier_quote": manifest.get("identifier_quote", '"'),
            "expected_database_product_regex": identity["database_product_regex"],
            "expected_driver_name_regex": identity["driver_name_regex"],
            "database_version_min": versions.get("database", {}).get("min", ""),
            "database_version_max": versions.get("database", {}).get("max", ""),
            "driver_version_min": versions.get("driver", {}).get("min", ""),
            "driver_version_max": versions.get("driver", {}).get("max", ""),
        },
        "namespace": {
            "mode": namespace_mode,
            "name": namespace_name,
            "selection": namespace_spec.get("selection", "none"),
            "create_sql": namespace_spec.get("create_sql", ""),
            "drop_sql": namespace_spec.get("drop_sql", ""),
            "select_sql": namespace_spec.get("select_sql", ""),
            "drop_on_exit": bool(namespace_spec.get("drop_on_exit", False) and namespace_mode == "auto"),
            "destructive_consent": bool(namespace_user.get("destructive_consent", False)),
        },
        "preflight": {
            "namespace_create_privilege": namespace_spec.get("create_privilege", ""),
            "probe_name": f"jdbc_priv_{secrets.token_hex(4)}",
            "privilege_checks": manifest.get("privilege_checks") or [],
        },
        "ddl": {"base_path": str(ddl_root)},
        "dml": {"base_path": str(dml_root)},
        "pool": {"profile_dir": str(pool_root)},
        "concurrency": {"enabled": False, "threads": 1, "timeout": 300000},
        "execution": {"mode": (user_config.get("execution") or {}).get("mode", "local")},
        "report": user_config.get("report") or {"output_dir": "report", "format": ["json", "html", "markdown"]},
        "test_filter": user_config.get("test_filter") or {"include_tests": [], "exclude_tests": [], "timeout": 60000},
        "adapter": {
            "id": manifest["id"],
            "name": manifest["name"],
            "version": manifest["version"],
            "trust": manifest["trust"],
            "revision": manifest.get("revision", "release"),
            "capabilities": manifest["capabilities"],
            "validated_combinations": manifest["validated_combinations"],
            "experimental_override": bool(user_config.get("experimental_override", False)),
        },
        "driver_artifact": driver_artifact,
        "known_deviations": manifest.get("known_differences") or [],
    }
    if not runtime["db"]["url"] or not runtime["db"]["username"]:
        raise ValueError("本地配置必须包含 db.url 和 db.username")
    if not runtime["namespace"]["destructive_consent"]:
        raise ValueError("未设置 namespace.destructive_consent=true；只允许预检，不执行测试")
    return runtime


def resolve_driver(project_root: Path, user_config: dict[str, Any], adapter: AdapterPackage) -> dict[str, Any]:
    requested = (user_config.get("db") or {}).get("driver") or adapter.manifest["driver"].get("source")
    if not isinstance(requested, dict):
        raise ValueError("驱动来源必须是固定 Maven 坐标或本地 JAR")
    kind = requested.get("kind")
    if kind == "local":
        path = Path(requested.get("path", ""))
        if not path.is_absolute():
            path = project_root / path
        if not path.is_file():
            raise ValueError(f"本地 JDBC 驱动不存在: {path}")
        return {"source": "local", "path": str(path.resolve()), "sha256": sha256(path)}
    if kind != "maven":
        raise ValueError("驱动来源 kind 必须是 maven 或 local")
    coordinate = requested.get("coordinate", "")
    parts = coordinate.split(":")
    if len(parts) != 3 or any(not part for part in parts):
        raise ValueError("Maven 驱动坐标必须固定为 groupId:artifactId:version")
    group_id, artifact_id, version = parts
    jar = Path.home() / ".m2" / "repository" / Path(*group_id.split(".")) / artifact_id / version / f"{artifact_id}-{version}.jar"
    if not jar.exists():
        result = subprocess.run(
            ["mvn", "-q", "dependency:get", f"-Dartifact={coordinate}"],
            cwd=project_root,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0 or not jar.exists():
            raise ValueError(f"无法解析固定 Maven 驱动 {coordinate}: {result.stderr[-300:]}")
    return {"source": "maven", "coordinate": coordinate, "path": str(jar), "sha256": sha256(jar)}


def runtime_dependencies() -> dict[str, str | None]:
    return {
        "python": shutil.which("python3") or shutil.which("python"),
        "java": shutil.which("java"),
        "maven": shutil.which("mvn"),
    }


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _asset_root(project_root: Path, adapter_root: Path, value: str | None) -> Path:
    path = Path(value or "")
    if path.is_absolute():
        return path
    project_path = project_root / path
    if project_path.exists():
        return project_path.resolve()
    return (adapter_root / path).resolve()
