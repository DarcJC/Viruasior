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
mutex_patch="$repo/native/venus-host/0001-destroy-object-mutex-after-objects.patch"
if git -C "$renderer_work/virglrenderer" apply --reverse --check "$mutex_patch" 2>/dev/null; then
    : # already applied exactly
else
    device_source="$renderer_work/virglrenderer/src/venus/vkr_device.c"
    object_line=$(grep -n 'if (!list_is_empty(&dev->objects))' "$device_source" | head -1 | cut -d: -f1)
    mutex_line=$(grep -n 'mtx_destroy(&dev->object_mutex)' "$device_source" | head -1 | cut -d: -f1)
    [[ -n "$object_line" && -n "$mutex_line" && "$mutex_line" -gt "$object_line" ]] || {
        echo 'object mutex must be destroyed after tracked device objects' >&2
        exit 1
    }
fi
if git -C "$renderer_work/virglrenderer" apply --reverse --check \
        "$repo/native/venus-crosvm/android-import-memory-properties.patch" 2>/dev/null; then
    [[ ${VENUS_CROSVM_IMPORT_FILTER:-0} == 1 && ${VENUS_CROSVM_DISPLAY:-0} == 1 ]] || {
        echo 'Refusing an implicit import-filter runtime; select the experimental desktop build explicitly.' >&2
        exit 1
    }
fi
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
    # The static renderer lives inside rutabaga's rlib, so final-crate metadata
    # alone cannot invalidate it when only native sources change.
    archive_patch="$repo/native/venus-crosvm/cargo-renderer-archive-dependencies.patch"
    git -C "$work/rutabaga" apply --reverse --check "$archive_patch" 2>/dev/null || \
        git -C "$work/rutabaga" apply "$archive_patch"
else
    grep -Fq 'rutabaga_gfx = "=0.1.76-alpha.0"' Cargo.toml
fi
# --locked: the candidate patch changes only the two local backend packages.
# Upstream gpu_display emits only -lwayland-client, not its static libffi dependency.
# Supply the real AArch64 archive at the final binary link, without rebuilding all crates.
compiler_rt=$("$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" --print-libgcc-file-name)
if [[ ${VENUS_CROSVM_DISPLAY:-0} == 1 ]]; then
    log_patch="$repo/native/venus-crosvm/android-renderer-logging.patch"
    git -C "$renderer_work/virglrenderer" apply --reverse --check "$log_patch" 2>/dev/null || \
        git -C "$renderer_work/virglrenderer" apply "$log_patch"
    ninja -C "$renderer_work/build-android"
fi
renderer_archive_sha=$(sha256sum "$renderer_work/build-android/src/libvirglrenderer.a" \
    "$renderer_work/build-android/src/libvirgl.a" "$renderer_work/build-android/src/mesa/libmesa.a" | cut -d ' ' -f1 | sha256sum | cut -d ' ' -f1)
[[ -f "$compiler_rt" ]] # libffi's AArch64 closure cache flush needs the NDK builtin.
trace_args=()
display_args=()
features=gpu,virgl_renderer,balloon
link_metadata="$renderer_archive_sha"
if [[ ${VENUS_CROSVM_DISPLAY:-0} == 1 ]]; then
    proto_patch="$repo/native/venus-crosvm/cargo-composite-protos.patch"
    git apply --reverse --check "$proto_patch" 2>/dev/null || git apply "$proto_patch"
    bash "$repo/scripts/build-venus-display.sh"
    # Cargo does not fingerprint archive contents passed through a fixed link-arg path.
    # Include these bytes in the final crate metadata so every display edit is relinked.
    link_metadata+="-$(sha256sum "$target_prefix/lib/libvenus_android_display.a" | cut -d ' ' -f1)"
    # AVF wraps partition images in its small protobuf composite-disk descriptor.
    # Without this feature the descriptor is silently treated as a tiny raw disk.
    features+=,android_display,net,composite-disk
    display_args=(-C "link-arg=$target_prefix/lib/libvenus_android_display.a"
        -C 'link-arg=-lbinder_ndk' -C 'link-arg=-lnativewindow' -C 'link-arg=-landroid' -C 'link-arg=-llog'
        -C "link-arg=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_static.a"
        -C "link-arg=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++abi.a")
fi
if [[ ${VENUS_CROSVM_TLS_TRACE:-0} == 1 ]]; then
    "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" -std=c11 -Wall -Wextra -Werror -fPIC \
        -c "$repo/native/venus-crosvm/venus_tls_trace.c" -o "$target_prefix/venus_tls_trace.o"
    trace_args=(-C "link-arg=$target_prefix/venus_tls_trace.o" -C 'link-arg=-Wl,--export-dynamic-symbol=pthread_key_create')
fi
cargo rustc --locked --release --target aarch64-linux-android --no-default-features \
    --features "$features" --bin crosvm -- -C "link-arg=$target_prefix/lib/libffi.a" \
    -C "link-arg=$compiler_rt" -C "metadata=$link_metadata" "${trace_args[@]}" "${display_args[@]}"
