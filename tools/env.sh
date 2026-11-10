#!/usr/bin/env bash
# Shared environment for tools/*.sh: finds a JDK and adb without requiring global setup.
# Override any of these by exporting them before running a script.

# JDK: prefer an existing JAVA_HOME, else Android Studio's bundled runtime (JBR).
has_java() { [ -n "$1" ] && { [ -x "$1/bin/java" ] || [ -x "$1/bin/java.exe" ]; }; }
if ! has_java "${JAVA_HOME:-}"; then
    unset JAVA_HOME
    for candidate in \
        "/c/Program Files/Android/Android Studio/jbr" \
        "$HOME/AppData/Local/Programs/Android Studio/jbr" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "/opt/android-studio/jbr"; do
        if has_java "$candidate"; then export JAVA_HOME="$candidate"; break; fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "No JDK found. Install Android Studio or set JAVA_HOME to a JDK 17+." >&2
    exit 1
fi

# Android SDK + adb
if [ -z "${ANDROID_HOME:-}" ]; then
    for candidate in "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        [ -d "$candidate" ] && { export ANDROID_HOME="$candidate"; break; }
    done
fi
if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then ADB=adb
    elif [ -x "${ANDROID_HOME:-}/platform-tools/adb.exe" ]; then ADB="$ANDROID_HOME/platform-tools/adb.exe"
    elif [ -x "${ANDROID_HOME:-}/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"
    else echo "adb not found. Install Android SDK platform-tools or set ADB." >&2; exit 1
    fi
fi
export ADB
export MSYS_NO_PATHCONV=1
