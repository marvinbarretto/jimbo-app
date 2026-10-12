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
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIVE=false
[ "${1:-}" = "--live" ] && LIVE=true

cd "$ROOT/android"
op run --env-file="$ROOT/android/phone.env" -- ./gradlew assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

if ! $LIVE; then
  echo "built $APK — pass --live to install it on the connected phone."
  exit 0
fi
adb install -r "$APK"
echo "installed. Sync logs: adb logcat -s JimboSync"
