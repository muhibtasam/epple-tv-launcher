#!/usr/bin/env bash
# Builds release APKs for both flavours and runs unit tests. Never contacts the TV.
#   scripts/build.sh            local build: your ./wallpapers are packed in (for your own TV)
#   scripts/build.sh --public   public release: no personal wallpapers, then scripts/verify-release.sh must pass
#   scripts/build.sh --debug    debug APKs (separate development key only; no tests, no release key)
# Release signing (see app/build.gradle.kts): EPPLE_RELEASE_* from the environment, else the permanent key in
# ~/Keys/epple-tv-release.jks with its password from the macOS Keychain item "epple-tv-release-keystore".
set -eu
cd "$(dirname "$0")/.."
PUBLIC=0; [ "${1:-}" = "--public" ] && PUBLIC=1
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
mkdir -p out
if [ "${1:-}" = "--debug" ]; then
  # Debug APKs (com.eppletv.launcher.debug), signed only with the separate development key ~/Keys/epple-tv-debug.jks
  # (password: Keychain item "epple-tv-debug-keystore"). The release key and its password are never read here.
  export EPPLE_DEBUG_STORE_FILE="${EPPLE_DEBUG_STORE_FILE:-$HOME/Keys/epple-tv-debug.jks}"
  export EPPLE_DEBUG_KEY_ALIAS="${EPPLE_DEBUG_KEY_ALIAS:-eppletv-debug}"
  if [ -z "${EPPLE_DEBUG_STORE_PASSWORD:-}" ]; then
    EPPLE_DEBUG_STORE_PASSWORD="$(security find-generic-password -s epple-tv-debug-keystore -w 2>/dev/null || true)"
  fi
  export EPPLE_DEBUG_STORE_PASSWORD
  export EPPLE_DEBUG_KEY_PASSWORD="${EPPLE_DEBUG_KEY_PASSWORD:-$EPPLE_DEBUG_STORE_PASSWORD}"
  ./gradlew --console=plain assembleDebug > out/build-debug.log 2>&1 || { grep -E '^e: |error:|What went wrong|FAILED|Debug signing' -A3 out/build-debug.log | head -60; exit 1; }
  for f in base google; do cp "app/build/outputs/apk/$f/debug/app-$f-debug.apk" "out/epple-tv-$f-debug.apk"; done
  ls -l out/*-debug.apk; exit 0
fi
export EPPLE_RELEASE_STORE_FILE="${EPPLE_RELEASE_STORE_FILE:-$HOME/Keys/epple-tv-release.jks}"
export EPPLE_RELEASE_KEY_ALIAS="${EPPLE_RELEASE_KEY_ALIAS:-eppletv-release}"
if [ -z "${EPPLE_RELEASE_STORE_PASSWORD:-}" ]; then
  EPPLE_RELEASE_STORE_PASSWORD="$(security find-generic-password -s epple-tv-release-keystore -w 2>/dev/null || true)"
fi
export EPPLE_RELEASE_STORE_PASSWORD
export EPPLE_RELEASE_KEY_PASSWORD="${EPPLE_RELEASE_KEY_PASSWORD:-$EPPLE_RELEASE_STORE_PASSWORD}"
WP=app/build/bundled-wallpapers/wallpapers
if [ $PUBLIC = 1 ]; then
  # The staging copy is cleared (your ./wallpapers folder is never touched) and Gradle is told not to pack it at all.
  rm -rf app/build/bundled-wallpapers
  GRADLE_ARGS="-PeppleBundleWallpapers=false"
  echo "public release: personal wallpapers excluded"
else
  # Your pictures in ./wallpapers go into the APK (assets/wallpapers), each resized to fit 1920x1080 as a JPEG so the
  # APK stays small; Epple TV takes in any it hasn't had yet after the install. The folder is rebuilt each time.
  rm -rf "$WP"; mkdir -p "$WP"
  for f in wallpapers/*; do
    case "$(echo "${f##*.}" | tr 'A-Z' 'a-z')" in jpg|jpeg|png|webp) ;; *) continue ;; esac
    n="$(basename "${f%.*}").jpg"
    sips -Z 1920 -s format jpeg -s formatOptions 88 "$f" --out "$WP/$n" >/dev/null 2>&1 || echo "skipped $f"
  done
  echo "wallpapers packed: $(ls "$WP" | wc -l | tr -d ' ')"
  GRADLE_ARGS=""
fi
./gradlew --console=plain $GRADLE_ARGS testBaseDebugUnitTest assembleRelease > out/build.log 2>&1 || { grep -E '^e: |error:|What went wrong|FAILED|Release signing' -A3 out/build.log | head -60; exit 1; }
for f in base google; do cp "app/build/outputs/apk/$f/release/app-$f-release.apk" "out/epple-tv-$f-release.apk"; done
if [ $PUBLIC = 1 ]; then
  NAME="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
  CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
  for f in base google; do scripts/verify-release.sh "out/epple-tv-$f-release.apk" "$NAME" "$CODE"; done
fi
ls -l out/*.apk; grep -E "BUILD SUCCESSFUL" out/build.log
