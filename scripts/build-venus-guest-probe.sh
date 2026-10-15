#!/usr/bin/env bash
# Read-only extraction from a LOCAL stopped guest-image copy. No mount/chroot/journal replay.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
image=${VENUS_GUEST_IMAGE:-"$repo/.pi/device-debug/image/root_part"}
work=${VENUS_GUEST_WORK_DIR:-/tmp/viruaisor-venus-guest-build}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME for the host glslc compiler}
icd=${VENUS_GUEST_ICD:?Set VENUS_GUEST_ICD to the paired explicit-sync guest Mesa build}
root="$work/root"
mkdir -p "$root/lib" "$root/etc" "$root/dev" "$root/proc" "$root/sys" "$root/tmp" "$repo/app/src/main/assets/venus"
gcc -std=c11 -Wall -Wextra -Werror "$repo/guest/venus-probe/readback_test.c" -o "$work/readback-test"
"$work/readback-test"
# Resolve only library basenames from ELF DT_NEEDED. Never follow a source-image
# symlink outside the conventional library tree or execute an extracted binary.
extract_library() {
    local name=$1 target info link depth
    [[ $name =~ ^[A-Za-z0-9_.+-]+$ ]] || return 1
    [[ -f "$root/lib/$name" ]] && return 0
    target="/usr/lib/aarch64-linux-gnu/$name"
    for depth in {1..8}; do
        info=$(debugfs -R "stat $target" "$image" 2>/dev/null)
        if [[ $info == *'Type: symlink'* ]]; then
            link=$(printf '%s\n' "$info" | sed -n 's/^Fast link dest: "\([A-Za-z0-9_.+-]*\)"$/\1/p')
            [[ -n $link ]] || { echo "Unsupported library symlink $target" >&2; return 1; }
            target="/usr/lib/aarch64-linux-gnu/$link"
        else
            [[ $info == *'Type: regular'* ]] || { echo "Missing regular library $target" >&2; return 1; }
            debugfs -R "dump $target $root/lib/$name" "$image" 2>/dev/null
            [[ -s "$root/lib/$name" ]] || return 1
            aarch64-linux-gnu-readelf -h "$root/lib/$name" | grep -q AArch64
            break
        fi
    done
    [[ -s "$root/lib/$name" ]] || return 1
    local needed
    while read -r needed; do extract_library "$needed"; done < <(
        aarch64-linux-gnu-readelf -d "$root/lib/$name" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
}
extract_library libvulkan.so.1
extract_library libvulkan_virtio.so
cp "$icd" "$root/lib/libvulkan_virtio.so"
aarch64-linux-gnu-strip --strip-unneeded "$root/lib/libvulkan_virtio.so"
while read -r needed; do extract_library "$needed"; done < <(
    aarch64-linux-gnu-readelf -d "$root/lib/libvulkan_virtio.so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
[[ -L "$root/lib/aarch64-linux-gnu" ]] || ln -s . "$root/lib/aarch64-linux-gnu"
# ELF PT_INTERP is executed by the guest kernel, not merely mmap'ed as a library.
chmod 0755 "$root/lib/ld-linux-aarch64.so.1"
[[ -c "$root/dev/console" ]] || mknod -m 0600 "$root/dev/console" c 5 1
[[ -c "$root/dev/null" ]] || mknod -m 0666 "$root/dev/null" c 1 3
printf '%s\n' '{"file_format_version":"1.0.0","ICD":{"library_path":"/lib/libvulkan_virtio.so","api_version":"1.3.305"}}' > "$root/etc/venus_icd.json"
"$ndk/shader-tools/linux-x86_64/glslc" -fshader-stage=compute "$repo/guest/venus-probe/compute.comp" -o "$work/shader.spv"
xxd -i -n venus_guest_shader "$work/shader.spv" | sed 's/^unsigned char/const unsigned char __attribute__((aligned(4)))/' > "$work/shader.h"
aarch64-linux-gnu-gcc -std=c11 -O2 -Wall -Wextra -Werror -I"$work" "$repo/guest/venus-probe/init.c" \
    -L"$root/lib" -Wl,-rpath-link,"$root/lib" -Wl,--dynamic-linker=/lib/ld-linux-aarch64.so.1 \
    -Wl,-rpath,/lib -l:libvulkan.so.1 -o "$root/init"
while read -r needed; do extract_library "$needed"; done < <(
    aarch64-linux-gnu-readelf -d "$root/init" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
chmod 0755 "$root/init"
test -x "$root/init" && test -x "$root/lib/ld-linux-aarch64.so.1" && test -c "$root/dev/console"
cd "$root"
find . -print0 | LC_ALL=C sort -z | cpio --null -o -H newc 2> "$work/cpio.log" | gzip -n > "$repo/app/src/main/assets/venus/guest-probe.cpio.gz"
sha256sum "$repo/app/src/main/assets/venus/guest-probe.cpio.gz" "$root/init" "$root/lib/libvulkan_virtio.so"
