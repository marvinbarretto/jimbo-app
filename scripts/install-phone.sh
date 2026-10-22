#!/usr/bin/env bash
# Build jimbo-app with the phone's API key from 1Password, and install it.
#
#   scripts/install-phone.sh          # build only (dry run for the phone)
#   scripts/install-phone.sh --live   # build, then adb install on the connected phone
#
# The key's only home is 1Password (op://Fleet/phone/jimbo_key, ADR-0106).
# `op run` hands it to gradle as JIMBO_API_KEY for this build and nowhere else;
# build.gradle bakes it into BuildConfig. local.properties keeps the URL and
# the places, never the key.
#
# The build output holds the key in plain text (BuildConfig.java, the dex, the
# APK), where any session on this machine could read it. So app/build is
# removed on every exit, pass or fail: the installed APK on the phone is the
# key's only copy outside 1Password. A dry run proves the build, then cleans.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIVE=false
[ "${1:-}" = "--live" ] && LIVE=true

# Regenerate the bundled capacitor.config.json from capacitor.config.ts, so
# settings such as loggingBehavior reach the APK.
(cd "$ROOT" && npx cap copy android)
cd "$ROOT/android"
trap 'rm -rf "$ROOT/android/app/build"' EXIT
op run --env-file="$ROOT/android/phone.env" -- ./gradlew assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

if ! $LIVE; then
  echo "built $APK, now removed with the rest of app/build — pass --live to install it on the connected phone."
  exit 0
fi
adb install -r "$APK"
echo "installed; app/build removed. Sync logs: adb logcat -s JimboSync"
