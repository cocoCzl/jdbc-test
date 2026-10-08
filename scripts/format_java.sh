#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

case "${1:-}" in
  apply|check) mode="$1" ;;
  *) echo "Usage: scripts/format_java.sh apply|check [module/src/{main,test}/java/file.java ...]" >&2; exit 2 ;;
esac
shift

idea_dir="${IDEA_HOME:-}"
if [[ -z "$idea_dir" ]]; then
  for candidate in "$HOME/Applications/IntelliJ IDEA.app/Contents" "/Applications/IntelliJ IDEA.app/Contents"; do
    if [[ -x "$candidate/bin/format.sh" ]]; then idea_dir="$candidate"; break; fi
  done
fi
if [[ ! -x "$idea_dir/bin/format.sh" ]]; then
  echo 'Set IDEA_HOME to an IDEA installation directory containing bin/format.sh (macOS: app/Contents).' >&2
  exit 2
fi

paths=()
if [[ $# -gt 0 ]]; then
  for file in "$@"; do
    case "$file" in
      jdbc-test-core/src/main/java/*.java|jdbc-test-core/src/test/java/*.java|jdbc-test-suite/src/main/java/*.java|jdbc-test-suite/src/test/java/*.java|jdbc-test-worker/src/main/java/*.java|jdbc-test-worker/src/test/java/*.java|jdbc-test-server/src/main/java/*.java|jdbc-test-server/src/test/java/*.java) ;;
      *) echo "Not a Java source in the four modules: $file" >&2; exit 2 ;;
    esac
    if [[ "$file" == *'/../'* || ! -f "$file" || -L "$file" ]]; then
      echo "Invalid Java source path: $file" >&2; exit 2
    fi
    paths+=("$file")
  done
else
  for module in core suite worker server; do
    for scope in main test; do
      paths+=("jdbc-test-$module/src/$scope/java")
    done
  done
fi

# Save edits and exit the IDE normally first; do not bypass its instance lock.
formatter_args=(-s "$repo_dir/code-style/java.xml" -r -m '*.java' -charset UTF-8)
if [[ "$mode" == check ]]; then formatter_args+=(-d); fi
exec "$idea_dir/bin/format.sh" "${formatter_args[@]}" "${paths[@]}"
