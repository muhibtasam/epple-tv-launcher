#!/usr/bin/env bash
# Emulator-only test helper. Hard-wired to emulator-5554 so it can never touch the real TV.
# Usage: scripts/emu.sh install | start | key <codes...> | shot <name> | gfx-reset | gfx
set -eu
cd "$(dirname "$0")/.."
export ANDROID_SERIAL=emulator-5554
P=com.eppletv.launcher
case "$1" in
  install) adb install -r out/epple-tv-google-release.apk | tail -1 ;;
  install-debug) ./gradlew -q assembleGoogleDebug && adb install -r app/build/outputs/apk/google/debug/app-google-debug.apk | tail -1 ;;
  start-many) adb shell am force-stop $P; adb shell am start -W -n $P/.home.HomeActivity --ei repeat "${2:-7}" | grep -E "Status|TotalTime" ;;
  start) adb shell am start -W -n $P/.home.HomeActivity | grep -E "Status|TotalTime" ;;
  key) shift; for k in "$@"; do adb shell input keyevent "$k"; done ;;
  hold) adb shell input keyevent --longpress "$2" ;;
  shot) mkdir -p out/shots; sleep "${3:-1.2}"; adb exec-out screencap -p > "out/shots/$2.png"
        sips -s format jpeg -s formatOptions 70 -Z 1280 "out/shots/$2.png" --out "out/shots/$2.jpg" >/dev/null ;;
  gfx-reset) adb shell dumpsys gfxinfo $P reset >/dev/null ;;
  gfx) adb shell dumpsys gfxinfo $P | grep -E "Total frames|Janky frames|50th|90th|95th|99th" ;;
  crash) adb logcat -d -s AndroidRuntime:E | tail -20 ;;
esac
