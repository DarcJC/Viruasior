#!/usr/bin/env bash
# Local build only; packaging/deployment requires a separately reviewed artifact pin.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}
renderer_work=${VENUS_WORK_DIR:?Set VENUS_WORK_DIR to the pinned renderer workspace}
[[ $(git -C "$work/rutabaga" rev-parse HEAD) == 1f181b0b3e4b821459f21fd9025cd4c408c4f9ec ]]
for item in crosvm rutabaga; do
    patch="$repo/native/venus-crosvm/$item-candidate.patch"
    if git -C "$work/$item" apply --check "$patch" 2>/dev/null; then
        git -C "$work/$item" apply "$patch"
    else
        git -C "$work/$item" apply --reverse --check "$patch"
    fi
done
patch="$repo/native/venus-crosvm/renderer-android-runtime.patch"
if git -C "$renderer_work/virglrenderer" apply --check "$patch" 2>/dev/null; then
    git -C "$renderer_work/virglrenderer" apply "$patch"
else
    git -C "$renderer_work/virglrenderer" apply --reverse --check "$patch"
fi
cp "$repo/native/venus-crosvm/venus_dmaheap.h" "$renderer_work/virglrenderer/src/venus/venus_dmaheap.h"
cp "$repo/native/venus-crosvm/venus_gralloc.h" "$repo/native/venus-crosvm/venus_gralloc_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_cache_sync.h" "$renderer_work/virglrenderer/src/venus/"
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_cache_sync_test.c" -o /tmp/viruaisor-venus-cache-test
/tmp/viruaisor-venus-cache-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_gralloc_policy_test.c" -o /tmp/viruaisor-venus-gralloc-policy-test
/tmp/viruaisor-venus-gralloc-policy-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_dmaheap_test.c" -o /tmp/viruaisor-venus-dmaheap-test
/tmp/viruaisor-venus-dmaheap-test
ninja -C "$renderer_work/build-android"
cp "$repo/native/venus-crosvm/venus_init_policy.rs" "$work/rutabaga/src/venus_init_policy.rs"
rustc --test "$repo/native/venus-crosvm/venus_init_policy.rs" -o /tmp/viruaisor-venus-policy-test
/tmp/viruaisor-venus-policy-test
VENUS_CROSVM_CANDIDATE=1 bash "$repo/scripts/build-venus-crosvm-baseline.sh"
