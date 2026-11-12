#!/usr/bin/env bash
# Builds release APKs for both flavours and runs unit tests. Never contacts the TV.
set -eu
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
mkdir -p out
./gradlew --console=plain testBaseDebugUnitTest assembleRelease > out/build.log 2>&1 || { grep -E '^e: |error:|What went wrong|FAILED' -A3 out/build.log | head -60; exit 1; }
for f in base google; do cp "app/build/outputs/apk/$f/release/app-$f-release.apk" "out/epple-tv-$f-release.apk"; done
ls -l out/*.apk; grep -E "BUILD SUCCESSFUL" out/build.log
