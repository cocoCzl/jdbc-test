"""Small Markdown helpers shared by packaging and document contract checks."""
from __future__ import annotations

import posixpath
import re


REPOSITORY = "https://github.com/cocoCzl/jdbc-test/blob/master/"
# Also accept parentheses in destinations such as Javadoc method fragments.
LINK = re.compile(r"\]\((?P<target>(?:[^()\s]|\([^()\s]*\))+)\)")


def repository_links(content: str, document: str, included: set[str]) -> str:
    """Keep packaged links local; point excluded source/evidence to the repository."""
    def replace(match: re.Match[str]) -> str:
        target = match.group("target")
        if target.startswith("#") or re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target):
            return match.group()
        path, separator, fragment = target.partition("#")
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(document), path))
        if resolved in included:
            return match.group()
        return f"]({REPOSITORY}{resolved}{separator}{fragment})"

    return LINK.sub(replace, content)
