#!/usr/bin/env bash
# Fetch the public October 2026 Ferrochrome guest kernel; never replace a reviewed input silently.
set -euo pipefail

out=${1:?Usage: fetch-ferrochrome-kernel.sh OUTPUT_DIR}
url=https://dl.google.com/android/ferrochrome/5100000/aarch64/images.tar.gz
archive_sha=443400a1d190df478592c5822ee9f02ff5f9684b6453c55ec6fff47156c8aab5
kernel_sha=1ba67545206723d88114138f7916b21d0b2796284dbb31e8cc65dda3561d5804
initrd_sha=ec395598085eb940b84fd2debe6eb0533aa1716ba1b03545cd25fcf8d7323ac0
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
archive=${FERROCHROME_ARCHIVE_FILE:-$scratch/images.tar.gz}
if [[ ! -f "$archive" ]]; then
    curl -fL --retry 3 --output "$archive" "$url"
fi
printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c -
tar -xzf "$archive" -C "$scratch" vmlinuz initrd.img build_id
printf '%s  %s\n' "$kernel_sha" "$scratch/vmlinuz" | sha256sum -c -
printf '%s  %s\n' "$initrd_sha" "$scratch/initrd.img" | sha256sum -c -
mkdir -p "$out"
for file in vmlinuz initrd.img build_id; do
    if [[ -e "$out/$file" ]] && ! cmp -s "$scratch/$file" "$out/$file"; then
        echo "Refusing to replace existing $out/$file" >&2
        exit 1
    fi
done
for file in vmlinuz initrd.img build_id; do
    cp "$scratch/$file" "$out/$file"
done
echo "Verified public Ferrochrome inputs in $out"
