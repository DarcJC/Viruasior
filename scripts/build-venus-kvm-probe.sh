#!/usr/bin/env bash
# Build a bounded Android arm64 KVM diagnostic; never installs or runs it.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_KVM_PROBE_DIR:-"$repo/.pi/device-debug/venus-kvm-probe"}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK with Linux tools}
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$work"
"$bin/aarch64-linux-android35-clang" -c "$repo/native/venus-kvm-probe/guest.S" -o "$work/guest.o"
"$bin/llvm-objcopy" -O binary --only-section=.text "$work/guest.o" "$work/guest.bin"
xxd -i -n kvm_memory_guest "$work/guest.bin" > "$work/guest.h"
"$bin/aarch64-linux-android35-clang" -O2 -Wall -Wextra -Werror \
    -I"$work" "$repo/native/venus-kvm-probe/main.c" -o "$work/venus-kvm-probe"
printf 'Built %s/venus-kvm-probe\n' "$work"
