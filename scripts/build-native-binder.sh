#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_file="$root_dir/app/src/main/cpp/native_binder_lookup.c"
output_dir="$root_dir/app/src/main/jniLibs/arm64-v8a"
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$output_dir"

# The GNU cross toolchain has no Android sysroot. This link-only stub contributes the public
# libbinder_ndk.so DT_NEEDED entry and symbols; it is never packaged. Android resolves them from
# the platform's stable NDK library when System.loadLibrary loads libviruaisor_native.so.
cat > "$tmp_dir/libbinder_ndk_stub.c" <<'EOF'
typedef struct AIBinder AIBinder;
AIBinder *AServiceManager_waitForService(const char *instance) { (void)instance; return 0; }
void *AIBinder_toJavaBinder(void *env, AIBinder *binder) { (void)env; (void)binder; return 0; }
void AIBinder_decStrong(AIBinder *binder) { (void)binder; }
EOF

aarch64-linux-gnu-gcc \
  -fPIC -ffreestanding -fno-stack-protector -nostdlib -shared \
  -Wl,-soname,libbinder_ndk.so \
  -o "$tmp_dir/libbinder_ndk.so" \
  "$tmp_dir/libbinder_ndk_stub.c"

aarch64-linux-gnu-gcc \
  -fPIC \
  -fvisibility=hidden \
  -ffreestanding \
  -fno-stack-protector \
  -nostdlib \
  -shared \
  -Wl,-soname,libviruaisor_native.so \
  -Wl,-z,max-page-size=16384 \
  -Wl,-z,common-page-size=16384 \
  -Wl,--no-as-needed \
  -L"$tmp_dir" \
  -o "$output_dir/libviruaisor_native.so" \
  "$source_file" \
  -l:libbinder_ndk.so

file "$output_dir/libviruaisor_native.so"
aarch64-linux-gnu-readelf -d "$output_dir/libviruaisor_native.so" | grep 'NEEDED.*libbinder_ndk.so'
