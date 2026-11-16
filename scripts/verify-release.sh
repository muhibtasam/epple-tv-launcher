#!/usr/bin/env bash
# Release gate: stops (exit 1) unless the APK is Epple TV (com.eppletv.launcher) at the expected version, signed by
# exactly one signer, the permanent release certificate, and carries no personal wallpapers.
#   scripts/verify-release.sh <apk> <versionName> <versionCode>
set -eu
APK="$1"; WANT_NAME="$2"; WANT_CODE="$3"
PKG="com.eppletv.launcher"
PIN="2f8a183766e75fd0d2421a4969fc5654b5d5778552fd4062da9de02a225afa2b"   # SHA-256 of the v1.0.0 certificate
fail() { echo "RELEASE CHECK FAILED ($APK): $*" >&2; exit 1; }
BT="$(ls -d "${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"/build-tools/* | tail -1)"
certs="$("$BT/apksigner" verify --print-certs "$APK" 2>&1)" || fail "signature does not verify"
signers="$(grep -c 'certificate SHA-256 digest' <<<"$certs" || true)"
[ "$signers" = 1 ] || fail "expected 1 signer, found $signers"
sha="$(grep -m1 'certificate SHA-256 digest' <<<"$certs" | awk '{print $NF}')"
[ "$sha" = "$PIN" ] || fail "certificate SHA-256 $sha is not the permanent release certificate"
badge="$("$BT/aapt2" dump badging "$APK" | head -1)"
pkg="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badge")"
code="$(sed -n "s/.* versionCode='\([^']*\)'.*/\1/p" <<<"$badge")"
name="$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badge")"
[ "$pkg" = "$PKG" ] || fail "package $pkg, expected $PKG"
[ "$code" = "$WANT_CODE" ] || fail "versionCode $code, expected $WANT_CODE"
[ "$name" = "$WANT_NAME" ] || fail "versionName $name, expected $WANT_NAME"
if unzip -l "$APK" | grep -q 'assets/wallpapers/'; then fail "contains personal wallpapers (assets/wallpapers)"; fi
echo "release check passed: $APK ($pkg $name/$code, certificate $PIN)"
