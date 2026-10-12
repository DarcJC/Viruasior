#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/c/Tools/Android/platform-tools/adb.exe}"
PACKAGE=pro.darc.viruaisor
ACTIVITY="$PACKAGE/.MainActivity"
SERVICE=android.system.virtualizationservice

"$ADB" get-state >/dev/null
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PACKAGE"
"$ADB" shell logcat -b all -c
"$ADB" shell am start -n "$ACTIVITY" >/dev/null

logs=""
for _ in {1..20}; do
    logs=$("$ADB" shell logcat -b all -d 2>/dev/null | grep -E "ViruaisorDisplay|avc:.*(name|service)=${SERVICE}" || true)
    if grep -Fq "Could not obtain the AVF display service" <<<"$logs"; then
        printf '%s\n' "$logs" | tail -n 12
        if grep -Eq "avc:.*denied.*\{ find \}" <<<"$logs" &&
            grep -Eq "scontext=u:r:untrusted_app" <<<"$logs" &&
            grep -Eq "tclass=service_manager" <<<"$logs" &&
            grep -Eq "(name|service)=${SERVICE}" <<<"$logs"; then
            echo "RED: Direct Surface lookup is blocked by SELinux service_manager find"
        else
            echo "RED: Direct Surface binder lookup failed (matching AVC was audit-suppressed)"
        fi
        exit 1
    fi
    sleep 0.25
done

if grep -Fq "Direct AVF surface attached" <<<"$logs"; then
    echo "GREEN: Direct Surface binder lookup and attachment succeeded"
    exit 0
fi

printf '%s\n' "$logs" | tail -n 12
echo "INCONCLUSIVE: neither a matching SELinux denial nor a successful attachment was observed" >&2
exit 2
