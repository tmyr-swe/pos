#!/usr/bin/env bash
# Universal build: the shaded jar that runs on any Java 25+ desktop.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests clean package

mkdir -p dist
cp target/chegg-pos.jar dist/chegg-pos.jar

cat > dist/run-chegg-pos.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exec java --enable-native-access=ALL-UNNAMED -jar "$(dirname "$0")/chegg-pos.jar" "$@"
EOF
chmod +x dist/run-chegg-pos.sh

echo "Portable build ready: $ROOT/dist/chegg-pos.jar"
