"""Export existing CLI packages as portable data-only Web import archives."""
from __future__ import annotations

import copy
import json
import re
import zipfile
from pathlib import Path

from adapter_manifest import validate_adapter_metadata
from adapter_runtime import _asset_root, load_adapter


def pack_adapter(project_root: Path, reference: str, output: Path) -> None:
    package = load_adapter(project_root, reference)
    manifest = copy.deepcopy(package.manifest)
    assets = manifest["assets"]
    directory = assets.get("directory", manifest["id"])
    files: dict[str, bytes] = {}
    for kind in ("ddl", "dml", "pool"):
        root = _asset_root(project_root, package.root, assets.get(kind, kind))
        source = root / (f"{directory}.yaml" if kind == "pool" else directory)
        candidates = [source] if kind == "pool" else sorted(source.rglob("*.sql"))
        for path in candidates:
            if not path.is_file():
                continue
            if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("ADAPTER_RESOURCE_PATH_INVALID")
            files[f"{kind}/{path.relative_to(root).as_posix()}"] = path.read_bytes()
        assets[kind] = kind
    pool = f"pool/{directory}.yaml"
    if pool not in files:
        raise ValueError(f"ADAPTER_RESOURCE_MISSING: {pool}")
    for item in manifest["validated_combinations"]:
        evidence = item.get("evidence")
        if evidence:
            path = package.root / evidence
            if not path.resolve().is_relative_to(package.root.resolve()) or path.is_symlink():
                raise ValueError("ADAPTER_RESOURCE_PATH_INVALID")
            # Web bundles only retain explicitly declared evidence under evidence/.
            name = f"evidence/{len(files)}-{path.name}"
            files[name] = path.read_bytes()
            item["evidence"] = name
    validate_adapter_metadata(manifest)
    files["adapter.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    if len(files) > 512 or sum(map(len, files.values())) > 8 * 1_048_576:
        raise ValueError("ADAPTER_PACKAGE_TOO_LARGE")
    for name, data in files.items():
        if (not re.fullmatch(r"[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*", name)
                or ".." in name or re.search(r"\.(jar|class|exe|so|dll|sh)$", name, re.I)):
            raise ValueError("ADAPTER_RESOURCE_PATH_INVALID")
        if len(data) > 1_048_576:
            raise ValueError("ADAPTER_PACKAGE_TOO_LARGE")
    # Exclusive creation: never overwrite user artifacts. No driver JARs or connection config copied.
    with output.open("xb") as stream:
        try:
            with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
                for name, data in sorted(files.items()):
                    archive.writestr(name, data)
        except BaseException:
            output.unlink(missing_ok=True)
            raise
