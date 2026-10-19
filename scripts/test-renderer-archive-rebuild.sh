#!/usr/bin/env bash
# Exercise Cargo's real bundled-static-library behavior with the renderer build.rs.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
renderer_build=${1:-"$repo/.pi/device-debug/venus-crosvm-build/rutabaga/build.rs"}
test -f "$renderer_build"
work=$(mktemp -d /tmp/viruaisor-renderer-rebuild.XXXXXX)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/src" "$work/renderer/src" "$work/lib/pkgconfig"
cp "$renderer_build" "$work/renderer/build.rs"
cat > "$work/Cargo.toml" <<'EOF'
[package]
name = "display-fixture"
version = "0.0.0"
edition = "2021"
[dependencies]
renderer-fixture = { path = "renderer", features = ["virgl_renderer"] }
EOF
cat > "$work/renderer/Cargo.toml" <<'EOF'
[package]
name = "renderer-fixture"
version = "0.0.0"
edition = "2021"
[features]
virgl_renderer = []
[build-dependencies]
pkg-config = "0.3"
EOF
cat > "$work/renderer/src/lib.rs" <<'EOF'
extern "C" { fn renderer_value() -> i32; }
pub fn value() -> i32 { unsafe { renderer_value() } }
EOF
cat > "$work/src/main.rs" <<'EOF'
fn main() { println!("{}", renderer_fixture::value()); }
EOF
cat > "$work/lib/pkgconfig/virglrenderer.pc" <<EOF
Name: virglrenderer
Description: Static renderer rebuild regression fixture
Version: 1.3.0
Libs: -L$work/lib -lvirglrenderer
EOF
export CARGO_TARGET_DIR="$work/target"
export VIRGLRENDERER_STATIC=1
export PKG_CONFIG_PATH=
export PKG_CONFIG_LIBDIR="$work/lib/pkgconfig"
unset CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS
cd "$work"
for value in 1 2; do
    printf 'int renderer_value(void) { return %d; }\n' "$value" > renderer.c
    cc -c renderer.c -o renderer.o
    ar rcs lib/libvirglrenderer.a renderer.o
    digest=$(sha256sum lib/libvirglrenderer.a | cut -d ' ' -f1)
    cargo rustc --offline --release -- -C "metadata=$digest"
    actual=$(target/release/display-fixture)
    if [[ "$actual" != "$value" ]]; then
        echo "FAIL: changed renderer archive should return $value; binary returned $actual" >&2
        exit 1
    fi
done
echo 'PASS: a native-only archive edit reaches the final executable'
