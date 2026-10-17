#!/usr/bin/env bash
# Host build only. Keep the standalone app probe separate from the AVF renderer's loader setup.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
paired=${VENUS_WORK_DIR:?Set the qualified renderer workspace}
ndk=${ANDROID_NDK_HOME:?Set Linux Android NDK r30}
work=/tmp/viruaisor-venus-context-build
revision=07a5bc398dfffdc36ad20a3476348753aa10a322
[[ $(git -C "$paired/virglrenderer" rev-parse HEAD) == "$revision" ]]
[[ $(git -C "$paired/virglrenderer/subprojects/venus-protocol-1.1.3" rev-parse HEAD) == ca19b6358d7cc491bc3e4de76f04c6700876a8fa ]]
mkdir -p "$work/virglrenderer"
if [[ ! -f "$work/virglrenderer/meson.build" ]]; then
    git -C "$paired/virglrenderer" archive "$revision" | tar -x -C "$work/virglrenderer"
fi
protocol="$work/virglrenderer/subprojects/venus-protocol-1.1.3"
mkdir -p "$protocol"
if [[ ! -f "$protocol/meson.build" ]]; then
    git -C "$paired/virglrenderer/subprojects/venus-protocol-1.1.3" archive HEAD | tar -x -C "$protocol"
fi
patch="$repo/native/venus-host/0001-destroy-object-mutex-after-objects.patch"
git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || git -C "$work/virglrenderer" apply "$patch"
mkdir -p "$work/pkgconfig"
sed -e "s|@NDK@|$ndk|g" -e "s|@REPO@|$repo|g" \
    -e "s|pkg_config_libdir = \[\]|pkg_config_libdir = ['$work/pkgconfig']|" \
    "$repo/native/venus-host/android-cross.ini.in" > "$work/android-cross.ini"
setup=()
[[ ! -f "$work/build-android/build.ninja" ]] || setup=(--reconfigure --clearcache)
meson setup "${setup[@]}" "$work/build-android" "$work/virglrenderer" --cross-file "$work/android-cross.ini" \
    -Dvenus=true -Dvrend=false -Dplatforms=[] -Ddrm-renderers=[] -Dtests=false \
    -Dvideo=false -Dvulkan-dload=true -Drender-server-mode=thread \
    -Drender-server-worker=thread -Ddefault_library=static -Db_staticpic=true
ninja -C "$work/build-android"
mkdir -p "$work/driver-generated"
python3 "$protocol/vn_protocol.py" --outdir "$work/driver-generated" \
    --banner "$work/build-android/subprojects/venus-protocol-1.1.3/banner"
VENUS_WORK_DIR="$work" bash "$repo/scripts/compile-venus-host-probe.sh"
