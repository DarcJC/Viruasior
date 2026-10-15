#!/usr/bin/env bash
# Build-only SDK extraction. Does not install packages into Android or the guest.
set -euo pipefail
work=/tmp/viruaisor-venus-guest-sysroot
guest_lib=${VENUS_GUEST_LIB_DIR:-/tmp/viruaisor-venus-guest-build/root/lib}
mkdir -p "$work/root/usr/lib/aarch64-linux-gnu" "$work/root/lib/aarch64-linux-gnu"
deb="$work/libc6-dev-2.41-deb13u4.deb"
if [[ ! -f "$deb" ]]; then
    curl --fail --location --max-time 60 \
        'https://deb.debian.org/debian/pool/main/g/glibc/libc6-dev_2.41-12+deb13u4_arm64.deb' -o "$deb"
fi
[[ $(sha256sum "$deb" | cut -d ' ' -f1) == 71e373cb5e9da975b02fb4af555929cb1977560e2dd8245de4eb8a7b03677a08 ]]
dpkg-deb -x "$deb" "$work/root"
cp "$guest_lib/libc.so.6" "$work/root/lib/aarch64-linux-gnu/"
cp "$guest_lib/libm.so.6" "$work/root/lib/aarch64-linux-gnu/"
# The SDK's libm linker script also names libmvec. Extract only that regular
# build input from the same local read-only image; never install it on Android.
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image=${VENUS_GUEST_IMAGE:-"$repo/.pi/device-debug/image/root_part"}
info=$(debugfs -R 'stat /usr/lib/aarch64-linux-gnu/libmvec.so.1' "$image" 2>/dev/null)
[[ $info == *'Type: regular'* ]]
debugfs -R "dump /usr/lib/aarch64-linux-gnu/libmvec.so.1 $work/root/lib/aarch64-linux-gnu/libmvec.so.1" "$image" 2>/dev/null
aarch64-linux-gnu-readelf -h "$work/root/lib/aarch64-linux-gnu/libmvec.so.1" | grep -q AArch64
cp "$guest_lib/ld-linux-aarch64.so.1" "$work/root/lib/"
printf 'Guest ABI: glibc 2.41; Debian SDK: 2.41-12+deb13u4; no runtime replacement\n'
