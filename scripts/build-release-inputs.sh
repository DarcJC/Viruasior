#!/usr/bin/env bash
# Rebuild the source-built helpers and guest image around the reviewed crosvm candidates.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
inputs=${VIRUAISOR_INPUTS_DIR:?Set VIRUAISOR_INPUTS_DIR to the persistent device-debug input directory}
crosvm_bins=${VIRUAISOR_CROSVM_BIN_DIR:?Set VIRUAISOR_CROSVM_BIN_DIR to the two reviewed crosvm executables}
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r30}"
: "${VENUS_WORK_DIR:?Set VENUS_WORK_DIR to the pinned host renderer workspace}"
[[ "$inputs" == /* && "$crosvm_bins" == /* && "$VENUS_WORK_DIR" == /* ]] || {
    echo 'Release input directories must be absolute Linux paths' >&2
    exit 1
}
[[ -d "$inputs/venus-crosvm-build/mesa-guest-reference/.git" ]]
[[ -d /tmp/viruaisor-manjaro/root/usr ]]
[[ -f /tmp/viruaisor-manjaro/rootfs.tar.gz ]]
printf '%s  %s\n' \
    1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a \
    /tmp/viruaisor-manjaro/rootfs.tar.gz | sha256sum -c -
printf '%s  %s\n' \
    1008c4aa740113c3bccd1aa72376bcd23602a2f94aa170d51617b7089c647343 \
    "$inputs/image/vmlinuz" | sha256sum -c -
printf '%s  %s\n' \
    39b4de660fc6d7a965265164bfc4ca71662032d97a7d43ef25a905ead7c2c475 \
    "$inputs/image/initrd.img" | sha256sum -c -
printf '%s  %s\n' \
    a4a71002533174408d0acdbfe3d4980482087bc9a29d0428ac423579bce9029b \
    "$crosvm_bins/libvenus_crosvm.so" | sha256sum -c -
printf '%s  %s\n' \
    ccdf777ee6e2098cf92d7b51cc0d47f403714c170b568e04daf9165140708571 \
    "$crosvm_bins/libvenus_desktop_crosvm.so" | sha256sum -c -

mkdir -p "$repo/.pi" "$repo/app/src/main/jniLibs/arm64-v8a"
[[ ! -e "$repo/.pi/device-debug" ]] || {
    echo 'The checkout must start without a .pi/device-debug path' >&2
    exit 1
}
ln -s "$inputs" "$repo/.pi/device-debug"
for name in libvenus_crosvm.so libvenus_desktop_crosvm.so; do
    install -m0755 "$crosvm_bins/$name" "$repo/app/src/main/jniLibs/arm64-v8a/$name"
done

cd "$repo"
bash scripts/build-native-binder.sh
bash scripts/build-storage-process.sh
bash scripts/build-rootfs-tools.sh
bash scripts/build-venus-host.sh
glslangValidator -V --target-env vulkan1.1 \
    -o "$VENUS_WORK_DIR/probe.spv" native/venus-host/compute.comp
VENUS_SHADER_SPV="$VENUS_WORK_DIR/probe.spv" bash scripts/compile-venus-host-probe.sh
bash scripts/compile-venus-overlay-probe.sh
bash scripts/build-manjaro-venus-icd.sh
bash scripts/build-manjaro-zink.sh
if [[ $(id -u) == 0 ]]; then
    bash scripts/build-manjaro-bundle.sh
else
    sudo -E bash scripts/build-manjaro-bundle.sh
fi
