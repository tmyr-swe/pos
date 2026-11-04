#!/usr/bin/env bash
# Run the POS from a locally built fat jar.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f target/chegg-pos.jar ]]; then
    mvn -q -DskipTests package
fi

exec java --enable-native-access=ALL-UNNAMED -jar target/chegg-pos.jar "$@"
