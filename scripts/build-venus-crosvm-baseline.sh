#!/usr/bin/env bash
# Local compilation ONLY. Does not install or overlay an artifact.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}
renderer_work=${VENUS_WORK_DIR:?Set VENUS_WORK_DIR to the validated Android renderer workspace}
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Linux NDK r30}
target_prefix="$work/android-prefix"
[[ $(git -C "$work/crosvm" rev-parse HEAD) == c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8 ]]
[[ $(git -C "$work/minijail" rev-parse HEAD) == 26ff82bac9c77ef25d0a913ee09ed11ccdfdab78 ]]
[[ $(git -C "$renderer_work/virglrenderer" rev-parse HEAD) == 07a5bc398dfffdc36ad20a3476348753aa10a322 ]]
git -C "$renderer_work/virglrenderer" apply --reverse --check "$repo/native/venus-host/0001-destroy-object-mutex-after-objects.patch"
mkdir -p "$target_prefix/lib/pkgconfig"
sed -e "s|@RENDERER_WORK@|$renderer_work|g" "$repo/native/venus-crosvm/virglrenderer.pc.in" > "$target_prefix/lib/pkgconfig/virglrenderer.pc"
sed -e "s|@TARGET_PREFIX@|$target_prefix|g" -e "s|@MINIJAIL_SOURCE@|$work/minijail|g" \
    "$repo/native/venus-crosvm/libminijail.pc.in" > "$target_prefix/lib/pkgconfig/libminijail.pc"
# These are linker inputs read from this device's unchanged AVF APEX, never deployment payloads.
[[ $(sha256sum "$target_prefix/lib/libcap.so" | cut -d ' ' -f1) == 360be4457dbd8b60d926bc7a5ed20e53758b1cee5033621386f20f506443f992 ]]
[[ $(sha256sum "$target_prefix/lib/libminijail.so" | cut -d ' ' -f1) == 57acbe4d4a2510bec0116e6c144a0feebebbfa05e67f758e27d873069a7ae405 ]]
[[ $(sha256sum "$target_prefix/lib/libprocessgroup.so" | cut -d ' ' -f1) == 173187af93613003ca400aaf1ea8df89bf1c0cc5c734e949726914d9cb6d8fa5 ]]
export CARGO_TARGET_DIR=${VENUS_CROSVM_TARGET_DIR:-/tmp/viruaisor-crosvm-venus-target}
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_PATH=
export PKG_CONFIG_LIBDIR="$target_prefix/lib/pkgconfig"
export MINIJAIL_BINDGEN_TARGET=aarch64-linux-android37
export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
export VIRGLRENDERER_STATIC=1
export CFLAGS_aarch64_linux_android="-I$target_prefix/include"
export LIBCLANG_PATH=${LIBCLANG_PATH:-/usr/lib/llvm-21/lib}
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-L native=$target_prefix/lib -C link-arg=-Wl,-z,max-page-size=16384"
cd "$work/crosvm"
if [[ ${VENUS_CROSVM_CANDIDATE:-0} == 1 ]]; then
    grep -Fq 'rutabaga_gfx = { path = "../rutabaga", version = "=0.1.76" }' Cargo.toml
else
    grep -Fq 'rutabaga_gfx = "=0.1.76-alpha.0"' Cargo.toml
fi
# --locked: the candidate patch changes only the two local backend packages.
# Upstream gpu_display emits only -lwayland-client, not its static libffi dependency.
# Supply the real AArch64 archive at the final binary link, without rebuilding all crates.
compiler_rt=$("$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" --print-libgcc-file-name)
renderer_archive_sha=$(sha256sum "$renderer_work/build-android/src/libvirglrenderer.a" \
    "$renderer_work/build-android/src/libvirgl.a" "$renderer_work/build-android/src/mesa/libmesa.a" | cut -d ' ' -f1 | sha256sum | cut -d ' ' -f1)
[[ -f "$compiler_rt" ]] # libffi's AArch64 closure cache flush needs the NDK builtin.
trace_args=()
if [[ ${VENUS_CROSVM_TLS_TRACE:-0} == 1 ]]; then
    "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" -std=c11 -Wall -Wextra -Werror -fPIC \
        -c "$repo/native/venus-crosvm/venus_tls_trace.c" -o "$target_prefix/venus_tls_trace.o"
    trace_args=(-C "link-arg=$target_prefix/venus_tls_trace.o" -C 'link-arg=-Wl,--export-dynamic-symbol=pthread_key_create')
fi
cargo rustc --locked --release --target aarch64-linux-android --no-default-features \
    --features gpu,virgl_renderer,balloon --bin crosvm -- -C "link-arg=$target_prefix/lib/libffi.a" \
    -C "link-arg=$compiler_rt" -C "metadata=$renderer_archive_sha" "${trace_args[@]}"
