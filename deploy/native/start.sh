#!/bin/sh
# Compatibility entry shipped in previous bundles; all logic lives in jdbc-test.
set -eu
exec "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/jdbc-test" start "$@"
