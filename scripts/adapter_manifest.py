"""Strict, bounded metadata contract shared with Core's adapter manifest boundary."""
from __future__ import annotations

import json
import math
import re
from datetime import date
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

# Every capability used by a reportable JDBC scenario must be explicitly
# declared by an adapter.  This keeps an omitted declaration from looking like
# a supported feature (or a harmless skip) in a compatibility report.
MANAGED_CAPABILITIES = {
    "savepoint", "callable_statement", "array_type", "ref_type", "struct_type",
    "rowid_type", "sqlxml", "rowset", "blobs", "clobs", "nclobs",
    "parameter_metadata", "updatable_result_set", "scrollable_result_set",
    "generated_keys", "multiple_result_sets", "statement_cancel",
    "query_timeout", "network_timeout", "client_info", "request_boundaries",
    "sharding", "connection_pool", "xa_data_source", "java_time",
    "lob_objects", "lob_locator_updates", "batch_generated_keys", "generated_keys_by_index",
    "callable_inout", "ref_cursor", "rowid_operations", "multiple_result_sequences", "data_source",
}


MAX_BYTES = 1_048_576


def parse_adapter_json(text: str) -> Any:
    if len(text.encode("utf-8")) > MAX_BYTES:
        raise ValueError("ADAPTER_JSON_TOO_LARGE")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("ADAPTER_JSON_DUPLICATE_KEY")
            result[key] = value
        return result

    def number(value):
        if len(value) > 128:
            raise ValueError("ADAPTER_JSON_NUMBER_TOO_LONG")
        if not any(c in value for c in ".eE"):
            return int(value)
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("ADAPTER_JSON_NUMBER_OUT_OF_RANGE")
        return result

    def invalid_constant(value):
        raise ValueError("ADAPTER_JSON_NON_FINITE_NUMBER")

    try:
        value = json.loads(text, object_pairs_hook=unique, parse_int=number,
                           parse_float=number, parse_constant=invalid_constant)
    except (json.JSONDecodeError, RecursionError) as exc:
        raise ValueError("ADAPTER_JSON_INVALID") from exc

    def depth(item, level=0):
        if isinstance(item, (dict, list)):
            if level >= 64:
                raise ValueError("ADAPTER_JSON_TOO_DEEP")
            for child in item.values() if isinstance(item, dict) else item:
                depth(child, level + 1)
    depth(value)
    return value


def read_adapter_json(path: Path) -> Any:
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("ADAPTER_JSON_TOO_LARGE")
        return parse_adapter_json(raw.decode("utf-8"))
    except (OSError, UnicodeError) as exc:
        raise ValueError(f"ADAPTER_JSON_UNREADABLE: {path}") from exc


def adapter_id(value: Any) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9_-]{1,31}", value):
        raise ValueError("ADAPTER_DECLARATION_INVALID: id")
    return value


def resource_path(value: Any) -> str:
    if (not isinstance(value, str) or ".." in value or
            not re.fullmatch(r"[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*", value)):
        raise ValueError("ADAPTER_DECLARATION_INVALID: resource path")
    return value


