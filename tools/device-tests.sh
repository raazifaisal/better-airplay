#!/usr/bin/env bash
# Build the native test executable with the NDK, run it on the TV, report results.
# Usage: tools/device-tests.sh [device] [abi]   (default: $TV or 192.168.29.59:5555, armeabi-v7a)
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
DEV="${1:-${TV:-192.168.29.59:5555}}"
ABI="${2:-armeabi-v7a}"

# Native Windows tools (cmake.exe) need C:/... paths, not Git Bash's /c/... form.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
CMAKE_BIN="$ANDROID_HOME/cmake/3.31.6/bin"
EXE=""; [ -f "$CMAKE_BIN/cmake.exe" ] && EXE=".exe"
NDK="$(native "$ANDROID_HOME/ndk/29.0.14206865")"
OUT="build/device-tests/$ABI"

"$CMAKE_BIN/cmake$EXE" -G Ninja -S app/src/main/cpp -B "$OUT" \
    -DCMAKE_MAKE_PROGRAM="$(native "$CMAKE_BIN/ninja$EXE")" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBAP_DEVICE_TESTS=ON >/dev/null
"$CMAKE_BIN/cmake$EXE" --build "$OUT" --target bap_device_tests -j 8 >/dev/null

"$ADB" connect "$DEV" >/dev/null || true
"$ADB" -s "$DEV" push "$OUT/bap_device_tests" /data/local/tmp/bap_device_tests >/dev/null
"$ADB" -s "$DEV" shell chmod 755 /data/local/tmp/bap_device_tests
set +e
"$ADB" -s "$DEV" shell /data/local/tmp/bap_device_tests
rc=$?
set -e
"$ADB" -s "$DEV" shell rm -f /data/local/tmp/bap_device_tests
exit $rc
