#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}
out="$repo/app/src/main/jniLibs/arm64-v8a/libvenus_overlay_probe.so"
mkdir -p "$(dirname "$out")"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang" \
    -std=gnu11 -O2 -Wall -Wextra -Werror -fPIE -pie -Wl,-z,max-page-size=16384 \
    "$repo/native/venus-overlay/overlay_probe.c" -o "$out"
