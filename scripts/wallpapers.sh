#!/usr/bin/env bash
# Copies your own pictures from ./wallpapers (never committed) to the TV. Epple TV takes them in, resized and
# re-encoded into its private storage, the next time Home opens; the copies on the TV's shared storage are then removed.
# Usage: scripts/wallpapers.sh            (device from $ADB_DEVICE, default 192.168.1.69:5555)
set -euo pipefail
cd "$(dirname "$0")/.."
DEV="${ADB_DEVICE:-192.168.1.69:5555}"
DIR=/sdcard/Android/data/com.eppletv.launcher/files/wallpapers
shopt -s nullglob nocaseglob
files=(wallpapers/*.jpg wallpapers/*.jpeg wallpapers/*.png wallpapers/*.webp)
if [ ${#files[@]} -eq 0 ]; then echo "Put JPG, PNG or WEBP pictures in ./wallpapers first."; exit 1; fi
adb -s "$DEV" shell mkdir -p "$DIR"
for f in "${files[@]}"; do adb -s "$DEV" push "$f" "$DIR/" >/dev/null && echo "sent $(basename "$f")"; done
echo "Done. Press Home on the remote (or reopen Epple TV) to see them."
