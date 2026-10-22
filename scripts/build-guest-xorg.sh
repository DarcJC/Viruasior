#!/usr/bin/env bash
# Build the paired guest server; never replace a host or distro Xorg.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
work=${VIRUAISOR_XORG_WORK_DIR:-/tmp/viruaisor-guest-xorg}
version=21.1.11
archive_sha=1d3dadbd57fb86b16a018e9f5f957aeeadf744f56c0553f55737628d06d326ef
source_dir="$work/xorg-server-$version"
archive="$work/xorg-server-$version.tar.xz"
patch_file="$repo/guest/xorg/present-clock.patch"
copy_patch="$repo/guest/xorg/present-copy-clock.patch"
out="$repo/.pi/device-debug/manjaro-plasma/xorg"
mkdir -p "$work" "$out"

# All target headers/libraries come from the paired Manjaro build root. xtrans
# is header-only; install its development headers in that build root too.
for header in X11/Xtrans/Xtrans.h bsd/stdlib.h; do
    test -f "$root/usr/include/$header"
done
test -f "$root/usr/lib/xorg/modules/drivers/modesetting_drv.so"
test -d "$root/var/lib/pacman/local/xorg-server-$version-1"
if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 3 --connect-timeout 20 \
        "https://www.x.org/releases/individual/xserver/xorg-server-$version.tar.xz" \
        -o "$archive.download"
    printf '%s  %s\n' "$archive_sha" "$archive.download" | sha256sum -c -
    mv "$archive.download" "$archive"
fi
printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c -
if [[ ! -d "$source_dir" ]]; then tar -xJf "$archive" -C "$work"; fi
# Normalize the follow-up first: it changes context in the base clock patch.
if patch --dry-run --force --reverse -p1 -d "$source_dir" < "$copy_patch" >/dev/null 2>&1; then
    patch --force --reverse -p1 -d "$source_dir" < "$copy_patch"
fi
if patch --dry-run --batch --forward -p1 -d "$source_dir" < "$patch_file" >/dev/null; then
    patch --batch --forward -p1 -d "$source_dir" < "$patch_file"
else
    patch --dry-run --batch --reverse -p1 -d "$source_dir" < "$patch_file" >/dev/null
fi
patch --batch --forward -p1 -d "$source_dir" < "$copy_patch"
cp "$repo/guest/xorg/present_clock.h" "$source_dir/present/present_clock.h"
gcc -Wall -Wextra -Werror -fsanitize=undefined \
    "$repo/guest/xorg/present_clock_test.c" -o "$work/present-clock-test"
"$work/present-clock-test"

python3 "$repo/guest/xorg/extract_present_scope.py" \
    "$source_dir/present/present_scmd.c" "$work/present-scope-test"
gcc -std=c11 -Wall -Wextra -Werror -fsanitize=undefined \
    -I"$work/present-scope-test" "$repo/guest/xorg/present_scope_test.c" \
    -o "$work/present-scope-test/run"
"$work/present-scope-test/run"

gcc_include=$(aarch64-linux-gnu-gcc -print-file-name=include)
sed -e "s|@SYSROOT@|$root|g" -e "s|@GCC_INCLUDE@|$gcc_include|g" \
    "$repo/guest/xorg/guest-cross.ini.in" > "$work/cross.ini.next"
if [[ -f "$work/build/build.ninja" ]] && ! cmp -s "$work/cross.ini" "$work/cross.ini.next"; then
    echo 'Cross compiler inputs changed; choose a fresh VIRUAISOR_XORG_WORK_DIR.' >&2
    exit 1
fi
mv "$work/cross.ini.next" "$work/cross.ini"
# Avoid probing unrelated Windows PATH entries when invoked from WSL.
export PATH=/usr/bin:/bin
export PKG_CONFIG_PATH=
setup_flags=()
if [[ -f "$work/build/build.ninja" ]]; then setup_flags=(--reconfigure); fi
meson setup "${setup_flags[@]}" "$work/build" "$source_dir" --cross-file "$work/cross.ini" \
    --prefix=/usr --libdir=lib -Dbuildtype=release -Db_lto=false \
    -Dxorg=true -Dglamor=true -Ddri3=true -Dglx=true \
    -Dxephyr=false -Dxnest=false -Dxvfb=false -Dxwin=false -Dxquartz=false \
    -Dsecure-rpc=false -Dsystemd_logind=true -Dudev=true -Dint10=false \
    -Ddocs=false -Ddevel-docs=false -Dmodule_dir=/usr/lib/xorg/modules \
    -Ddefault_font_path=/usr/share/fonts/misc,/usr/share/fonts/TTF,/usr/share/fonts/OTF,/usr/share/fonts/Type1,/usr/share/fonts/100dpi,/usr/share/fonts/75dpi \
    -Dxkb_dir=/usr/share/X11/xkb -Dxkb_output_dir=/var/lib/xkb -Dxkb_bin_dir=/usr/bin
ninja -j4 -C "$work/build" hw/xfree86/Xorg
aarch64-linux-gnu-readelf -h "$work/build/hw/xfree86/Xorg" | grep -q AArch64
install -m0755 "$work/build/hw/xfree86/Xorg" "$out/Xorg"
install -m0644 "$source_dir/COPYING" "$out/COPYING"
# This optional client probe is an output for diagnostics, not a bundle file.
aarch64-linux-gnu-gcc --sysroot="$root" -B"$root/usr/lib/" -nostdinc \
    -isystem "$gcc_include" -isystem "$root/usr/include" -Wall -Wextra -Werror \
    -O2 -fPIC -shared "$repo/guest/xorg/present_clock_probe.c" \
    -L"$root/usr/lib" -Wl,-rpath-link,"$root/usr/lib" -lxcb-present -lxcb -ldl \
    -o "$out/present-clock-probe.so"
sha256sum "$archive" "$patch_file" "$copy_patch" "$repo/guest/xorg/present_clock.h" "$out/Xorg" > "$out/SHA256SUMS"
cat "$out/SHA256SUMS"
