#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/c/Tools/Android/platform-tools/adb.exe}"
PACKAGE=pro.darc.viruaisor
ACTIVITY="$PACKAGE/.MainActivity"
EXPECTED_STATUS="${1:-VM stopped}"

"$ADB" get-state >/dev/null
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" logcat -c
"$ADB" shell am start -W -n "$ACTIVITY" >/dev/null

ui=""
for _ in {1..20}; do
    ui="$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null)"
    if grep -Fq 'text="Available (capabilities: 0x3)"' <<<"$ui" &&
        grep -Fq "text=\"$EXPECTED_STATUS\"" <<<"$ui"; then
        break
    fi
    sleep 0.25
done
if ! grep -Fq 'text="Available (capabilities: 0x3)"' <<<"$ui"; then
    echo "FAIL: AVF capability check is not available" >&2
    exit 1
fi
if ! grep -Fq "text=\"$EXPECTED_STATUS\"" <<<"$ui"; then
    echo "FAIL: expected VM status '$EXPECTED_STATUS'" >&2
    exit 1
fi
if ! "$ADB" shell pidof "$PACKAGE" >/dev/null; then
    echo "FAIL: app process is not running" >&2
    exit 1
fi

echo "PASS: AVF available; status=$EXPECTED_STATUS"
