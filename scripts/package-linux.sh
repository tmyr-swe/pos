#!/usr/bin/env bash
# Linux build: self-contained app image, tarball and (when dpkg is available) .deb.
#
# jpackage cannot cross-compile, so run this on Linux. For Windows run
# scripts/package-windows.ps1 on Windows or in the CI Windows job.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_NAME="Chegg POS"
APP_VERSION="$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>(.*)<\/version>.*/\1/')"
MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.sql,java.prefs,jdk.unsupported"

# Prefer the JDK that built the app. Some distro JDKs ship a modified
# java.security that jlink refuses; set JPACKAGE=/path/to/jpackage to override.
if [[ -z "${JPACKAGE:-}" ]]; then
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
        JPACKAGE="$JAVA_HOME/bin/jpackage"
    else
        JPACKAGE="jpackage"
    fi
fi

mvn -q -DskipTests clean package

rm -rf target/jpackage-input dist/linux
mkdir -p target/jpackage-input dist/linux
cp target/chegg-pos.jar target/jpackage-input/

COMMON=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input target/jpackage-input
    --main-jar chegg-pos.jar
    --main-class com.chegg.pos.Main
    --icon packaging/chegg-pos.png
    --description "Chegg point of sale"
    --vendor "Chegg"
    --add-modules "$MODULES"
    --java-options "--enable-native-access=ALL-UNNAMED"
    --java-options "-Dfile.encoding=UTF-8"
)

"$JPACKAGE" "${COMMON[@]}" --type app-image --dest dist/linux

mkdir -p dist
tar -C dist/linux -czf "dist/chegg-pos-${APP_VERSION}-linux-x86_64.tar.gz" "$APP_NAME"

if command -v dpkg-deb >/dev/null 2>&1; then
    "$JPACKAGE" "${COMMON[@]}" \
        --type deb \
        --linux-package-name chegg-pos \
        --linux-shortcut \
        --linux-menu-group Office \
        --linux-deb-maintainer "Chegg <support@chegg.example>" \
        --dest dist
else
    echo "dpkg-deb not found: skipped .deb (app image and tarball are ready)."
fi

echo "Linux build ready in $ROOT/dist"
