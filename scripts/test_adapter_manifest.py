import copy
import json
import tempfile
import unittest
from pathlib import Path

from adapter_manifest import parse_adapter_json, read_adapter_json, validate_adapter_metadata
from adapter_runtime import discover_adapters, load_adapter


ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "jdbc-test-core/src/test/resources/adapter-contract"


class AdapterManifestTest(unittest.TestCase):
    def test_shared_java_python_metadata_contract(self):
        base = json.loads((FIXTURES / "base.json").read_text(encoding="utf-8"))
        cases = json.loads((FIXTURES / "cases.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(case["name"]):
                manifest = copy.deepcopy(base)
                for update in case.get("updates", []):
                    parent = manifest
                    for key in update["path"][:-1]:
                        parent = parent[key]
                    parent[update["path"][-1]] = update["value"]
                for path in case.get("remove", []):
                    parent = manifest
                    for key in path[:-1]:
                        parent = parent[key]
                    del parent[path[-1]]
                text = case.get("raw", json.dumps(manifest, ensure_ascii=False))
                if case["valid"]:
                    validate_adapter_metadata(parse_adapter_json(text))
                else:
                    with self.assertRaises(ValueError):
                        validate_adapter_metadata(parse_adapter_json(text))

    def test_index_failures_are_explicit_and_do_not_scan_unregistered_directories(self):
        base = (FIXTURES / "base.json").read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="jdbc-adapter-index-") as temporary:
            project = Path(temporary)
            adapters = project / "adapters"
            (adapters / "postgresql").mkdir(parents=True)
            (adapters / "postgresql/adapter.json").write_text(base, encoding="utf-8")
            for text in ("null", "[]", "{}", '["../escape"]', '["/absolute"]',
                         '["postgresql","postgresql"]', '["missing"]'):
                (adapters / "catalog.json").write_text(text, encoding="utf-8")
                with self.subTest(text), self.assertRaises(ValueError):
                    discover_adapters(project)
            (adapters / "catalog.json").unlink()
            with self.assertRaises(ValueError):
                discover_adapters(project)
            (adapters / "catalog.json").write_text('["postgresql"]', encoding="utf-8")
            (adapters / "ignored-broken").mkdir()
            (adapters / "ignored-broken/adapter.json").write_text("broken", encoding="utf-8")
            self.assertEqual(["postgresql"], list(discover_adapters(project)))
            (adapters / "catalog.json").write_text('["custom-db"]', encoding="utf-8")
            (adapters / "custom-db").mkdir()
            (adapters / "custom-db/adapter.json").write_text(base, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "ID_MISMATCH"):
                discover_adapters(project)
            manifest = json.loads(base)
            manifest.update(id="custom-db", trust="local")
            manifest["assets"]["directory"] = "postgresql"
            (adapters / "custom-db/adapter.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(["custom-db"], list(discover_adapters(project)))

    def test_explicit_local_path_remains_independent_of_builtin_index(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-adapter-local-") as temporary:
            project = Path(temporary)
            package = load_adapter(ROOT, "examples/adapters/postgresql-local")
            manifest = copy.deepcopy(package.manifest)
            for area in ("ddl", "dml", "pool"):
                manifest["assets"][area] = str(ROOT / area)
            local = project / "private-adapter"
            local.mkdir()
            (local / "adapter.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual("postgresql-local", load_adapter(project, str(local)).adapter_id)

    def test_invalid_encoding_and_resource_limits_fail_closed(self):
        for text in ('{"x":' + '9' * 129 + '}', '[' * 65 + '0' + ']' * 65,
                     '"' + 'x' * 1_048_576 + '"', '{"x":1e309}'):
            with self.subTest(text[:50]), self.assertRaises(ValueError):
                parse_adapter_json(text)
        self.assertEqual('{"number": 1.5}', json.dumps(parse_adapter_json('{"number":1.5}')))
        with tempfile.TemporaryDirectory(prefix="jdbc-adapter-encoding-") as temporary:
            file = Path(temporary) / "adapter.json"
            file.write_bytes(b'\xff{}')
            with self.assertRaises(ValueError):
                read_adapter_json(file)


if __name__ == "__main__":
    unittest.main()
