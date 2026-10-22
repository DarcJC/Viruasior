#!/usr/bin/env bash
# Build the ordinary-user desktop probe; no guest image modification needed.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_MEMORY_TEST_DIR:-"$repo/.pi/device-debug/venus-memory-test"}
libdir=${VENUS_GUEST_LIBDIR:?Set VENUS_GUEST_LIBDIR to the guest usr/lib directory}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME for Vulkan headers}
mkdir -p "$work/include"
# Do not add Bionic libc headers to the glibc cross compiler's include path.
cp -a "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/"{vulkan,vk_video} "$work/include/"
glslangValidator -V "$repo/guest/venus-probe/compute.comp" -o "$work/shader.spv"
xxd -i -n venus_guest_shader "$work/shader.spv" |
    sed -e 's/^unsigned char/_Alignas(4) static const unsigned char/' \
        -e 's/^unsigned int/static const unsigned int/' > "$work/shader.h"
aarch64-linux-gnu-gcc -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$work/include" -I"$work" "$repo/guest/venus-probe/init.c" \
    -Wl,-rpath-link,"$libdir" "$libdir/libvulkan.so.1" -o "$work/venus-memory-test"
printf 'Built %s/venus-memory-test\n' "$work"
