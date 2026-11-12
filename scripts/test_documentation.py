"""Repository and packaged Markdown paths/anchors, without external requests."""
from __future__ import annotations

import re
import tempfile
import unittest
import zipfile
from pathlib import Path
from urllib.parse import unquote

import build_release
from documentation import LINK, REPOSITORY, repository_links


ROOT = Path(__file__).resolve().parent.parent


def prose(content: str) -> str:
    return re.sub(r"^```[^\n]*\n.*?^```\s*$", "", content, flags=re.M | re.S)


def anchors(content: str) -> set[str]:
    content = prose(content)
    result = set(re.findall(r'<a\s+id="([^"]+)"', content))
    counts: dict[str, int] = {}
    for heading in re.findall(r"^#{1,6}\s+(.+)$", content, flags=re.M):
        slug = re.sub(r"[^\w -]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(f"{slug}-{count}" if count else slug)
    return result


def assert_document_links(test: unittest.TestCase, root: Path, documents: list[Path]) -> None:
    for document in documents:
        raw = document.read_text(encoding="utf-8")
        test.assertEqual(0, len(re.findall(r"^```", raw, re.M)) % 2,
                         f"Unclosed code fence: {document}")
        content = prose(raw)
        for match in LINK.finditer(content):
            target = unquote(match.group("target"))
            if target.startswith(REPOSITORY):
                path = ROOT / target[len(REPOSITORY):].split("#", 1)[0]
            elif re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target):
                continue
            else:
                path = document if target.startswith("#") else document.parent / target.split("#", 1)[0]
                test.assertTrue(path.resolve().is_relative_to(root.resolve()),
                                f"Outside document root: {document}: {target}")
            test.assertTrue(path.is_file(), f"Missing link: {document}: {target}")
            if "#" in target and path.suffix == ".md":
                fragment = target.split("#", 1)[1]
                test.assertIn(fragment, anchors(path.read_text(encoding="utf-8")),
                              f"Missing anchor: {document}: {target}")
        for line in content.splitlines():
            if re.match(r"^\|[\s:|-]+\|$", line):
                continue
            # Tables in the maintained guides have one physical line per row.
            if line.startswith("|"):
                test.assertTrue(line.endswith("|"), f"Unclosed table row: {document}: {line}")


class DocumentationTest(unittest.TestCase):
    def test_repository_document_paths_and_anchors(self):
        documents = [*ROOT.glob("README*.md"), *(ROOT / "docs").rglob("*.md")]
        for area in ("examples", "jdbc-test-server", "jdbc-test-worker"):
            documents.extend((ROOT / area).glob("README*.md"))
        assert_document_links(self, ROOT, documents)

    def test_language_entry_and_legacy_home(self):
        chinese = (ROOT / "README.md").read_text()
        english = (ROOT / "README.en.md").read_text()
        legacy = (ROOT / "README.zh-CN.md").read_text()
        self.assertIn("在真实数据库上验证 JDBC 驱动接口行为", chinese)
        self.assertIn("[English](README.en.md)", chinese)
        self.assertIn("[简体中文](README.md)", english)
        self.assertIn("[README.md](README.md)", legacy)
        self.assertNotIn("| 数据库 |", legacy)
        for name in ("README.md", "README.en.md"):
            index = (ROOT / "docs" / name).read_text()
            for guide in ("usage", "deployment", "development", "internal-regression",
                          "jdbc-capability-contracts", "database-validation"):
                self.assertIn(f"]({guide}", index)

    def test_source_release_markdown_links_without_docs_or_checkout(self):
        with tempfile.TemporaryDirectory(prefix="jdbc-doc-release-") as temporary:
            archive, _ = build_release.build(Path(temporary))
            with zipfile.ZipFile(archive) as bundle:
                names = bundle.namelist()
                self.assertFalse(any("/docs/" in name or "/.local/" in name for name in names))
                bundle.extractall(Path(temporary) / "unpacked")
            directory = Path(temporary) / "unpacked" / names[0].split("/", 1)[0]
            self.assertTrue((directory / "README.en.md").is_file())
            assert_document_links(self, directory, list(directory.rglob("*.md")))

    def test_packaging_keeps_local_links_and_preserves_external_method_fragments(self):
        content = "[local](usage.md#results) [source](development.md#api) [method](https://example.org/#call(int))"
        transformed = repository_links(content, "docs/README.md", {"docs/usage.md"})
        self.assertIn("(usage.md#results)", transformed)
        self.assertIn(f"({REPOSITORY}docs/development.md#api)", transformed)
        self.assertIn("(https://example.org/#call(int))", transformed)


if __name__ == "__main__":
    unittest.main()
