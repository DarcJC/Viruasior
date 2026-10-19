#!/system/bin/sh
# Read-only inventory of this project's stopped guest image. No journal replay or chroot.
set -eu
# This helper runs on Android, where host APK metadata and Node are unavailable.
# Its host caller resolves the APK applicationId and passes it explicitly.
package=${PACKAGE-}
package_option=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --package)
            [ "$package_option" = false ] && [ "$#" -ge 2 ] || { echo 'Expected one --package value' >&2; exit 2; }
            package=$2; package_option=true; shift 2 ;;
        --package=*)
            [ "$package_option" = false ] || { echo '--package may appear only once' >&2; exit 2; }
            package=${1#--package=}; package_option=true; shift ;;
        *) echo 'Usage: PACKAGE=application.id sh inspect-gpu-guest-offline.sh [--package application.id]' >&2; exit 2 ;;
    esac
done
case "$package" in ''|*[!A-Za-z0-9_.]*) echo 'Explicit valid PACKAGE/--package is required' >&2; exit 2 ;; esac
[ "${#package}" -le 255 ] && printf '%s\n' "$package" | LC_ALL=C grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' || {
    echo 'Invalid applicationId' >&2; exit 2;
}
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
processes=$(ps -A)
if printf '%s\n' "$processes" | grep -E 'crosvm' >/dev/null ||
   printf '%s\n' "$processes" | grep -F "$package:root:" >/dev/null; then
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
