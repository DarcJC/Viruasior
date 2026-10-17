#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK r30}
work=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}
prefix="$work/android-prefix"
gcc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
    "$repo/native/venus-display/frame_copy_test.c" -o /tmp/viruaisor-display-copy-test
/tmp/viruaisor-display-copy-test
gcc -std=c11 -Wall -Wextra -Werror \
    "$repo/native/venus-display/dmabuf_frame_policy_test.c" -o /tmp/viruaisor-dmabuf-frame-policy-test
/tmp/viruaisor-dmabuf-frame-policy-test
gcc -std=c11 -Wall -Wextra -Werror \
    "$repo/native/venus-display/dmabuf_sync_test.c" -o /tmp/viruaisor-dmabuf-sync-test
/tmp/viruaisor-dmabuf-sync-test
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang++"
"$compiler" -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -fno-exceptions -fno-rtti \
    -c "$repo/native/venus-display/android_display.cpp" -o "$prefix/venus_android_display.o"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" rcs "$prefix/lib/libvenus_android_display.a" "$prefix/venus_android_display.o"
echo "Android display client compiled; device presentation is not yet verified."
