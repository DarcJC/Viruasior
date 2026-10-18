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
if ! grep -Fq VIRUAISOR_ZINK_BUFFER_COHERENCE "$source/src/gallium/drivers/zink/zink_screen.c"; then
    git -C "$source" apply "$patch"
fi
cp "$repo/native/venus-crosvm/zink_venus_dmabuf_policy.h" "$source/src/gallium/drivers/zink/"
cp "$repo/native/venus-crosvm/zink_venus_upload_policy.h" "$source/src/gallium/drivers/zink/"
if ! grep -Fq zink_venus_private_upload "$source/src/gallium/drivers/zink/zink_bo.c"; then
    git -C "$source" apply "$repo/native/venus-crosvm/zink-venus-small-upload.patch"
fi
if ! grep -Fq zink_venus_linear_dmabuf "$source/src/gallium/drivers/zink/zink_resource.c"; then
    git -C "$source" apply "$repo/native/venus-crosvm/zink-venus-pixmap-linear.patch"
fi
if ! grep -Fq VIRUAISOR_ZINK_SHARED_PIXMAP_EXPORT "$source/src/gallium/drivers/zink/zink_resource.c"; then
    git -C "$source" apply "$repo/native/venus-crosvm/zink-venus-shared-pixmap.patch"
fi
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/zink_venus_dmabuf_policy_test.c" -o /tmp/viruaisor-zink-dmabuf-test
/tmp/viruaisor-zink-dmabuf-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/zink_venus_upload_policy_test.c" -o /tmp/viruaisor-zink-upload-test
/tmp/viruaisor-zink-upload-test
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
