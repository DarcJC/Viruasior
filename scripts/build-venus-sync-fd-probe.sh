#!/usr/bin/env bash
# Build a guest-only GPU ordering regression; this does not install or run it.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
output="$repo/.pi/device-debug/venus-sync-fd-probe"
mkdir -p "$(dirname "$output")"
aarch64-linux-gnu-gcc --sysroot="$root" -O2 -Wall -Wextra -Werror \
    "$repo/native/venus-crosvm/venus_sync_fd_guest_probe.c" \
    -L"$root/usr/lib" -Wl,-rpath-link,"$root/usr/lib" \
    -lvulkan -lpthread -o "$output"
aarch64-linux-gnu-strip "$output"
sha256sum "$output"
