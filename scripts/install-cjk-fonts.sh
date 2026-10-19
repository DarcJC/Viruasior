#!/usr/bin/env bash
# Build-host guest rootfs only. Does not access Android or alter its files.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(realpath -e "${1:?Pass an unpacked guest rootfs directory}")
[[ "$root" != / && -x "$root/usr/bin/fc-cache" && -f "$root/etc/os-release" ]]
payload="$repo/.pi/device-debug/cjk-fonts"
python3 "$repo/scripts/prepare-cjk-fonts.py" --output "$payload"
install -d -m0755 "$root/usr/local/share/fonts/viruaisor"
for name in NotoSansCJKsc-Regular.otf NotoSansCJKsc-Bold.otf NotoSerifCJKsc-Regular.otf LICENSE; do
    install -m0644 "$payload/$name" "$root/usr/local/share/fonts/viruaisor/$name"
done
if [[ -x "$root/usr/bin/qemu-aarch64-static" ]]; then
    chroot "$root" /usr/bin/qemu-aarch64-static /usr/bin/fc-cache -f /usr/local/share/fonts/viruaisor
    result=$(chroot "$root" /usr/bin/qemu-aarch64-static /usr/bin/fc-list :lang=zh family)
else
    chroot "$root" /usr/bin/fc-cache -f /usr/local/share/fonts/viruaisor
    result=$(chroot "$root" /usr/bin/fc-list :lang=zh family)
fi
[[ "$result" == *"Noto Sans CJK SC"* && "$result" == *"Noto Serif CJK SC"* ]]
printf '%s\n' "$result"
