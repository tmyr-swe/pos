#!/usr/bin/env bash
# Construct each role workspace once and exit. Needs a display (X server).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests package

HOME_DIR="${POS_HOME:-$ROOT/target/ui-smoke-home}"
rm -rf "$HOME_DIR"
java --enable-native-access=ALL-UNNAMED \
    -Dpos.home="$HOME_DIR" \
    -Dpos.logs="$HOME_DIR/logs" \
    -jar target/chegg-pos.jar --ui-smoke-test
