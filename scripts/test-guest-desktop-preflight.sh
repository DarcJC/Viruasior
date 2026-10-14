#!/usr/bin/env bash
# Exercise the real unit's GPU preflight against guest module-state fixtures. No root required.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

mapfile -t preflights < <(awk '/^ExecStartPre=/ { sub(/^ExecStartPre=/, ""); print }' "$repo/docs/guest/avf-desktop@.service")
[[ ${#preflights[@]} -eq 1 ]] || { echo 'FAIL: expected exactly one GPU preflight' >&2; exit 1; }
# Only replace the platform paths; execute the actual command, including its shell/quoting.
preflight=${preflights[0]//\/sys\/module/$fixture/modules}
preflight=${preflight//\/sbin\/modprobe/$fixture/modprobe}
export MODPROBE_CALLS="$fixture/calls"
cat > "$fixture/modprobe" <<'SH'
#!/bin/sh
set -eu
printf '%s\n' "$*" >> "$MODPROBE_CALLS"
[ "$#" -eq 1 ] && [ "$1" = virtio_gpu ] || exit 99
exit "$MODPROBE_RESULT"
SH
chmod +x "$fixture/modprobe"

failures=0
cases=0
check() {
    local name=$1 loaded=$2 loader_result=$3 expected_status=$4 expected_calls=$5
    rm -rf "$fixture/modules"
    rm -f "$MODPROBE_CALLS"
    if [[ $loaded == yes ]]; then mkdir -p "$fixture/modules/virtio_gpu"; fi
    export MODPROBE_RESULT=$loader_result
    local actual_status=0 actual_calls=0
    bash -c "$preflight" || actual_status=$?
    if [[ -f $MODPROBE_CALLS ]]; then actual_calls=$(wc -l < "$MODPROBE_CALLS"); fi
    cases=$((cases + 1))
    if [[ $actual_status -eq $expected_status && $actual_calls -eq $expected_calls ]]; then
        echo "PASS: $name"
    else
        echo "FAIL: $name; exit=$actual_status (expected $expected_status), modprobe calls=$actual_calls (expected $expected_calls)" >&2
        failures=$((failures + 1))
    fi
}

check 'available driver survives missing module files (device regression)' yes 1 0 0
check 'available driver never reloads even with module files' yes 0 0 0
check 'absent driver is explicitly loaded on desktop request' no 0 0 1
check 'absent driver and failed loader still fail closed' no 1 1 1

echo "$cases cases, $failures failures"
[[ $failures -eq 0 ]]
