#!/bin/sh
set -eu
base="${JDBC_TEST_BASE_URL:-http://127.0.0.1:8080}"
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
curl -fsS "$base/actuator/health" | tee "$temporary/health"
grep -q '"status":"UP"' "$temporary/health"
curl -fsS "$base/api/test-catalog" > "$temporary/catalog"
grep -q '"totalScenarioCount":202' "$temporary/catalog"
for route in / /drivers /connections /runs /runs/new /unknown-client-route; do
  curl -fsS -H 'Accept: text/html' "$base$route" > "$temporary/index"
  grep -q 'id="root"' "$temporary/index"
done
for route in /api/unknown /actuator/unknown /assets/missing.js; do
  code="$(curl -sS -o "$temporary/error" -w '%{http_code}' -H 'Accept: text/html' "$base$route")"
  test "$code" = 404
  if grep -q 'id="root"' "$temporary/error"; then exit 1; fi
done
asset="$(sed -n 's/.*src="\([^"]*\/assets\/[^" ]*\.js\)".*/\1/p' "$temporary/index" | head -1)"
test -n "$asset"
curl -fsS "$base$asset" > /dev/null
printf '\nProduction health, catalog, SPA, API exclusions and static asset: PASS\n'