def validate_adapter_metadata(manifest: Any, *, allow_external_asset_paths=False) -> None:
    def invalid(name):
        raise ValueError(f"ADAPTER_DECLARATION_INVALID: {name}")

    def obj(value, name):
        if not isinstance(value, dict) or not all(isinstance(k, str) for k in value):
            invalid(name)
        return value

    def text(fields, name):
        value = fields.get(name)
        if not isinstance(value, str) or not value.strip():
            invalid(name)
        return value

    def choice(fields, name, values):
        value = text(fields, name)
        if value not in values:
            invalid(name)
        return value

    def array(value, name):
        if not isinstance(value, list):
            invalid(name)
        return value

    def strings(value, name):
        values = array(value, name)
        if not all(isinstance(v, str) and v.strip() for v in values) or len(set(values)) != len(values):
            invalid(name)
        return values

    def version_range(value, name):
        obj(value, name)
        for end in ("min", "max"):
            version = value.get(end, "")
            if not isinstance(version, str) or not re.fullmatch(r"(?:[0-9]+(?:\.[0-9]+)*)?", version):
                invalid(name + "." + end)
        minimum, maximum = value.get("min", ""), value.get("max", "")
        if minimum and maximum:
            left, right = [int(v) for v in minimum.split(".")], [int(v) for v in maximum.split(".")]
            width = max(len(left), len(right))
            if left + [0] * (width - len(left)) > right + [0] * (width - len(right)):
                invalid(name + " range")

    obj(manifest, "root")
    adapter_id(manifest.get("id"))
    text(manifest, "name")
    text(manifest, "version")
    if "revision" in manifest:
        text(manifest, "revision")
    if "db_type" in manifest:
        text(manifest, "db_type")  # Unknown classifications remain in the retained declaration.
    trust = choice(manifest, "trust", {"official", "candidate", "local"})
    choice(manifest, "dialect", {"postgresql", "mysql", "oracle", "sqlserver"})
    quote = manifest.get("identifier_quote", '"')
    if not isinstance(quote, str) or quote not in {'"', "`", "["}:
        invalid("identifier_quote")
    driver = obj(manifest.get("driver"), "driver")
    text(driver, "class")
    source = obj(driver.get("source"), "driver.source")
    kind = choice(source, "kind", {"maven", "local"})
    text(source, "coordinate" if kind == "maven" else "path")
    assets = obj(manifest.get("assets"), "assets")
    for name in ("ddl", "dml", "pool"):
        value = text(assets, name) if name != "pool" or name in assets else "pool"
        if not allow_external_asset_paths:
            resource_path(value)
    adapter_id(assets.get("directory", manifest["id"]))
    areas = strings(assets.get("areas"), "assets.areas")
    if not REQUIRED_ASSET_AREAS <= set(areas) or any(not re.fullmatch(r"[a-z][a-z0-9_]*", a) for a in areas):
        invalid("assets.areas")
    capabilities = obj(manifest.get("capabilities"), "capabilities")
    if not MANAGED_CAPABILITIES <= set(capabilities) or not all(isinstance(v, bool) for v in capabilities.values()):
        invalid("capabilities")
    identity = obj(manifest.get("identity"), "identity")
    for name in ("database_product_regex", "driver_name_regex"):
        try:
            re.compile(text(identity, name))
        except re.error:
            invalid("identity." + name)
    versions = obj(manifest.get("supported_versions"), "supported_versions")
    for target in ("database", "driver"):
        version_range(versions.get(target, {}), target)
    namespace = obj(manifest.get("namespace"), "namespace")
    mode = choice(namespace, "mode", {"auto", "existing"})
    if "selection" in namespace:
        selection = choice(namespace, "selection", {"catalog", "schema", "none"})
        expected = "catalog" if manifest["dialect"] == "mysql" else "schema"
        if selection not in {"none", expected}:
            invalid("namespace.selection")
    if "drop_on_exit" in namespace and not isinstance(namespace["drop_on_exit"], bool):
        invalid("namespace.drop_on_exit")
    if mode == "auto":
        text(namespace, "create_sql")
    if namespace.get("drop_on_exit", False):
        text(namespace, "drop_sql")
    privileges = strings(manifest.get("minimum_privileges"), "minimum_privileges")
    covered = set()
    if "create_privilege" in namespace:
        covered.add(text(namespace, "create_privilege"))
    for check in array(manifest.get("privilege_checks"), "privilege_checks"):
        obj(check, "privilege_checks[]")
        covered.add(text(check, "privilege"))
        text(check, "sql")
        if "cleanup_sql" in check and not isinstance(check["cleanup_sql"], str):
            invalid("privilege_checks.cleanup_sql")
    if not set(privileges) <= covered:
        invalid("minimum_privileges")
    combinations = array(manifest.get("validated_combinations"), "validated_combinations")
    if trust == "official" and not combinations:
        invalid("validated_combinations")
    for combination in combinations:
        obj(combination, "validated_combinations[]")
        text(combination, "database_version")
        text(combination, "driver_version")
        resource_path(text(combination, "evidence"))
    for difference in array(manifest.get("known_differences"), "known_differences"):
        obj(difference, "known_differences[]")
        for name in ("scenario_id", "reason", "issue_url", "review_after", "trust"):
            text(difference, name)
        choice(difference, "trust", {"official", "candidate", "local"})
        for name in ("database_version", "driver_version"):
            if isinstance(difference.get(name), dict):
                version_range(difference[name], name)
            else:
                text(difference, name)
        try:
            date.fromisoformat(text(difference, "review_after"))
        except ValueError:
            invalid("known_differences.review_after")
        if not re.fullmatch(r"https?://.+", text(difference, "issue_url")):
            invalid("known_differences.issue_url")
