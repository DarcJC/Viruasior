#!/usr/bin/env bash
# Build only. Import into Viruaisor separately through the ordinary application.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=/tmp/viruaisor-manjaro
root="$work/root"
out="$work/bundle"
workspace_out="$repo/.pi/device-debug/manjaro-plasma"
icd="$work/venus-build/build/src/virtio/vulkan/libvulkan_virtio.so"
zink="$work/zink25-build/staged/opt/viruaisor/zink25"
[[ -f "$icd" && -x "$root/usr/bin/startplasma-x11" ]]
[[ -x "$root/usr/bin/sed" ]]
[[ -x "$root/usr/bin/xrandr" ]]
[[ -f "$zink/lib/libgallium-25.0.7.so" && -f "$zink/lib/libEGL_mesa.so.0" && -f "$zink/lib/libGLX_mesa.so.0" && -f "$zink/lib/dri/zink_dri.so" ]]
[[ -L "$zink/lib/dri/swrast_dri.so" && -L "$zink/lib/dri/kms_swrast_dri.so" ]]
[[ -f /proc/sys/fs/binfmt_misc/register ]] || mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc
[[ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]] || cat /usr/lib/binfmt.d/qemu-aarch64.conf > /proc/sys/fs/binfmt_misc/register
! mountpoint -q "$root/dev"
! mountpoint -q "$root/proc"
mkdir -p "$out" "$workspace_out" "$root/opt/viruaisor/venus" "$root/etc/viruaisor" "$root/etc/X11/xorg.conf.d"
mkdir -p "$root/opt/viruaisor/zink25"
cp -a "$zink/." "$root/opt/viruaisor/zink25/"
mkdir -p "$root/etc/ld.so.conf.d"
printf '/opt/viruaisor/zink25/lib\n' > "$root/etc/ld.so.conf.d/00-viruaisor-zink25.conf"
rm -rf "$work/kernel-modules"
mkdir -p "$work/kernel-modules" "$root/usr/lib/modules"
(cd "$work/kernel-modules" && gzip -dc "$repo/.pi/device-debug/image/initrd.img" | cpio -idm --quiet)
cp -a "$work/kernel-modules/lib/modules/." "$root/usr/lib/modules/"
make -C "$repo/guest/avf-agent" arm64-static
make -C "$repo/guest/avf-agent" test
install -Dm0755 "$repo/guest/avf-agent/avf-agent" "$root/usr/local/sbin/avf-agent"
install -Dm0644 "$repo/docs/guest/avf-agent.service" "$root/usr/lib/systemd/system/avf-agent.service"
install -Dm0644 "$repo/guest/plasma/avf-plasma.service" "$root/usr/lib/systemd/system/avf-plasma.service"
install -m0644 "$repo/guest/plasma/venus.env" "$root/etc/viruaisor/venus.env"
bash "$repo/scripts/build-kwin-full-repaint.sh"
install -Dm0755 "$workspace_out/viruaisor_full_repaint.so" "$root/usr/lib/qt/plugins/kwin/effects/plugins/viruaisor_full_repaint.so"
bash "$repo/scripts/build-guest-xorg.sh"
install -Dm0755 "$workspace_out/xorg/Xorg" "$root/opt/viruaisor/xorg/Xorg"
install -m0644 "$workspace_out/xorg/COPYING" "$workspace_out/xorg/SHA256SUMS" "$root/opt/viruaisor/xorg/"
python3 -m unittest discover -s "$repo/guest/plasma" -p '*test.py'
for file in venus-preflight plasma-session display-control.py desktop_cpu.py; do install -m0755 "$repo/guest/plasma/$file" "$root/opt/viruaisor/$file"; done
for file in display_limits.py display_session.py display_wayland.py; do install -m0644 "$repo/guest/plasma/$file" "$root/opt/viruaisor/$file"; done
for wrapper in desktop-xorg desktop-kwin; do ln -sfn desktop_cpu.py "$root/opt/viruaisor/$wrapper"; done
install -m0644 "$repo/scripts/guest-gpu-draw-probe.py" "$root/opt/viruaisor/guest-gpu-draw-probe.py"
install -m0644 "$repo/guest/plasma/input-check.py" "$root/opt/viruaisor/input-check.py"
install -m0755 "$icd" "$root/opt/viruaisor/venus/libvulkan_virtio.so"
cat > "$root/opt/viruaisor/venus/venus_icd.json" <<'EOF'
{"file_format_version":"1.0.0","ICD":{"library_path":"/opt/viruaisor/venus/libvulkan_virtio.so","api_version":"1.3.0"}}
EOF
for group in video render input; do
    chroot "$root" getent group "$group" >/dev/null || chroot "$root" groupadd --system "$group"
