#!/usr/bin/env bash
set -euo pipefail

ADB=${ADB:-/c/Tools/Android/platform-tools/adb.exe}
TIMEOUT=${1:-180}
[[ "$TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "Timeout must be a positive integer" >&2; exit 2; }
# Avoid Git Bash's conversion of remote /data and /sdcard paths to Windows paths.
export MSYS_NO_PATHCONV=1
OUT=".pi/device-debug/root-direct-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
if $ADB shell ps -A | grep -q 'crosvm'; then
    echo "FAIL: an existing crosvm may own the global display; stop it before this single-VM test" >&2
    exit 1
fi
# A sleeping/locked activity has no Surface: reject this prerequisite before starting a VM.
# Read only the KeyguardServiceDelegate block; fail closed on unknown/ambiguous dump formats.
$ADB shell dumpsys window policy | tr -d '\r' > "$OUT/window-before.txt"
if ! awk '
    /^[ \t]*KeyguardServiceDelegate[ \t]*$/ {
        sections++; match($0, /[^ \t]/); indent = RSTART - 1; in_guard = 1; next
    }
    in_guard && /[^ \t]/ {
        match($0, /[^ \t]/)
        if (RSTART - 1 <= indent) { in_guard = 0; next }
        value = $0; sub(/^.*=/, "", value); gsub(/[ \t]/, "", value)
        if ($0 ~ /^[ \t]*showing=/) { showing = value; showing_count++ }
        if ($0 ~ /^[ \t]*screenState=/) { screen = value; screen_count++ }
        if ($0 ~ /^[ \t]*interactiveState=/) { interactive = value; interactive_count++ }
    }
    END {
        exit !(sections == 1 && showing_count == 1 && showing == "false" &&
               screen_count == 1 && screen == "SCREEN_STATE_ON" &&
               interactive_count == 1 && interactive == "INTERACTIVE_STATE_AWAKE")
    }
' "$OUT/window-before.txt"; then
    echo "FAIL: screen is off/locked or state is unverified; unlock phone and inspect $OUT/window-before.txt" >&2
    exit 1
fi
START=$($ADB shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
$ADB shell 'getenforce; settings get global hidden_api_policy; settings get global hidden_api_blacklist_exemptions' > "$OUT/policy-before.txt"

# A prepared app with root, AVF grants and an imported bundle is required. Never modify policy.
$ADB shell am start -W -f 0x20000000 -n pro.darc.viruaisor/.MainActivity \
    -a pro.darc.viruaisor.action.DEBUG_ROOT_DISPLAY
$ADB shell am start -W -f 0x20000000 -n pro.darc.viruaisor/.MainActivity \
    -a pro.darc.viruaisor.action.DEBUG_START

capture() {
    $ADB logcat -d -T "$START" -b all > "$OUT/logcat.txt"
    $ADB shell 'getenforce; settings get global hidden_api_policy; settings get global hidden_api_blacklist_exemptions' > "$OUT/policy-after.txt"
    $ADB shell 'ps -AZ | grep -E "viruaisor|crosvm|virtualization"' > "$OUT/processes.txt" || true
    $ADB exec-out screencap -p > "$OUT/screen.png"
}

attached=false
desktop=false
deadline=$((SECONDS + TIMEOUT))
while (( SECONDS < deadline )); do
    $ADB logcat -d -T "$START" -s ViruaisorRootDisplay ViruaisorState > "$OUT/progress.txt"
    if grep -Eq 'bootstrap failed|binding timed out|attach failed|operation exceeded|status=Crashed' "$OUT/progress.txt"; then
        capture
        echo "FAIL: root display/VM error. Evidence: $OUT" >&2
        exit 1
    fi
    if grep -q 'Root Direct Surface attached' "$OUT/progress.txt"; then attached=true; fi
    if grep -q 'Guest agent ready; desktop requested' "$OUT/progress.txt"; then desktop=true; fi
    if $attached && $desktop; then
        capture
        if ! cmp -s "$OUT/policy-before.txt" "$OUT/policy-after.txt"; then
            echo "FAIL: policy snapshot changed; inspect $OUT (script made no policy changes)" >&2
            exit 1
        fi
        grep -q 'Enforcing' "$OUT/policy-after.txt" || { echo "FAIL: SELinux not Enforcing" >&2; exit 1; }
        echo "PASS: Surface attached and guest desktop requested; policy snapshot unchanged."
        echo "NOT YET PROVEN: guest frames visible. Inspect $OUT/screen.png and compositor logs."
        echo "Evidence: $OUT"
        exit 0
    fi
    sleep 1
done
capture
echo "FAIL: timeout; Surface attached=$attached, desktop requested=$desktop. Evidence: $OUT" >&2
exit 1
