#!/usr/bin/env bash
# Build-host preparation only. Never execute this script on Android.
set -euo pipefail
if [[ ${1:-} != --isolated ]]; then
    [[ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] || cat /usr/lib/binfmt.d/qemu-aarch64.conf > /proc/sys/fs/binfmt_misc/register
    exec unshare --mount --pid --fork --mount-proc --propagation private bash "$0" --isolated
fi
work=${MANJARO_WORK_DIR:-/tmp/viruaisor-manjaro}
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root="$work/root"
[[ "$work" == /tmp/viruaisor-manjaro && -d "$root/usr" && ! -L "$root" ]]
echo '1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a  rootfs.tar.gz' | (cd "$work" && sha256sum -c -)
[[ $(id -u) == 0 ]] || { echo 'Run inside the WSL build workspace as root' >&2; exit 1; }
cleanup() {
    chroot "$root" /usr/bin/gpgconf --homedir /etc/pacman.d/gnupg --kill all || true
    for mountpoint in dev proc sys; do
        mountpoint -q "$root/$mountpoint" && umount -R "$root/$mountpoint" || true
    done
    umount "$root" || true
}
trap cleanup EXIT
# libalpm's CheckSpace needs a root mount visible from inside the chroot.
mount --bind "$root" "$root"
mkdir -p "$root/dev/pts" "$root/proc" "$root/sys"
mount --rbind /dev "$root/dev"
mount --make-rslave "$root/dev"
mount -t proc proc "$root/proc"
mount -t sysfs -o ro sysfs "$root/sys"
cp -L /etc/resolv.conf "$root/etc/resolv.conf"
chroot "$root" /usr/bin/pacman-key --init
chroot "$root" /usr/bin/pacman-key --populate archlinuxarm manjaro manjaro-arm
chroot "$root" /usr/bin/pacman -Syu --noconfirm
chroot "$root" /usr/bin/pacman -S --needed --noconfirm plasma-desktop plasma-workspace kwin \
    konsole dolphin xorg-server xorg-xinit xorg-xrandr mesa vulkan-virtio vulkan-tools mesa-utils \
    dbus systemd systemd-sysvcompat sudo sed networkmanager pipewire wireplumber qt6-wayland xorg-xwayland \
    vkmark weston xtrans libbsd
bash "$repo/scripts/install-cjk-fonts.sh" "$root"
chroot "$root" /usr/bin/pacman -Q > "$work/packages.txt"
chroot "$root" /usr/bin/pacman -Qkk plasma-workspace kwin mesa vulkan-virtio > "$work/package-integrity.txt"