done
chroot "$root" id droid >/dev/null 2>&1 || chroot "$root" useradd -m -u 1000 -g users -G video,render,input -s /bin/bash droid
[[ $(chroot "$root" id -u droid) == 1000 ]]
chroot "$root" usermod -a -G video,render,input droid
mkdir -p "$root/home/droid/Desktop"
cat > "$root/home/droid/Desktop/viruaisor-input-check.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Viruaisor Input Check
Exec=konsole --hold -e python3 /opt/viruaisor/input-check.py
Icon=input-keyboard
Terminal=false
EOF
chmod 0755 "$root/home/droid/Desktop/viruaisor-input-check.desktop"
chroot "$root" chown -R droid:users /home/droid/Desktop
chroot "$root" passwd -l droid
mkdir -p "$root/etc/sudoers.d"
printf 'droid ALL=(ALL:ALL) NOPASSWD: ALL\n' > "$root/etc/sudoers.d/viruaisor"
chmod 0440 "$root/etc/sudoers.d/viruaisor"
chroot "$root" visudo -c
mkdir -p "$root/etc/systemd/system/multi-user.target.wants" "$root/etc/systemd/system/network-online.target.wants" "$root/etc/systemd/system/sockets.target.wants"
ln -sfn /usr/lib/systemd/system/avf-agent.service "$root/etc/systemd/system/multi-user.target.wants/avf-agent.service"
ln -sfn /usr/lib/systemd/system/NetworkManager.service "$root/etc/systemd/system/multi-user.target.wants/NetworkManager.service"
ln -sfn /usr/lib/systemd/system/dbus.socket "$root/etc/systemd/system/sockets.target.wants/dbus.socket"
ln -sfn /usr/lib/systemd/system/multi-user.target "$root/etc/systemd/system/default.target"
cat > "$root/etc/X11/xorg.conf.d/20-viruaisor.conf" <<'EOF'
Section "Device"
    Identifier "Virtio Venus"
    Driver "modesetting"
    Option "AccelMethod" "glamor"
    # The paired app displays the virtio cursor as a separate small surface.
    # Software cursors prevent Xorg Present from using page flips.
    Option "SWcursor" "false"
EndSection
EOF
printf 'viruaisor-manjaro\n' > "$root/etc/hostname"
ldconfig_ready=false
for attempt in 1 2 3; do
    if chroot "$root" /usr/bin/ldconfig; then
        ldconfig_ready=true
        break
    fi
    sleep 1
done
[[ "$ldconfig_ready" == true ]]
chroot "$root" /usr/bin/ldconfig -p | grep -F 'libgbm.so.1 (libc6,AArch64) => /opt/viruaisor/zink25/lib/libgbm.so.1' >/dev/null
chroot "$root" /usr/bin/ldconfig -p | grep -F 'libEGL_mesa.so.0 (libc6,AArch64) => /opt/viruaisor/zink25/lib/libEGL_mesa.so.0' >/dev/null
truncate -s 0 "$root/etc/machine-id"
chroot "$root" /usr/lib/ld-linux-aarch64.so.1 --list /opt/viruaisor/venus/libvulkan_virtio.so > "$out/icd-loader.txt"
for lib in libEGL_mesa.so.0 libGLX_mesa.so.0 libgallium-25.0.7.so; do
    chroot "$root" /usr/lib/ld-linux-aarch64.so.1 --library-path /opt/viruaisor/zink25/lib \
        --list "/opt/viruaisor/zink25/lib/$lib" >> "$out/icd-loader.txt"
done
chroot "$root" /usr/bin/pacman -Q > "$out/packages.txt"
cp "$repo/.pi/device-debug/image/vmlinuz" "$out/vmlinuz"
cp "$repo/.pi/device-debug/image/initrd.img" "$out/initrd.img"
python3 - "$out/vm.json" <<'PY'
import json,sys
config={"version":1,"kernel":"vmlinuz","initrd":"initrd.img","rootfs":"root_part",
"rootfsPartition":{"label":"ROOT","guid":"EBBBECCD-10A6-4969-A904-38F513E6D5CA"},
"osName":"manjaro","memoryMiB":6144,"cpuTopology":"match_host","vsockPort":4050,
"network":True,"autoMemoryBalloon":True,"debuggable":True,"consoleInputDevice":"ttyS0",
"gpuProfile":"venus","display":{"width":1280,"height":720,"horizontalDpi":160,"verticalDpi":160,"refreshRate":60},
"kernelArgs":["root=/dev/vda1","rootwait","rw","console=ttyS0,115200","earlycon=uart8250,mmio,0x3f8",
"8250.nr_uarts=4","pci=nomsi","arm64.nompam","nohibernate","panic=-1","loglevel=5",
"drm_kms_helper.fbdev_emulation=0","systemd.unit=multi-user.target"]}
with open(sys.argv[1],"w") as f: json.dump(config,f,indent=2)
PY
truncate -s 8G "$out/root_part"
mkfs.ext4 -F -L ROOT -O '^metadata_csum_seed,^orphan_file' -d "$root" "$out/root_part"
e2fsck -fn "$out/root_part"
python3 - "$out" <<'PY'
import pathlib,sys,zipfile
out=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(out/'manjaro-plasma-venus.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=3) as z:
    for name in ('vm.json','vmlinuz','initrd.img','root_part'): z.write(out/name,name)
PY
(cd "$out" && sha256sum vm.json vmlinuz initrd.img root_part manjaro-plasma-venus.zip > SHA256SUMS)
cp "$out/"{manjaro-plasma-venus.zip,vm.json,vmlinuz,initrd.img,packages.txt,icd-loader.txt,SHA256SUMS} "$workspace_out/"
echo "Prepared bundle: $workspace_out/manjaro-plasma-venus.zip"
