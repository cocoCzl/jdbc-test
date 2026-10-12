#!/bin/sh
set -eu
deploy_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$deploy_root"
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 is required. Install Java 21 and add java to PATH." >&2
  exit 1
fi
if [ ! -f config/application.properties ]; then
  echo "Copy config/application.properties.example to config/application.properties, then set the PostgreSQL connection and master key." >&2
  exit 1
fi
for artifact in server/jdbc-test-server.jar worker/jdbc-test-worker.jar web/index.html; do
  if [ ! -f "$artifact" ]; then
    echo "Missing $artifact. Extract the complete server deployment archive." >&2
    exit 1
  fi
done
# JAVA_TOOL_OPTIONS is consumed by Java itself and inherited by Worker JVMs.
exec java -jar server/jdbc-test-server.jar \
  --spring.profiles.active=native \
  --spring.config.additional-location=file:./config/application.properties "$@"
