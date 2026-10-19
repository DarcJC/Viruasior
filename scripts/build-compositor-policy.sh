#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=${MANJARO_ROOT:-/tmp/viruaisor-manjaro/root}
out="$repo/.pi/device-debug/manjaro-plasma/compositor-policy"
mkdir -p "$(dirname "$out")"
aarch64-linux-gnu-g++ --sysroot="$root" -std=c++17 -O2 -fPIC \
    -I"$root/usr/include/qt" -I"$root/usr/include/qt/QtCore" \
    -I"$root/usr/include/qt/QtGui" -I"$root/usr/include/qt/QtDBus" \
    "$repo/guest/plasma/compositor-policy.cpp" -L"$root/usr/lib" \
    -Wl,-rpath-link,"$root/usr/lib" -lQt5DBus -lQt5Gui -lQt5Core -o "$out"
sha256sum "$out"
