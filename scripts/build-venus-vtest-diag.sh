#!/usr/bin/env bash
# Build-only host diagnostic. This does not replace the application's AVF transport.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
baseline=/tmp/viruaisor-venus-context-build
[[ -f "$baseline/build-android/build.ninja" ]]
work=$baseline
if [[ ( ${1:-} == --android-memory || ${1:-} == --android-memory-filter ) && $# == 1 ]]; then
    work=/tmp/viruaisor-venus-vtest-memory-build
    [[ $1 != --android-memory-filter ]] || work+=-filter
    mkdir -p "$work"
    if [[ ! -f "$work/virglrenderer/meson.build" ]]; then
        cp -a "$baseline/virglrenderer" "$work/virglrenderer"
    fi
    # Only allocator/cache/buffer hooks. The AVF runtime's strict system-namespace
    # loader pin stays untouched; Vtest main retains the standalone TLS pin.
    patch="$repo/native/venus-crosvm/renderer-android-runtime.patch"
    git -C "$work/virglrenderer" apply --exclude=src/venus/vkr_renderer.c --reverse --check "$patch" 2>/dev/null || \
        git -C "$work/virglrenderer" apply --exclude=src/venus/vkr_renderer.c "$patch"
    patch="$repo/native/venus-host/venus-vtest-memory-log.patch"
    git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || \
        git -C "$work/virglrenderer" apply "$patch"
    if [[ $1 == --android-memory-filter ]]; then
        patch="$repo/native/venus-crosvm/android-import-memory-properties.patch"
        git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || \
            git -C "$work/virglrenderer" apply "$patch"
        patch="$repo/native/venus-crosvm/android-import-buffer-requirements.patch"
        git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || \
            git -C "$work/virglrenderer" apply "$patch"
    fi
    sed "s|'-fPIC',|'-fPIC', '-I$repo/native/venus-crosvm',|" \
        "$baseline/android-cross.ini" > "$work/android-cross.ini"
elif [[ $# != 0 ]]; then
    echo 'usage: build-venus-vtest-diag.sh [--android-memory|--android-memory-filter]' >&2
    exit 2
fi
patch="$repo/native/venus-host/venus-vtest-build.patch"
git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || git -C "$work/virglrenderer" apply "$patch"
patch="$repo/native/venus-host/venus-vtest-link.patch"
git -C "$work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null || git -C "$work/virglrenderer" apply "$patch"
if [[ "$work" != "$baseline" && ! -f "$work/build-android/build.ninja" ]]; then
    meson setup "$work/build-android" "$work/virglrenderer" --cross-file "$work/android-cross.ini" \
        -Dvenus=true -Dvrend=false -Dplatforms=[] -Ddrm-renderers=[] -Dtests=false \
        -Dvideo=false -Dvulkan-dload=true -Drender-server-mode=thread \
        -Drender-server-worker=thread -Ddefault_library=static -Db_staticpic=true
fi
ninja -C "$work/build-android" vtest/virgl_test_server
suffix=
[[ "$work" == "$baseline" ]] || suffix=-android-memory
[[ "$work" != *-filter ]] || suffix+=-filter
install -Dm0755 "$work/build-android/vtest/virgl_test_server" "$repo/.pi/device-debug/manjaro-plasma/venus-vtest-server$suffix"
