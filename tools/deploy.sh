#!/usr/bin/env bash
# Build, install, provision, start, and follow logs.
# Usage: tools/deploy.sh [device]   (default device: $TV or 192.168.29.59:5555)
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
DEV="${1:-${TV:-192.168.29.59:5555}}"
PKG=dev.better.airplay

echo "JDK: $JAVA_HOME"
echo "adb: $ADB"
"$ADB" connect "$DEV" >/dev/null || true
./gradlew assembleDebug
"$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk
tools/provision.sh "$DEV"
"$ADB" -s "$DEV" logcat -c
# The service is not exported (by design); launching the home screen starts it.
"$ADB" -s "$DEV" shell am start -n $PKG/.presentation.home.HomeActivity >/dev/null
echo "--- logs (Ctrl+C to stop) ---"
"$ADB" -s "$DEV" logcat -s 'BetterAirPlay:*' 'BetterAirPlay/native:*' 'BetterAirPlay/mdns:*' 'AndroidRuntime:E'
