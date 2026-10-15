#!/bin/sh
set -eu
base="${JDBC_TEST_BASE_URL:-http://127.0.0.1:8080}"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
fail() {
  printf 'Production smoke failed: %s\n' "$1" >&2
  exit 1
}
curl -fsS "$base/actuator/health" > "$temporary/health" || fail 'health request'
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$temporary/health" || fail 'health is not UP'
curl -fsS "$base/api/test-catalog" > "$temporary/catalog" || fail 'catalog request'
# Scenario inventory grows independently of this deployment smoke test.
grep -Eq '"totalScenarioCount"[[:space:]]*:[[:space:]]*[1-9][0-9]*[[:space:]]*([,}]|$)' "$temporary/catalog" \
  || fail 'catalog must report a positive totalScenarioCount'
for route in / /drivers /connections /runs /runs/new /unknown-client-route; do
  curl -fsS -H 'Accept: text/html' "$base$route" > "$temporary/index" || fail "SPA request: $route"
  grep -q 'id="root"' "$temporary/index" || fail "SPA root missing: $route"
done
for route in /api/unknown /actuator/unknown /assets/missing.js; do
  code="$(curl -sS -o "$temporary/error" -w '%{http_code}' -H 'Accept: text/html' "$base$route")" \
    || fail "exclusion request: $route"
  test "$code" = 404 || fail "expected 404: $route"
  if grep -q 'id="root"' "$temporary/error"; then fail "SPA served for excluded route: $route"; fi
done
asset="$(sed -n 's/.*src="\([^"]*\/assets\/[^" ]*\.js\)".*/\1/p' "$temporary/index" | head -1)"
test -n "$asset" || fail 'JavaScript asset missing from SPA'
curl -fsS "$base$asset" > /dev/null || fail 'JavaScript asset request'
printf '\nProduction health, catalog, SPA, API exclusions and static asset: PASS\n'
