from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from regression import runtime_inputs

from adapter_manifest import (
    MANAGED_CAPABILITIES, REQUIRED_ADAPTER_FIELDS, REQUIRED_ASSET_AREAS,
    KNOWN_DIFFERENCE_FIELDS, adapter_id, read_adapter_json, validate_adapter_metadata,
)


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
    ids = read_adapter_json(adapters_root / "catalog.json")
    if not isinstance(ids, list) or not ids:
        raise ValueError("ADAPTER_INDEX_MISSING_OR_INVALID")
    for value in ids:
        selected_id = adapter_id(value)
        if selected_id in result:
            raise ValueError(f"ADAPTER_INDEX_DUPLICATE: {selected_id}")
        path = adapters_root / selected_id / "adapter.json"
        if not path.resolve().is_relative_to(adapters_root.resolve()):
            raise ValueError("ADAPTER_INDEX_PATH_ESCAPE")
        manifest = read_adapter_json(path)
        validate_adapter_metadata(manifest)
        if manifest["id"] != selected_id:
            raise ValueError(f"ADAPTER_INDEX_ID_MISMATCH: {selected_id}")
        assets = manifest["assets"]
        if any(assets.get(area, area) != area for area in ("ddl", "dml", "pool")):
            raise ValueError(f"ADAPTER_PACKAGED_ASSET_ROOT_INVALID: {selected_id}")
        result[selected_id] = path.parent
    return result


def load_adapter(project_root: Path, reference: str) -> AdapterPackage:
    discovered = discover_adapters(project_root)
    if reference not in discovered:
        raise ValueError(f"BUILTIN_ADAPTER_REQUIRED: {reference}")
    path = discovered[reference]
    package = AdapterPackage(path.resolve(), read_adapter_json(path / "adapter.json"))
    validate_adapter(package, project_root, bundled=True)
    return package


def validate_adapter(package: AdapterPackage, project_root: Path, *, bundled: bool = False) -> None:
    manifest = package.manifest
    validate_adapter_metadata(manifest, allow_external_asset_paths=not bundled)
    capabilities = manifest["capabilities"]
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

    if manifest["trust"] == "official":
        for combination in manifest["validated_combinations"]:
            evidence = combination["evidence"]
            if not (package.root / evidence).is_file():
                raise ValueError(f"官方适配证据不存在: {evidence}")


