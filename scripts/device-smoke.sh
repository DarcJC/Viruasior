#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/c/Tools/Android/platform-tools/adb.exe}"
source "$(dirname "${BASH_SOURCE[0]}")/application-id.sh"
resolve_application_id "$@"
(( ${#APPLICATION_ARGS[@]} <= 1 )) || { echo 'Usage: device-smoke.sh [expected-status] [--package application.id]' >&2; exit 2; }
ACTIVITY="$PACKAGE/pro.darc.viruaisor.MainActivity"
EXPECTED_STATUS="${APPLICATION_ARGS[0]:-VM stopped}"
case "$EXPECTED_STATUS" in
    'VM stopped') STATUS_MARKER='Stopped' ;;
    'VM starting') STATUS_MARKER='Starting' ;;
    'VM running') STATUS_MARKER='Running' ;;
    'Guest agent ready') STATUS_MARKER='GuestAgentReady' ;;
    'VM stopping') STATUS_MARKER='Stopping' ;;
    *) STATUS_MARKER="$EXPECTED_STATUS" ;;
esac

"$ADB" get-state >/dev/null
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" logcat -c
"$ADB" shell am start -W -n "$ACTIVITY" >/dev/null

ui=""
for _ in {1..20}; do
    ui="$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null)"
    if grep -Fq 'AVF_AVAILABLE' <<<"$ui" &&
        grep -Fq "VM_STATUS:$STATUS_MARKER" <<<"$ui"; then
        break
    fi
    sleep 0.25
done
if ! grep -Fq 'AVF_AVAILABLE' <<<"$ui"; then
    echo "FAIL: AVF capability check is not available" >&2
    exit 1
fi
if ! grep -Fq "VM_STATUS:$STATUS_MARKER" <<<"$ui"; then
    echo "FAIL: expected VM status '$EXPECTED_STATUS'" >&2
    exit 1
fi
if ! "$ADB" shell pidof "$PACKAGE:venus_vm" >/dev/null; then
    echo "FAIL: app process is not running" >&2
    exit 1
fi

echo "PASS: AVF available; status=$EXPECTED_STATUS"
