#!/usr/bin/env bash
# Host-only CPU emulation test. It proves neither Venus acceleration nor Android presentation.
set -euo pipefail
work=/tmp/viruaisor-manjaro
bundle="$work/bundle"
log="$work/offline-boot.log"
[[ -f "$bundle/root_part" && -f "$bundle/vmlinuz" ]]
set +e
timeout 150 qemu-system-aarch64 -machine virt,gic-version=3 -cpu max -accel tcg,thread=multi \
    -smp 4 -m 2048 -nographic -monitor none -nodefaults -serial stdio \
    -kernel "$bundle/vmlinuz" -initrd "$bundle/initrd.img" \
    -append 'root=/dev/vda rw console=ttyAMA0,115200 systemd.unit=multi-user.target systemd.log_target=console loglevel=5 arm64.nompam' \
    -drive "if=none,id=root,file=$bundle/root_part,format=raw,snapshot=on" \
    -device virtio-blk-pci,drive=root -device virtio-gpu-pci -nic none -no-reboot > "$log" 2>&1
status=$?
set -e
[[ $status == 0 || $status == 124 ]]
grep -q 'Manjaro ARM' "$log"
grep -Eq 'Reached target .*Multi-User System|Reached target .*multi-user.target' "$log"
! grep -Eq 'Kernel panic|Failed to execute /sbin/init' "$log"
echo "PASS: Manjaro systemd multi-user boot with a temporary snapshot; no GPU/Surface claim. $log"
