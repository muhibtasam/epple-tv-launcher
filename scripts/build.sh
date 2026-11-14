#!/usr/bin/env bash
# Builds release APKs for both flavours and runs unit tests. Never contacts the TV.
set -eu
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
mkdir -p out
# Your pictures in ./wallpapers go into the APK (assets/wallpapers), each resized to fit 1920x1080 as a JPEG so the APK
# stays small; Epple TV takes in any it hasn't had yet after the install. The folder is rebuilt each time.
WP=app/build/bundled-wallpapers/wallpapers; rm -rf "$WP"; mkdir -p "$WP"
for f in wallpapers/*; do
  case "$(echo "${f##*.}" | tr 'A-Z' 'a-z')" in jpg|jpeg|png|webp) ;; *) continue ;; esac
  n="$(basename "${f%.*}").jpg"
  sips -Z 1920 -s format jpeg -s formatOptions 88 "$f" --out "$WP/$n" >/dev/null 2>&1 || echo "skipped $f"
done
echo "wallpapers packed: $(ls "$WP" | wc -l | tr -d ' ')"
./gradlew --console=plain testBaseDebugUnitTest assembleRelease > out/build.log 2>&1 || { grep -E '^e: |error:|What went wrong|FAILED' -A3 out/build.log | head -60; exit 1; }
for f in base google; do cp "app/build/outputs/apk/$f/release/app-$f-release.apk" "out/epple-tv-$f-release.apk"; done
ls -l out/*.apk; grep -E "BUILD SUCCESSFUL" out/build.log
