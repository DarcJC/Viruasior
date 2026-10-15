#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_WORK_DIR:-"$repo/.pi/device-debug/venus-host-build"}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}
renderer="$work/virglrenderer"
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang"
trace_args=()
if [[ ${VENUS_TLS_TRACE:-0} == 1 ]]; then
    trace_args=(-DVENUS_TLS_TRACE -Wl,--export-dynamic)
fi
xxd -i -n probe_shader_spv "${VENUS_SHADER_SPV:?Compile native/venus-host/compute.comp to SPIR-V first}" |
    sed -e 's/^unsigned char/_Alignas(4) static const unsigned char/' \
        -e 's/^unsigned int/static const unsigned int/' > "$work/probe_shader.h"
"$compiler" -std=gnu11 -O2 -fPIC -Wall -Wextra -Wno-unused-parameter "${trace_args[@]}" \
    -I"$repo/native/venus-host" -I"$work/driver-generated" \
    -I"$renderer/subprojects/venus-protocol-1.1.3/include" -I"$renderer/src" -I"$work" \
    -I"$work/build-android/src" "$repo/native/venus-host/venus_host_probe.c" \
    -Wl,--start-group "$work/build-android/src/libvirglrenderer.a" \
    "$work/build-android/src/libvirgl.a" "$work/build-android/src/mesa/libmesa.a" \
    -Wl,--end-group -ldl -lm -llog -Wl,-z,max-page-size=16384 \
    -o "$work/venus-host-probe"
mkdir -p "$repo/app/src/main/jniLibs/arm64-v8a"
cp "$work/venus-host-probe" "$repo/app/src/main/jniLibs/arm64-v8a/libvenus_host_probe.so"
bash "$repo/scripts/generate-venus-notices.sh"
