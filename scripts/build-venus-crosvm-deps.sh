#!/usr/bin/env bash
# Target-only static Wayland/libffi inputs. No device deployment or dummy backend.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK r30}
target_prefix="$work/android-prefix"
buildroot=${VENUS_CROSVM_DEPS_BUILD_DIR:-/tmp/viruaisor-crosvm-venus-deps}
[[ $(git -C "$work/libffi" rev-parse HEAD) == 3d0ce1e6fcf19f853894862abcbac0ae78a7be60 ]]
[[ $(git -C "$work/wayland" rev-parse HEAD) == 736d12ac67c20c60dc406dc49bb06be878501f86 ]]
mkdir -p "$buildroot/libffi" "$target_prefix"
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang"
if [[ ! -f "$work/libffi/configure" ]]; then
    (cd "$work/libffi"; bash autogen.sh)
fi
(
    cd "$buildroot/libffi"
    CC="$compiler" CXX="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang++" \
    NM="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" STRIP="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
    AR="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" \
    RANLIB="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib" CFLAGS='-O2 -fPIC' \
        "$work/libffi/configure" --host=aarch64-linux-android --prefix="$target_prefix" \
        --libdir="$target_prefix/lib" --disable-shared --enable-static --disable-docs
    make -j4
    make install
)
sed -e "s|@NDK@|$ndk|g" -e "s|@TARGET_PREFIX@|$target_prefix|g" \
    "$repo/native/venus-crosvm/android-deps-cross.ini.in" > "$buildroot/android-cross.ini"
if [[ ! -f "$buildroot/wayland/build.ninja" ]]; then
    meson setup "$buildroot/wayland" "$work/wayland" --cross-file "$buildroot/android-cross.ini" \
        --prefix "$target_prefix" --libdir lib -Ddefault_library=static -Db_staticpic=true \
        -Dscanner=false -Dtests=false -Ddocumentation=false -Ddtd_validation=false
fi
meson compile -C "$buildroot/wayland" -j4
meson install -C "$buildroot/wayland"