def build_runtime_config(
    project_root: Path,
    user_config: dict[str, Any],
    adapter: AdapterPackage,
    driver_artifact: dict[str, Any],
    *, regression_evidence: bool = True,
) -> dict[str, Any]:
    manifest = adapter.manifest
    db = user_config.get("db") or {}
    password_env = db.get("password_env", "DB_PASSWORD")
    password = os.environ.get(password_env)
    if password is None:
        raise ValueError(f"环境变量 {password_env} 未设置")
    connection_mode = str(db.get("connection_mode", "hikari"))
    if connection_mode not in {"hikari", "driver_manager"}:
        raise ValueError("db.connection_mode 必须是 hikari 或 driver_manager")
    properties = _string_map(db.get("properties"), "db.properties")
    property_env = _string_map(db.get("property_env"), "db.property_env")
    reserved = {"user", "password"}
    forbidden = sorted(reserved & (properties.keys() | property_env.keys()))
    if forbidden:
        raise ValueError(f"db.properties 中不能配置保留属性: {', '.join(forbidden)}")
    overlap = sorted(properties.keys() & property_env.keys())
    if overlap:
        raise ValueError(f"连接属性不能同时使用普通值和环境变量: {', '.join(overlap)}")
    for key, env_name in property_env.items():
        env_value = os.environ.get(env_name)
        if env_value is None:
            raise ValueError(f"连接属性 {key} 所需环境变量 {env_name} 未设置")
        properties[key] = env_value
    providers = db.get("data_sources") or {}
    capabilities = dict(manifest["capabilities"])
    for interface, feature in (("dataSource", "data_source"),
                               ("connectionPoolDataSource", "connection_pool"),
                               ("xaDataSource", "xa_data_source")):
        capabilities[feature] = bool(providers.get(interface))
    compatible = db.get("compatible_dialect", False)
    if not isinstance(compatible, bool):
        raise ValueError("db.compatible_dialect must be a boolean")
    identity = {} if compatible else manifest["identity"]
    versions = {} if compatible else manifest["supported_versions"]
    namespace_spec = dict(manifest["namespace"])
    namespace_user = user_config.get("namespace") or {}
    namespace_mode = namespace_user.get("mode", namespace_spec.get("mode", "existing"))
    namespace_name = namespace_user.get("name")
    if not namespace_name:
        if namespace_mode == "auto":
            namespace_name = f"jdbc_test_{secrets.token_hex(6)}"
        else:
            raise ValueError("Explicit namespace.name is required for existing execution scope")

    assets = manifest["assets"]
    ddl_root = _asset_root(project_root, adapter.root, assets["ddl"])
    dml_root = _asset_root(project_root, adapter.root, assets["dml"])
    pool_root = _asset_root(project_root, adapter.root, assets.get("pool", "pool"))

    test_filter = dict(user_config.get("test_filter") or {})
    if "timeout_ms" not in test_filter and "timeout" in test_filter:
        test_filter["timeout_ms"] = test_filter.pop("timeout")
    test_filter.setdefault("include_tests", [])
    test_filter.setdefault("exclude_tests", [])
    test_filter.setdefault("timeout_ms", 60_000)

    object_prefix = "j" + secrets.token_hex(10)
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
            "driver_class": db.get("driver_class") or manifest["driver"]["class"],
            "connection_mode": connection_mode,
            "properties": properties,
            "data_sources": providers,
            "identifier_quote": manifest.get("identifier_quote", '"'),
            "expected_database_product_regex": identity.get("database_product_regex", ""),
            "expected_driver_name_regex": identity.get("driver_name_regex", ""),
            "database_version_min": versions.get("database", {}).get("min", ""),
            "database_version_max": versions.get("database", {}).get("max", ""),
            "driver_version_min": versions.get("driver", {}).get("min", ""),
            "driver_version_max": versions.get("driver", {}).get("max", ""),
        },
        "namespace": {
            "mode": namespace_mode,
            "database": namespace_user.get("database", ""),
            "name": namespace_name,
            "selection": namespace_spec.get("selection", "none"),
            "object_prefix": object_prefix,
            "ledger_path": str(project_root / "report" / (object_prefix + "-objects.yaml")),
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
        "execution": {"mode": (user_config.get("execution") or {}).get("mode", "local")},
        "report": user_config.get("report") or {"output_dir": "report", "format": ["json", "html", "markdown"]},
        "regression": user_config.get("regression") or {},
        "plan_requirements": user_config.get("plan_requirements") or {},
        "test_profile": str(user_config.get("test_profile", "full")),
        "test_filter": test_filter,
        "adapter": {
            "id": manifest["id"],
            "name": manifest["name"],
            "version": manifest["version"],
            "trust": "local" if compatible else manifest["trust"],
            "revision": manifest.get("revision", "release"),
            "capabilities": capabilities,
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
    if runtime["test_profile"] not in {"core", "full"}:
        raise ValueError("test_profile 必须是 core 或 full")
    concurrency = user_config.get("concurrency") or {}
    if concurrency.get("enabled"):
        raise ValueError("并发压测不属于 JDBC 4.3 兼容性基线；请移除 concurrency.enabled")
    required = runtime["plan_requirements"].get("required_capabilities", [])
    if not isinstance(required, list) or any(x not in {"transactions", "batch_updates", "savepoint"} for x in required):
        raise ValueError("plan_requirements.required_capabilities supports transactions, batch_updates, savepoint")
    if regression_evidence:
        runtime["regression_inputs"] = runtime_inputs(project_root, runtime, manifest)
    return runtime


def _string_map(value: Any, field: str) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError(f"{field} 必须是字符串映射")
    result: dict[str, str] = {}
    for key, item in value.items():
        if not isinstance(key, str) or not key or not isinstance(item, str) or not item:
            raise ValueError(f"{field} 的键和值必须是非空字符串")
        result[key] = item
    return result


def resolve_driver(project_root: Path, user_config: dict[str, Any], adapter: AdapterPackage) -> dict[str, Any]:
    requested = (user_config.get("db") or {}).get("driver") or adapter.manifest["driver"].get("source")
    if not isinstance(requested, dict):
        raise ValueError("驱动来源必须是固定 Maven 坐标或本地 JAR")
    kind = requested.get("kind")
    if kind == "local":
        path = Path(requested.get("path", ""))
        if not path.is_absolute():
            path = project_root / path
        if path.is_file():
            jars = [path.resolve()]
        elif path.is_dir():
            jars = sorted((jar.resolve() for jar in path.glob("*.jar") if jar.is_file()), key=lambda jar: jar.name)
            if not jars:
                raise ValueError(f"本地 JDBC 驱动目录中没有 JAR: {path}")
        else:
            raise ValueError(f"本地 JDBC 驱动不存在: {path}")
        return {
            "source": "local",
            "path": str(path.resolve()),
            "classpath": [str(jar) for jar in jars],
            "sha256": sha256_bundle(jars),
        }
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
    return {
        "source": "maven",
        "coordinate": coordinate,
        "path": str(jar),
        "classpath": [str(jar)],
        "sha256": sha256_bundle([jar]),
    }


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


def sha256_bundle(paths: list[Path]) -> str:
    """Create a stable fingerprint for one driver JAR or a directory of JARs."""
    digest = hashlib.sha256()
    for path in paths:
        digest.update(path.name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256(path).encode("ascii"))
        digest.update(b"\0")
    return digest.hexdigest()


def _asset_root(project_root: Path, adapter_root: Path, value: str | None) -> Path:
    path = Path(value or "")
    if path.is_absolute():
        return path
    package_path = adapter_root / path
    if package_path.exists():
        return package_path.resolve()
    project_path = project_root / path
    if project_path.exists():
        return project_path.resolve()
    return package_path.resolve()
