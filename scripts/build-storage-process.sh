#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK r30}
cc -std=c11 -Wall -Wextra -Werror "$repo/native/storage/fs_process.c" \
    "$repo/native/storage/fs_process_test.c" -o /tmp/viruaisor-fs-process-test
/tmp/viruaisor-fs-process-test
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang" \
    -std=c11 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -shared \
    -Wl,-z,max-page-size=16384 -Wl,-soname,libviruaisor_storage.so \
    "$repo/native/storage/fs_process.c" "$repo/native/storage/fs_process_jni.c" \
    -o "$repo/app/src/main/jniLibs/arm64-v8a/libviruaisor_storage.so"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang" \
    -std=c11 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    "$repo/native/storage/fs_launcher.c" \
    -o "$repo/app/src/main/jniLibs/arm64-v8a/libviruaisor_storage_launcher.so"
