#!/bin/sh
# Host dependencies: Docker + Compose and POSIX shell only.
set -eu
umask 077
cd "$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
mode=''
project=''
port=''
new=''
stop=''
host=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --bundled-pg) [ -z "$mode" ] || { echo "Select exactly one Docker PG mode." >&2; exit 1; }; mode=bundled ;;
    --external-pg) [ -z "$mode" ] || { echo "Select exactly one Docker PG mode." >&2; exit 1; }; mode=external ;;
    --new-install) new=--new-install ;;
    --stop) stop=--stop ;;
    --host-pg) host=yes ;;
    --project|--port)
      option="$1"; shift; [ "$#" -gt 0 ] || { echo 'Missing option value.' >&2; exit 1; }
      if [ "$option" = --project ]; then project="$1"; else port="$1"; fi ;;
    *) echo 'Use docker --bundled-pg|--external-pg [--project NAME] [--port PORT] [--host-pg] [--stop]. Never pass secrets as arguments.' >&2; exit 1 ;;
  esac
  shift
done
[ -n "$mode" ] || { echo 'Select exactly one Docker PG mode.' >&2; exit 1; }
docker compose version >/dev/null
docker info >/dev/null
# Unified .env is the sole source. Do not silently inherit a second secret/config source.
for name in COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROJECT_NAME POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD JDBC_TEST_MASTER_KEY JDBC_TEST_PORT JDBC_TEST_BIND_ADDRESS JDBC_TEST_MAX_CONCURRENT_RUNS JDBC_TEST_RUN_TIMEOUT JAVA_TOOL_OPTIONS; do
  unset "$name"
done
image="${JDBC_TEST_SETUP_IMAGE:-jdbc-test:setup}"
if [ -z "${JDBC_TEST_SETUP_IMAGE:-}" ]; then docker build -t "$image" .; fi
export JDBC_TEST_SETUP_IMAGE="$image"
interactive=-i
if [ -t 0 ] && [ -t 1 ]; then interactive=-it; fi
helper() {
  input_mode="$interactive"
  if [ "$1" = docker-info ]; then input_mode=-i; fi
  docker run --rm "$input_mode" --mount "type=bind,source=$(pwd -P),target=/deployment" \
    --network "${JDBC_TEST_SETUP_NETWORK:-bridge}" --add-host host.docker.internal:host-gateway \
    --user "$(id -u):$(id -g)" --workdir /deployment --entrypoint java "$image" \
    -Dloader.main=com.jdbctest.server.deploy.UnifiedLauncher -cp /app/server/jdbc-test-server.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}
set -- --mode "$mode"
if [ -n "$project" ]; then set -- "$@" --project "$project"; fi
project="$(helper docker-info "$@" --project-only)"
assert_empty() {
  volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
  containers="$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
  directory_containers="$(docker ps -aq --filter "label=com.docker.compose.project.working_dir=$(pwd -P)")"
  if [ -n "$volumes$containers$directory_containers" ] || docker volume inspect "${project}_postgres_data" >/dev/null 2>&1 || docker volume inspect "${project}_jdbc_test_data" >/dev/null 2>&1; then
    echo 'Existing project/path/volume state found with missing configuration/Key. Restore the original .env and Key; no replacement generated.' >&2; exit 1
  fi
}
mkdir .docker-launch.lock || { echo 'Another Docker launch is running; configuration was not changed.' >&2; exit 1; }
trap 'rm -f .docker-launch.lock/compose.env .docker-launch.lock/platform.env; rmdir .docker-launch.lock 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
if [ ! -e .env ] && [ ! -L .env ]; then
  [ -z "$stop" ] || { echo 'Restore the original .env to stop the original project safely.' >&2; exit 1; }
  assert_empty
  set -- --mode "$mode" --project "$project" --docker-state-checked
  if [ -n "$new" ]; then set -- "$@" "$new"; fi
  if [ -n "$port" ]; then set -- "$@" --port "$port"; fi
  helper init-docker "$@"
  assert_empty
  # Publish the complete private file atomically; never replace a concurrent .env.
  ln .docker-launch.lock/platform.env .env
fi
set -- --mode "$mode" --project "$project"
if [ "$(helper docker-info "$@" --needs-key)" = yes ]; then assert_empty; fi
if [ -n "$new" ]; then set -- "$@" "$new"; fi
if [ -n "$stop" ]; then set -- "$@" "$stop"; fi
if [ -n "$port" ]; then set -- "$@" --port "$port"; fi
helper prepare-docker "$@" --docker-state-checked
if [ "$mode" = bundled ]; then compose=docker-compose.yml; else compose=docker-compose.external.yml; fi
set -- --env-file .docker-launch.lock/compose.env -p "$project" -f "$compose"
if [ -n "$host" ]; then set -- "$@" -f docker-compose.host-gateway.yml; fi
# Static validation is redacted; formal up is the operation that runs migrations.
docker compose "$@" config --quiet
if [ -n "$stop" ]; then
  docker compose "$@" stop
  echo 'Stopped. PG volumes, application data, .env and Key retained.'
else
  docker compose "$@" up -d --build --wait
  echo 'Ready. Website binding:'
  docker compose "$@" port jdbc-test 8080
  echo 'Open http://<printed-host>:<printed-port>. Next time rerun this command; append --stop to stop and retain data.'
fi
