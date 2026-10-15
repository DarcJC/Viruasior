#!/system/bin/sh
# Read-only inventory of this project's stopped guest image. No journal replay or chroot.
set -eu
package=pro.darc.viruaisor
mount_dir=$(mktemp -d /data/local/tmp/viruaisor-gpu-inventory.XXXXXX)
holder=
loop=
mounted=false
cleanup() {
    if [ "$mounted" = true ]; then umount "$mount_dir" || return; fi
    if [ -n "$loop" ]; then losetup -d "$loop" 2>/dev/null || true; fi
    if [ -n "$holder" ]; then
        pkill -P "$holder" 2>/dev/null || true
        kill "$holder" 2>/dev/null || true
        wait "$holder" 2>/dev/null || true
    fi
    rmdir "$mount_dir" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
if ps -A | grep -E 'crosvm|pro\.darc\.viruaisor:root:' >/dev/null; then
    echo 'Stop the VM/display helper before inspecting its image' >&2
    exit 1
fi
run-as "$package" sh -c 'exec 3<files/vms/default/root_part; sleep 60' &
holder=$!
sleep 1
test -e "/proc/$holder/fd/3"
loop=$(losetup -r -f -s "/proc/$holder/fd/3")
mount -t ext4 -o ro,noload "$loop" "$mount_dir"
mounted=true
echo 'GUEST_OFFLINE_READ_ONLY_BEGIN'
cat "$mount_dir/etc/os-release"
ls -l "$mount_dir/usr/share/vulkan/icd.d"
for icd in "$mount_dir/usr/share/vulkan/icd.d/"*.json; do
    echo "$icd"
    cat "$icd"
done
find "$mount_dir/usr/lib" -maxdepth 4 -type f | grep -E '(libvulkan_(virtio|gfxstream|panfrost|lvp)|panfrost_dri)' || true
find "$mount_dir/lib/modules" -type f | grep -E '/(virtio_gpu|panfrost|panthor)\.ko' || true
for config in "$mount_dir/boot/"config*; do
    [ -f "$config" ] || continue
    grep -E 'CONFIG_DRM_(VIRTIO_GPU|PANFROST|PANTHOR)=' "$config" || true
done
grep '^render:' "$mount_dir/etc/group"
echo 'GUEST_OFFLINE_READ_ONLY_END'
