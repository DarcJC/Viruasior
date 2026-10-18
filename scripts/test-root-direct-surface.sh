#!/usr/bin/env bash
set -euo pipefail

ADB=${ADB:-/c/Tools/Android/platform-tools/adb.exe}
NODE=${NODE:-node}
TIMEOUT=${1:-180}
[[ "$TIMEOUT" =~ ^[1-9][0-9]*$ ]] || { echo "Timeout must be a positive integer" >&2; exit 2; }
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# Avoid Git Bash's conversion of remote /data and /sdcard paths to Windows paths.
export MSYS_NO_PATHCONV=1
OUT=".pi/device-debug/root-direct-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd -P)
if [[ "$(uname -s)" == MINGW* ]]; then OUT=$(cygpath -m "$OUT"); fi
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
$ADB logcat -v threadtime -T "$START" -s ViruaisorRootDisplay ViruaisorState ViruaisorVenusDisplay ViruaisorVenusRenderer ViruaisorVenusDesktop ViruaisorDisplay virtmgr crosvm > "$OUT/logcat-live.txt" &
recorder=$!
trap 'kill "$recorder" 2>/dev/null || true; wait "$recorder" 2>/dev/null || true' EXIT

# A prepared app with root, AVF grants and an imported bundle is required. Never modify policy.
# Replace a stale task base intent so a process restart cannot replay an earlier
# one-shot debug import while this run is starting.
$ADB shell am start -W -f 0x10008000 -n pro.darc.viruaisor/.MainActivity \
    -a pro.darc.viruaisor.action.DEBUG_ROOT_DISPLAY
$ADB shell am start -W -f 0x20000000 -n pro.darc.viruaisor/.MainActivity \
    -a pro.darc.viruaisor.action.DEBUG_START

capture() {
    cp "$OUT/logcat-live.txt" "$OUT/logcat.txt"
    $ADB shell 'getenforce; settings get global hidden_api_policy; settings get global hidden_api_blacklist_exemptions' > "$OUT/policy-after.txt"
    $ADB shell 'ps -AZ | grep -E "viruaisor|crosvm|virtualization"' > "$OUT/processes.txt" || true
    $ADB shell dumpsys SurfaceFlinger > "$OUT/surfaceflinger.txt"
    $ADB exec-out screencap -p > "$OUT/screen.png"
    $ADB shell uiautomator dump /sdcard/Android/data/pro.darc.viruaisor/files/viruaisor-frame.xml >/dev/null
    $ADB pull /sdcard/Android/data/pro.darc.viruaisor/files/viruaisor-frame.xml "$OUT/frame-ui.xml" >/dev/null
}

attached=false
desktop=false
gpu=false
plasma=false
frame=false
synced=false
nonblack=false
fatal_pattern='bootstrap failed|binding timed out|attach failed|operation exceeded|status=Crashed|GPU_DRAW_READBACK_FAIL|DIRECT_SCANOUT_FALLBACK|cpuCopies=[1-9]|VIRUAISOR_DISPLAY_SYNC_(RING|DEVICE)_FAILED|Plasma session did not confirm KWin OpenGL|MESA: error: ZINK: .* failed|AddScreen/ScreenInit failed|failed to bind extensions|couldn.t get display device|glamor initialization failed'
deadline=$((SECONDS + TIMEOUT))
while (( SECONDS < deadline )); do
    cp "$OUT/logcat-live.txt" "$OUT/progress.txt"
    if grep -Eq "$fatal_pattern" "$OUT/progress.txt"; then
        capture
        echo "FAIL: root display/VM error. Evidence: $OUT" >&2
        exit 1
    fi
    if grep -q 'Root Direct Surface attached' "$OUT/progress.txt"; then attached=true; fi
    if grep -q 'Guest agent ready; desktop requested' "$OUT/progress.txt"; then desktop=true; fi
    if grep -q 'GPU_DRAW_READBACK_PASS' "$OUT/progress.txt"; then gpu=true; fi
    if grep -q 'VIRUAISOR_PLASMA_HARDWARE_READY=true' "$OUT/progress.txt"; then
        plasma=true
        # Requires the Venus draw preflight and active KWin OpenGL.
        desktop=true
    fi
    if grep -Eq 'FRAME_POSTED count=[1-9][0-9]*' "$OUT/progress.txt"; then frame=true; fi
    if awk '
        /VIRUAISOR_PLASMA_HARDWARE_READY=true/ { ready=1 }
        ready && /VIRUAISOR_DISPLAY_SYNC_READY/ && /rings=[1-9][0-9]*/ && $0 !~ /devices=/ { sync=1 }
        ready && sync && /FRAME_POSTED .*path=root-surface-control cpuCopies=0/ { found=1; exit }
        END { exit !found }
    ' "$OUT/progress.txt"; then
        synced=true
        nonblack=true
    fi
    if $attached && $desktop && $gpu && $plasma && $frame && $synced && $nonblack; then
        capture
        if ! cmp -s "$OUT/policy-before.txt" "$OUT/policy-after.txt"; then
            echo "FAIL: policy snapshot changed; inspect $OUT (script made no policy changes)" >&2
            exit 1
        fi
        grep -q 'Enforcing' "$OUT/policy-after.txt" || { echo "FAIL: SELinux not Enforcing" >&2; exit 1; }
        token=$(sed -n 's/.*DESKTOP_LEASE_READY token=\([0-9a-f]\{32\}\).*/\1/p' "$OUT/logcat.txt" | tail -1)
        if ! (cd "$REPO" && "$NODE" scripts/assert-surface-pixels.cjs "$OUT/screen.png" "$OUT/frame-ui.xml" "$token" "$OUT/surfaceflinger.txt") > "$OUT/frame-report.json"; then
            # KWin can become active before Plasma finishes drawing its wallpaper.
            # Keep the original bounded deadline and require real pixels on a later frame.
            sleep 1
            continue
        fi
        if ! (cd "$REPO" && "$NODE" scripts/assert-manjaro-desktop.cjs - "$OUT/frame-report.json") \
            < "$OUT/logcat.txt" > "$OUT/manjaro-desktop-report.json"; then
            cat "$OUT/manjaro-desktop-report.json" >&2
            echo "FAIL: strict Manjaro/Venus desktop evidence did not pass. Evidence: $OUT" >&2
            exit 1
        fi
        echo "PASS: KWin OpenGL, direct AHB, renderer synchronization and a post-ready non-black Direct Surface frame verified; policy snapshot unchanged."
        echo "VISUAL CHECK REQUIRED: verify physical panel colours too; screenshots cannot detect HWC green output."
        echo "Evidence: $OUT"
        exit 0
    fi
    sleep 1
done
capture
echo "FAIL: timeout; Surface attached=$attached, desktop requested=$desktop, GPU readback=$gpu, Plasma hardware=$plasma, frame posted=$frame, renderer synchronized=$synced, post-ready non-black=$nonblack. Evidence: $OUT" >&2
exit 1
