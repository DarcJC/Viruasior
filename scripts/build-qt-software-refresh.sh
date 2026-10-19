#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
work=/tmp/viruaisor-qt-software-refresh
version=5.15.12
grep -Fq '#define QT_VERSION_STR "5.15.12"' "$root/usr/include/qt/QtCore/qconfig.h"
mkdir -p "$work" "$repo/.pi/device-debug/manjaro-plasma"
cp "$repo/guest/plasma/qt-software-refresh/"{refresh.cpp,metadata.json} "$work/"
includes=(-I"$root/usr/include" -I"$root/usr/include/qt")
for part in QtCore QtGui; do
    includes+=(-I"$root/usr/include/qt/$part" -I"$root/usr/include/qt/$part/$version" -I"$root/usr/include/qt/$part/$version/$part")
done
qemu-aarch64 -L "$root" "$root/usr/bin/moc" "${includes[@]}" "$work/refresh.cpp" -o "$work/refresh.moc"
aarch64-linux-gnu-g++ --sysroot="$root" -std=c++17 -O2 -fPIC -shared -fvisibility=hidden \
    "${includes[@]}" "$work/refresh.cpp" -L"$root/usr/lib" -Wl,-rpath-link,"$root/usr/lib" \
    -lQt5Gui -lQt5Core -o "$work/libviruaisor_software_refresh.so"
cp "$work/libviruaisor_software_refresh.so" "$repo/.pi/device-debug/manjaro-plasma/"
sha256sum "$work/libviruaisor_software_refresh.so"
