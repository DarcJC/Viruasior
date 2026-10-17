#!/usr/bin/env bash
# Build-only comparison against the distro's Mesa 24 Zink. No guest/device deployment.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source="$repo/.pi/device-debug/venus-crosvm-build/mesa-guest-reference"
work=/tmp/viruaisor-manjaro/zink25-build
cross=/tmp/viruaisor-manjaro/venus-build/cross.ini
[[ $(git -C "$source" rev-parse HEAD) == 742a20f48c59e8649533c84c4d49dd95b403f5da ]]
[[ -f "$cross" ]]
patch="$repo/native/venus-crosvm/zink-venus-upload-coherence.patch"
git -C "$source" apply --reverse --check "$patch" 2>/dev/null || git -C "$source" apply "$patch"
unset PKG_CONFIG_SYSROOT_DIR PKG_CONFIG_LIBDIR PKG_CONFIG_PATH
setup=()
[[ ! -f "$work/build.ninja" ]] || setup=(--reconfigure --clearcache)
meson setup "${setup[@]}" "$work" "$source" --cross-file "$cross" --buildtype=release \
    --prefix=/opt/viruaisor/zink25 -Dplatforms=x11,wayland -Dgallium-drivers=zink \
    -Dvulkan-drivers=[] -Dvulkan-layers=[] -Dglx=dri -Degl=enabled -Dgbm=enabled \
    -Dgles1=disabled -Dgles2=enabled -Dopengl=true -Dglvnd=enabled -Dllvm=disabled \
    -Dxmlconfig=disabled -Dbuild-tests=false -Dtools=[] -Dvideo-codecs=[] \
    -Dlibunwind=disabled -Dvalgrind=disabled
ninja -j4 -C "$work"
DESTDIR="$work/staged" meson install -C "$work" --no-rebuild
for name in swrast_dri.so kms_swrast_dri.so; do
    ln -sfn libdril_dri.so "$work/staged/opt/viruaisor/zink25/lib/dri/$name"
done
find "$work/staged/opt/viruaisor/zink25" -type f -name '*.so*' -exec sha256sum {} +
