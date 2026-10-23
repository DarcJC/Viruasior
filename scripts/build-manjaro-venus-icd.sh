#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
work=/tmp/viruaisor-manjaro/venus-build
source="$repo/.pi/device-debug/venus-crosvm-build/mesa-guest-reference"
[[ $(git -C "$source" rev-parse HEAD) == 742a20f48c59e8649533c84c4d49dd95b403f5da ]]
wsi_sync_patch="$repo/native/venus-crosvm/guest-venus-wsi-sync-fd.patch"
if git -C "$source" apply --reverse --check "$wsi_sync_patch" 2>/dev/null; then
    git -C "$source" apply --reverse "$wsi_sync_patch"
fi
git -C "$source" apply --reverse --check "$repo/native/venus-crosvm/guest-mesa-cache-sync.patch"
cp "$repo/guest/venus-probe/cache_policy.h" "$source/src/virtio/vulkan/viruaisor_cache_policy.h"
node_patch="$repo/native/venus-crosvm/guest-wsi-drm-node-match.patch"
if git -C "$source" apply --check "$node_patch" 2>/dev/null; then
    git -C "$source" apply "$node_patch"
else
    git -C "$source" apply --reverse --check "$node_patch"
fi
# Normalize the two overlapping WSI patches before checking the older patch.
completed_patch="$repo/native/venus-crosvm/guest-wsi-ahb-completed-present.patch"
if git -C "$source" apply --reverse --check "$completed_patch" 2>/dev/null; then
    git -C "$source" apply --reverse "$completed_patch"
fi
format_patch="$repo/native/venus-crosvm/guest-wsi-ahb-surface-formats.patch"
if git -C "$source" apply --check "$format_patch" 2>/dev/null; then
    git -C "$source" apply "$format_patch"
else
    git -C "$source" apply --reverse --check "$format_patch"
fi
if ! git -C "$source" apply --reverse --check "$completed_patch" 2>/dev/null; then
    git -C "$source" apply --check "$completed_patch"
    git -C "$source" apply "$completed_patch"
fi
sync_patch="$repo/native/venus-crosvm/guest-venus-desktop-sync-fd.patch"
if ! git -C "$source" apply --reverse --check "$sync_patch" 2>/dev/null; then
    git -C "$source" apply --check "$sync_patch"
    git -C "$source" apply "$sync_patch"
fi
divisor_patch="$repo/native/venus-crosvm/guest-venus-khr-vertex-divisor.patch"
if ! git -C "$source" apply --reverse --check "$divisor_patch" 2>/dev/null; then
    git -C "$source" apply --check "$divisor_patch"
    git -C "$source" apply "$divisor_patch"
fi
present_patch="$repo/native/venus-crosvm/guest-venus-wsi-submit-fence.patch"
if ! git -C "$source" apply --reverse --check "$present_patch" 2>/dev/null; then
    git -C "$source" apply --check "$present_patch"
    git -C "$source" apply "$present_patch"
fi
python3 "$repo/scripts/test-venus-present-wait.py" "$source"
git -C "$source" apply --check "$wsi_sync_patch"
git -C "$source" apply "$wsi_sync_patch"
python3 "$repo/scripts/test-venus-wsi-sync-fd.py" "$source"
mkdir -p "$work"
gcc_include=$(aarch64-linux-gnu-gcc -print-file-name=include)
cxx_include="/usr/aarch64-linux-gnu/include/c++/$(aarch64-linux-gnu-g++ -dumpversion)"
cat > "$work/cross.ini" <<EOF
[binaries]
c = ['aarch64-linux-gnu-gcc', '--sysroot=$root', '-B$root/usr/lib/', '-nostdinc', '-isystem', '$gcc_include', '-isystem', '$root/usr/include']
cpp = ['aarch64-linux-gnu-g++', '--sysroot=$root', '-B$root/usr/lib/', '-nostdinc', '-nostdinc++', '-isystem', '$cxx_include', '-isystem', '$cxx_include/aarch64-linux-gnu', '-isystem', '$cxx_include/backward', '-isystem', '$gcc_include', '-isystem', '$root/usr/include']
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[properties]
needs_exe_wrapper = true
sys_root = '$root'
pkg_config_libdir = ['$root/usr/lib/pkgconfig', '$root/usr/share/pkgconfig']
[built-in options]
c_link_args = ['-L$root/usr/lib', '-Wl,-rpath-link,$root/usr/lib']
cpp_link_args = ['-L$root/usr/lib', '-Wl,-rpath-link,$root/usr/lib']
EOF
unset PKG_CONFIG_SYSROOT_DIR PKG_CONFIG_LIBDIR PKG_CONFIG_PATH
setup=()
[[ ! -f "$work/build/build.ninja" ]] || setup=(--reconfigure --clearcache)
meson setup "${setup[@]}" "$work/build" "$source" --cross-file "$work/cross.ini" --buildtype=release \
    -Dplatforms=x11,wayland -Dgallium-drivers=[] -Dvulkan-drivers=virtio -Dvulkan-layers=[] \
    -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled \
    -Dopengl=false -Dllvm=disabled -Dxmlconfig=disabled -Dbuild-tests=false -Dtools=[] \
    -Dvideo-codecs=[] -Dlibunwind=disabled -Dvalgrind=disabled
ninja -j4 -C "$work/build" src/virtio/vulkan/libvulkan_virtio.so
aarch64-linux-gnu-readelf --dyn-syms --wide "$work/build/src/virtio/vulkan/libvulkan_virtio.so" \
    | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tee "$work/glibc-requirements.txt"
! grep -Eq 'GLIBC_2\.(3[6-9]|[4-9][0-9])' "$work/glibc-requirements.txt"
sha256sum "$work/build/src/virtio/vulkan/libvulkan_virtio.so"
