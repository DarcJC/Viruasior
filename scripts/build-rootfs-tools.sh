#!/usr/bin/env bash
# Local build only. Does not install an APK or access a device image.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${ROOTFS_TOOLS_WORK_DIR:-/tmp/viruaisor-rootfs-tools}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK r30}
revision=c3cce4a07efefc62bc7fc57a678cb870af27d0f2
mkdir -p "$work"
if [[ ! -d "$work/source/.git" ]]; then
    git clone --quiet --depth 1 --branch v1.47.2 https://github.com/tytso/e2fsprogs.git "$work/source"
fi
[[ $(git -C "$work/source" rev-parse HEAD) == "$revision" ]]
git -C "$work/source" diff --quiet
export CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang"
export AR="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export RANLIB="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ranlib"
mkdir -p "$work/build"
"$CC" -O2 -fPIE -c "$repo/native/storage/tool_lifetime.c" -o "$work/lifetime.o"
cd "$work/build"
if [[ ! -f Makefile || "$repo/native/storage/tool_lifetime.c" -nt e2fsck/e2fsck ]]; then
    CFLAGS='-O2 -fPIE' LDFLAGS="-Wl,-z,max-page-size=16384 $work/lifetime.o" \
        "$work/source/configure" --host=aarch64-linux-android --disable-nls \
        --disable-elf-shlibs --disable-uuidd --disable-fsck --disable-e2initrd-helper --without-crond-dir
    # Refresh linkage when the lifetime object changes.
    rm -f e2fsck/e2fsck resize/resize2fs
fi
make -j"${JOBS:-8}" libs
make -C e2fsck -j"${JOBS:-8}" e2fsck
make -C resize -j"${JOBS:-8}" resize2fs
out="$repo/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$out"
cp e2fsck/e2fsck "$out/librootfs_e2fsck.so"
cp resize/resize2fs "$out/librootfs_resize2fs.so"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$out/librootfs_e2fsck.so" "$out/librootfs_resize2fs.so"
mkdir -p "$repo/app/src/main/assets/third_party"
{
    printf 'e2fsprogs v1.47.2, revision %s\nSource: https://github.com/tytso/e2fsprogs/tree/%s\n\n' "$revision" "$revision"
    cat "$work/source/NOTICE" "$work/source/lib/uuid/COPYING" "$work/source/lib/ss/mit-sipb-copyright.h"
} > "$repo/app/src/main/assets/third_party/rootfs-tools-notices.txt"
file "$out/librootfs_e2fsck.so" "$out/librootfs_resize2fs.so"
sha256sum "$out/librootfs_e2fsck.so" "$out/librootfs_resize2fs.so"
