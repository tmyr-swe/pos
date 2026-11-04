#!/usr/bin/env bash
# Build and run the Chegg POS smoke test against an isolated data directory.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests package

HOME_DIR="${POS_HOME:-$ROOT/target/smoke-home}"
rm -rf "$HOME_DIR"
java --enable-native-access=ALL-UNNAMED \
    -Dpos.home="$HOME_DIR" \
    -Dpos.logs="$HOME_DIR/logs" \
    -jar target/chegg-pos.jar --smoke-test
