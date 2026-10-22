#!/usr/bin/env bash
# Bootstrap a fresh GitHub-hosted Ubuntu runner. No persistent paths are assumed.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${RUNNER_TEMP:?This entry point requires a disposable runner}"
: "${VIRUAISOR_RELEASE_INPUTS_URL:?Set the URL of the reviewed four-file input ZIP}"
: "${VIRUAISOR_RELEASE_INPUTS_SHA256:?Set the SHA-256 printed by release_inputs.py pack}"
[[ "$VIRUAISOR_RELEASE_INPUTS_URL" == https://* ]]
[[ "$VIRUAISOR_RELEASE_INPUTS_SHA256" =~ ^[a-f0-9]{64}$ ]]
inputs="$RUNNER_TEMP/viruaisor-inputs"
archive="$RUNNER_TEMP/viruaisor-inputs.zip"
curl --fail --location --retry 3 --connect-timeout 30 --proto '=https' --proto-redir '=https' \
    "$VIRUAISOR_RELEASE_INPUTS_URL" -o "$archive"
printf '%s  %s\n' "$VIRUAISOR_RELEASE_INPUTS_SHA256" "$archive" | sha256sum -c -
python3 "$repo/scripts/release_inputs.py" unpack "$archive" "$inputs"
mkdir -p "$inputs/image"
mv "$inputs/vmlinuz" "$inputs/initrd.img" "$inputs/image/"
mkdir -p "$repo/.pi"
ln -s "$inputs" "$repo/.pi/device-debug"

# Recreate source workspaces at the same commits the existing builders check.
mesa="$inputs/venus-crosvm-build/mesa-guest-reference"
git init "$mesa"
git -C "$mesa" remote add origin https://gitlab.freedesktop.org/mesa/mesa.git
git -C "$mesa" fetch --depth 1 origin 742a20f48c59e8649533c84c4d49dd95b403f5da
git -C "$mesa" checkout --detach FETCH_HEAD
git -C "$mesa" apply "$repo/native/venus-crosvm/guest-mesa-cache-sync.patch"

# The base distribution is public; only kernel and crosvm need one-time export.
work=/tmp/viruaisor-manjaro
[[ ! -e "$work" ]] || { echo "Expected fresh guest workspace: $work" >&2; exit 1; }
mkdir -p "$work/root"
curl --fail --location --retry 3 --connect-timeout 30 --proto '=https' --proto-redir '=https' \
    https://github.com/manjaro-arm/rootfs/releases/download/20261012/Manjaro-ARM-aarch64-latest.tar.gz \
    -o "$work/rootfs.tar.gz"
printf '%s  %s\n' \
    1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a \
    "$work/rootfs.tar.gz" | sha256sum -c -
sudo tar --numeric-owner -xpf "$work/rootfs.tar.gz" -C "$work/root"
if [[ ! -f /proc/sys/fs/binfmt_misc/register ]]; then
    sudo mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc
fi
if [[ ! -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]; then
    if [[ -f /usr/lib/binfmt.d/qemu-aarch64.conf ]]; then
        sudo tee /proc/sys/fs/binfmt_misc/register < /usr/lib/binfmt.d/qemu-aarch64.conf > /dev/null
    else
        sudo update-binfmts --enable qemu-aarch64
    fi
fi
[[ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]
sudo bash "$repo/scripts/prepare-manjaro-rootfs.sh"

{
    printf 'VIRUAISOR_INPUTS_DIR=%s\n' "$inputs"
    printf 'VIRUAISOR_CROSVM_BIN_DIR=%s\n' "$inputs"
    printf 'VENUS_WORK_DIR=%s\n' "$RUNNER_TEMP/viruaisor-venus-host"
} >> "${GITHUB_ENV:?}"
