#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source="$repo/.pi/device-debug/venus-crosvm-build/mesa-guest-reference"
work=${VENUS_GUEST_MESA_WORK_DIR:-/tmp/viruaisor-venus-guest-mesa}
guest_lib=${VENUS_GUEST_LIB_DIR:-/tmp/viruaisor-venus-guest-build/root/lib}
bash "$repo/scripts/prepare-venus-guest-sysroot.sh"
[[ $(git -C "$source" rev-parse HEAD) == 742a20f48c59e8649533c84c4d49dd95b403f5da ]]
patch="$repo/native/venus-crosvm/guest-mesa-cache-sync.patch"
if git -C "$source" apply --check "$patch" 2>/dev/null; then git -C "$source" apply "$patch";
else git -C "$source" apply --reverse --check "$patch"; fi
patch="$repo/native/venus-crosvm/guest-mesa-glibc-c23-threads.patch"
if git -C "$source" apply --check "$patch" 2>/dev/null; then git -C "$source" apply "$patch";
else git -C "$source" apply --reverse --check "$patch"; fi
patch="$repo/native/venus-crosvm/guest-wsi-drm-node-match.patch"
if git -C "$source" apply --check "$patch" 2>/dev/null; then git -C "$source" apply "$patch";
else git -C "$source" apply --reverse --check "$patch"; fi
patch="$repo/native/venus-crosvm/guest-wsi-ahb-surface-formats.patch"
if git -C "$source" apply --check "$patch" 2>/dev/null; then git -C "$source" apply "$patch";
else git -C "$source" apply --reverse --check "$patch"; fi
cp "$repo/guest/venus-probe/cache_policy.h" "$source/src/virtio/vulkan/viruaisor_cache_policy.h"
gcc -Wall -Wextra -Werror "$repo/guest/venus-probe/cache_policy_test.c" -o /tmp/viruaisor-venus-guest-cache-test
/tmp/viruaisor-venus-guest-cache-test
mkdir -p "$work/pkgconfig"
mkdir -p "$work/include"
if [[ ! -f "$source/subprojects/expat-2.5.0/lib/expat.h" ]]; then
    meson subprojects download --sourcedir "$source" expat
fi
cp "$source/subprojects/expat-2.5.0/lib/expat.h" "$source/subprojects/expat-2.5.0/lib/expat_external.h" "$work/include/"
cp /usr/include/zlib.h /usr/include/zconf.h /usr/include/zstd.h /usr/include/zstd_errors.h \
    /usr/include/xf86drm.h /usr/include/xf86drmMode.h "$work/include/"
for spec in "libdrm:2.4.124:libdrm.so.2:-I/usr/include/libdrm -I$work/include" "zlib:1.3.1:libz.so.1:-I$work/include" "libzstd:1.5.7:libzstd.so.1:-I$work/include" "expat:2.8.3:libexpat.so.1:-I$work/include"; do
    IFS=: read -r name version library cflags <<< "$spec"
    [[ -s "$guest_lib/$library" ]]
    aarch64-linux-gnu-readelf -h "$guest_lib/$library" | grep -q AArch64
    printf 'Name: %s\nDescription: Real guest AArch64 library\nVersion: %s\nLibs: -L%s -l:%s\nCflags: %s\n' \
        "$name" "$version" "$guest_lib" "$library" "$cflags" > "$work/pkgconfig/$name.pc"
done
gcc_include=$(aarch64-linux-gnu-gcc -print-file-name=include)
cxx_include="/usr/aarch64-linux-gnu/include/c++/$(aarch64-linux-gnu-g++ -dumpversion)"
[[ -d "$gcc_include" && -d "$cxx_include" ]]
sed -e "s|@GUEST_LIB@|$guest_lib|g" -e 's|@SYSROOT@|/tmp/viruaisor-venus-guest-sysroot/root|g' \
    -e "s|@GCC_INCLUDE@|$gcc_include|g" -e "s|@CXX_INCLUDE@|$cxx_include|g" \
    "$repo/native/venus-crosvm/guest-mesa-cross.ini.in" > "$work/cross.ini"
export PKG_CONFIG_LIBDIR="$work/pkgconfig"
export PKG_CONFIG_PATH=
setup_flags=()
if [[ -f "$work/build/build.ninja" ]]; then setup_flags=(--reconfigure --clearcache); fi
    meson setup "${setup_flags[@]}" "$work/build" "$source" --cross-file "$work/cross.ini" --buildtype=release \
        -Dplatforms=[] -Dgallium-drivers=[] -Dvulkan-drivers=virtio -Dvulkan-layers=[] \
        -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled \
        -Dopengl=false -Dllvm=disabled -Dxmlconfig=disabled -Dbuild-tests=false -Dtools=[] \
        -Dvideo-codecs=[] -Dlibunwind=disabled -Dvalgrind=disabled
ninja -j4 -C "$work/build" src/virtio/vulkan/libvulkan_virtio.so
aarch64-linux-gnu-readelf -d "$work/build/src/virtio/vulkan/libvulkan_virtio.so" | grep NEEDED
sha256sum "$work/build/src/virtio/vulkan/libvulkan_virtio.so"
