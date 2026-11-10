#!/usr/bin/env bash
# One-time "system-like" setup over ADB (no root). Safe to re-run.
# Usage: tools/provision.sh [device]   (default device: $TV or 192.168.29.59:5555)
set -euo pipefail
source "$(dirname "$0")/env.sh"
DEV="${1:-${TV:-192.168.29.59:5555}}"
PKG=dev.better.airplay
a() { "$ADB" -s "$DEV" shell "$@"; }

a appops set $PKG SYSTEM_ALERT_WINDOW allow          # start player from background + overlay fallback
a cmd appops set $PKG RUN_IN_BACKGROUND allow
a cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
a dumpsys deviceidle whitelist +$PKG >/dev/null      # exempt from Doze
a am set-standby-bucket $PKG active
SDK=$(a getprop ro.build.version.sdk | tr -d '\r')
if [ "$SDK" -ge 33 ]; then a pm grant $PKG android.permission.POST_NOTIFICATIONS || true; fi
echo "provisioned $PKG on $DEV (API $SDK)"
