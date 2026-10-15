#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_WORK_DIR:-"$repo/.pi/device-debug/venus-host-build"}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Linux Android NDK r30 directory}
renderer_revision=07a5bc398dfffdc36ad20a3476348753aa10a322
protocol_revision=ca19b6358d7cc491bc3e4de76f04c6700876a8fa
mkdir -p "$work"
if [[ ! -d "$work/virglrenderer/.git" ]]; then
    git clone --depth 1 --no-checkout https://gitlab.freedesktop.org/virgl/virglrenderer.git "$work/virglrenderer"
    git -C "$work/virglrenderer" fetch --depth 1 origin "$renderer_revision"
    git -C "$work/virglrenderer" checkout --detach "$renderer_revision"
fi
renderer="$work/virglrenderer"
[[ $(git -C "$renderer" rev-parse HEAD) == "$renderer_revision" ]] || {
    echo "Expected pinned renderer $renderer_revision; refusing a different checkout" >&2; exit 1;
}
protocol="$renderer/subprojects/venus-protocol-1.1.3"
if [[ ! -d "$protocol/.git" ]]; then
    git clone --depth 1 --branch v1.1.3 https://gitlab.freedesktop.org/virgl/venus-protocol.git "$protocol"
fi
[[ $(git -C "$protocol" rev-parse HEAD) == "$protocol_revision" ]] || exit 1
patch="$repo/native/venus-host/0001-destroy-object-mutex-after-objects.patch"
if git -C "$renderer" apply --check "$patch" 2>/dev/null; then
    git -C "$renderer" apply "$patch"
else
    git -C "$renderer" apply --reverse --check "$patch"
fi
sed -e "s|@NDK@|$ndk|g" -e "s|@REPO@|$repo|g" "$repo/native/venus-host/android-cross.ini.in" > "$work/android-cross.ini"
if [[ ! -f "$work/build-android/build.ninja" ]]; then
    meson setup "$work/build-android" "$renderer" --cross-file "$work/android-cross.ini" \
        -Dvenus=true -Dvrend=false -Dplatforms=[] -Ddrm-renderers=[] -Dtests=false \
        -Dvideo=false -Dvulkan-dload=true -Drender-server-mode=thread \
        -Drender-server-worker=thread -Ddefault_library=static -Db_staticpic=true
fi
ninja -C "$work/build-android"
mkdir -p "$work/driver-generated"
python3 "$protocol/vn_protocol.py" --outdir "$work/driver-generated" \
    --banner "$work/build-android/subprojects/venus-protocol-1.1.3/banner"
printf 'Renderer %s; protocol %s\n' "$renderer_revision" "$protocol_revision"
