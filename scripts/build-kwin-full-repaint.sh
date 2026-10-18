#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
work=/tmp/viruaisor-kwin-full-repaint
# KWin's effect ABI is versioned. Build against the exact paired guest headers.
grep -Fq 'set(PACKAGE_VERSION "5.27.10")' "$root/usr/lib/cmake/KWinEffects/KWinEffectsConfigVersion.cmake"
mkdir -p "$work" "$repo/.pi/device-debug/manjaro-plasma"
cp "$repo/guest/plasma/kwin-full-repaint/"{full-repaint.cpp,metadata.json} "$work/"
includes=(-I"$root/usr/include" -I"$root/usr/include/qt")
for part in QtCore QtGui QtWidgets QtDBus QtX11Extras; do includes+=(-I"$root/usr/include/qt/$part"); done
for part in KCoreAddons KConfigCore KConfigGui KWindowSystem; do includes+=(-I"$root/usr/include/KF5/$part"); done
qemu-aarch64 -L "$root" "$root/usr/bin/moc" "${includes[@]}" "$work/full-repaint.cpp" -o "$work/full-repaint.moc"
aarch64-linux-gnu-g++ --sysroot="$root" -std=c++20 -O2 -fPIC -shared -fvisibility=hidden \
    "${includes[@]}" "$work/full-repaint.cpp" -L"$root/usr/lib" -Wl,-rpath-link,"$root/usr/lib" \
    -lkwineffects -lQt5Core -lQt5Gui -lKF5CoreAddons -o "$work/viruaisor_full_repaint.so"
cp "$work/viruaisor_full_repaint.so" "$repo/.pi/device-debug/manjaro-plasma/"
sha256sum "$work/viruaisor_full_repaint.so"
