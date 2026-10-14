#!/usr/bin/env bash
# Stop the single-VM display experiment through the app and verify scoped resources disappear.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-/c/Tools/Android/platform-tools/adb.exe}
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[[ $# -eq 1 ]] || { echo 'Usage: bash scripts/test-root-display-cleanup.sh .pi/device-debug/<capture>' >&2; exit 2; }
OUT=$(realpath -m "$1")
[[ "$OUT" == "$repo/.pi/device-debug/"* && -f "$OUT/policy-before.txt" ]] || {
    echo 'FAIL: expected an existing experiment capture beneath .pi/device-debug/' >&2; exit 2;
}
START=$($ADB shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
$ADB shell am start -W -f 0x20000000 -n pro.darc.viruaisor/.MainActivity \
    -a pro.darc.viruaisor.action.DEBUG_STOP

capture() {
    $ADB shell ps -AZ > "$OUT/processes-after-stop.txt"
    $ADB shell dumpsys SurfaceFlinger --list > "$OUT/layers-after-stop.txt"
    $ADB logcat -d -T "$START" -s ViruaisorState ViruaisorRootDisplay > "$OUT/cleanup-progress.txt"
}
for ((i=0;i<30;i++)); do
    capture
    if ! grep -E 'crosvm|pro\.darc\.viruaisor:root:' "$OUT/processes-after-stop.txt" >/dev/null && \
       ! grep -F 'ViruaisorRootDisplay' "$OUT/layers-after-stop.txt" >/dev/null && \
       grep -q 'status=Stopped' "$OUT/cleanup-progress.txt"; then
        $ADB shell 'getenforce; settings get global hidden_api_policy; settings get global hidden_api_blacklist_exemptions' \
            > "$OUT/policy-after-stop.txt"
        cmp -s "$OUT/policy-before.txt" "$OUT/policy-after-stop.txt" || {
            echo "FAIL: policy snapshot changed; inspect $OUT" >&2; exit 1;
        }
        if grep -Eq 'operation exceeded|detach failed' "$OUT/cleanup-progress.txt"; then
            echo "FAIL: resources exited but cleanup reported an error; inspect $OUT/cleanup-progress.txt" >&2
            exit 1
        fi
        echo 'PASS: app reports Stopped; crosvm, root helper and display child absent; policy snapshot unchanged'
        exit 0
    fi
    sleep 1
done
echo "FAIL: stop/cleanup incomplete after 30 seconds; inspect $OUT" >&2
exit 1
