#!/usr/bin/env bash
# Build vkmark and both window-system plugins against the same guest headers.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${MANJARO_WORK_DIR:-/tmp/viruaisor-manjaro}
root="$work/root"
source="$work/vkmark-source"
patch="$repo/native/venus-crosvm/vkmark-noncoherent-memory.patch"
[[ -d "$root/usr" && -f "$patch" ]]
[[ $(id -u) == 0 ]] || { echo 'Run as root in the WSL build workspace' >&2; exit 1; }

if [[ ${1:-} != --isolated ]]; then
    [[ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] || \
        cat /usr/lib/binfmt.d/qemu-aarch64.conf > /proc/sys/fs/binfmt_misc/register
    exec unshare --mount --pid --fork --mount-proc --propagation private bash "$0" --isolated
fi

cleanup() {
    for mountpoint in dev proc sys; do
        mountpoint -q "$root/$mountpoint" && umount -R "$root/$mountpoint" || true
    done
}
trap cleanup EXIT
mkdir -p "$root/dev/pts" "$root/proc" "$root/sys"
mount --rbind /dev "$root/dev"
mount --make-rslave "$root/dev"
mount -t proc proc "$root/proc"
mount -t sysfs -o ro sysfs "$root/sys"
cp -L /etc/resolv.conf "$root/etc/resolv.conf"

chroot "$root" /usr/bin/pacman -S --needed --noconfirm \
    vkmark weston gcc meson ninja glm gzip pkgconf assimp vulkan-headers \
    xcb-util-wm wayland wayland-protocols libdrm

if [[ ! -d "$source/.git" ]]; then
    git clone https://github.com/vkmark/vkmark.git "$source"
fi
git -C "$source" checkout --detach cf45f2faee236fd1118be2fcd27e4f2a91fc2e40
[[ $(git -C "$source" rev-parse HEAD) == cf45f2faee236fd1118be2fcd27e4f2a91fc2e40 ]]
if git -C "$source" apply --check "$patch" 2>/dev/null; then
    git -C "$source" apply "$patch"
else
    git -C "$source" apply --reverse --check "$patch"
fi

guest_source="$root/tmp/viruaisor-vkmark-source"
mkdir -p "$guest_source" "$root/tmp/viruaisor-vkmark-pkgconfig"
cp -a "$source/." "$guest_source/"
printf 'Name: glm\nDescription: Header-only GLM\nVersion: 1.0.0\nCflags: -I/usr/include\n' > \
    "$root/tmp/viruaisor-vkmark-pkgconfig/glm.pc"
meson_env=(/usr/bin/env PKG_CONFIG_PATH=/tmp/viruaisor-vkmark-pkgconfig)
build=/tmp/viruaisor-vkmark-build
if [[ -f "$root$build/build.ninja" ]]; then
    chroot "$root" "${meson_env[@]}" /usr/bin/meson setup --reconfigure "$build" /tmp/viruaisor-vkmark-source \
        --prefix=/opt/viruaisor/vkmark --libdir=lib --buildtype=release \
        -Dkms=false -Dxcb=true -Dwayland=true
else
    chroot "$root" "${meson_env[@]}" /usr/bin/meson setup "$build" /tmp/viruaisor-vkmark-source \
        --prefix=/opt/viruaisor/vkmark --libdir=lib --buildtype=release \
        -Dkms=false -Dxcb=true -Dwayland=true
fi
chroot "$root" /usr/bin/ninja -j4 -C "$build" src/vkmark src/xcb.so src/wayland.so
chroot "$root" /usr/bin/meson install -C "$build" --no-rebuild
sha256sum "$root/opt/viruaisor/vkmark/bin/vkmark" \
    "$root/opt/viruaisor/vkmark/lib/vkmark/"{xcb,wayland}.so
chroot "$root" /usr/bin/pacman -Rns --noconfirm \
    gcc meson ninja glm gzip pkgconf wayland-protocols
chroot "$root" /usr/bin/rm -rf \
    /tmp/viruaisor-vkmark-source /tmp/viruaisor-vkmark-build \
    /tmp/viruaisor-vkmark-pkgconfig
