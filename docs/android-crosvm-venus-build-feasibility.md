# Android arm64 crosvm/Venus build feasibility

Update 2026-10-15 14:04: the repaired private stock-execution gate passed and a real
Cargo/NDK compilation baseline has now linked successfully. See
[current build status](android-crosvm-venus-build-status.md) for exact inputs, the
registry-vs-local rutabaga distinction and remaining runtime/guest GPU gates.
The feasibility investigation below is historical, not the current build result.

Research date: 2026-10-15. Scope: source and local build-artifact inspection only.
No device operation, implementation patch, cross-compilation, Android source-tree
sync, APEX replacement or deployment was performed for this note. Downloaded sparse
source repositories are under ignored `.pi/device-debug/venus-host-research/`.

## Outcome

The most reproducible platform-compatible route is a **local Android/Soong module
build** of the pinned crosvm plus a Venus-enabled rutabaga and renderer. Building a
module does not require building or installing a new system image/APEX. The existing
NDK-built renderer can be reused as a reviewed static input, but **NDK + one Cargo
feature flag is not a complete Android AVF build recipe**.

A smaller Cargo/NDK headless build is technically plausible and useful after the
namespace/execution gate passes. It requires native dependency provisioning and some
Android build integration that the manifests do not supply automatically. It is an
unverified porting path, not a currently tested build or AVF-compatible executable.
Do not start a large AOSP checkout/build if the isolated overlay/execution gate fails;
that failure cannot be repaired by compiling more GPU code.

## Exact upstream snapshots

The Android `android-17.0.0_r1` annotated tags were resolved with `git ls-remote`:

| Component | Commit |
| --- | --- |
| crosvm | `c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8` |
| rutabaga_gfx | `1f181b0b3e4b821459f21fd9025cd4c408c4f9ec` |
| AVF configuration/control source | `22f1c9ee92b146e10c8f5e71338618f77ab1631e` |
| Existing virglrenderer | `07a5bc398dfffdc36ad20a3476348753aa10a322` |
| Existing venus-protocol | `ca19b6358d7cc491bc3e4de76f04c6700876a8fa` |

These are upstream reference revisions, not proof of the OEM firmware's exact source.
Keep its existing executable hashes and observed launch arguments as separate evidence.
Sources: [crosvm Android build][crosvm-bp], [crosvm Cargo manifest][crosvm-cargo],
[rutabaga Android build][rutabaga-bp], [rutabaga Cargo manifest][rutabaga-cargo].

## What actually enables Venus

Cargo's feature chain is:

```text
crosvm/virgl_renderer
    -> devices/virgl_renderer
       -> gpu + rutabaga_gfx/virgl_renderer
          -> native virglrenderer >= 1.0.0, found by pkg-config
```

Rutabaga's `set_use_vulkan` enables its Venus flag, while capset 4 selects its
VirglRenderer component. The stock Android.bp enables only `gfxstream`, which explains
the installed build rejection. `gpu` alone supplies a virtio GPU/display device and
does not enable accelerated Venus. Sources: [root features][crosvm-cargo],
[device features][devices-cargo], [rutabaga build script][rutabaga-build],
[backend selection][rutabaga-core].

Soong does not run Cargo's feature resolution/build.rs in the same way. A focused
Soong change must explicitly enable `virgl_renderer` in the relevant crosvm/device/
rutabaga modules and link the reviewed native renderer. The generated Android.bp files
contain their own feature/native-dependency lists. Also reconcile
`fence_passing_option1`: the stock rutabaga Android.bp sets it explicitly, whereas
Cargo build.rs stops setting it when `virgl_renderer` is enabled. Do not blindly keep
gfxstream-specific cfgs or dependencies in a Venus-only variant.
Sources: [crosvm module][crosvm-bp], [devices module][devices-bp],
[rutabaga module][rutabaga-bp], [rutabaga build.rs][rutabaga-build].

Enabling and linking the feature is necessary but still insufficient for the tested
renderer/device combination:

1. **Embedded server selection.** crosvm sets `use_render_server` only when an external
   server FD exists or `implicit-render-server` is true. That GPU option defaults to
   false; stock AVF does not expose either input. The current renderer requires its
   `RENDER_SERVER` flag even when built with embedded thread workers. A custom candidate
   needs an explicit, bounded Venus-only embedded-thread selection; it must not silently
   fork/exec an unavailable external server.
2. **Android allocation.** This Mali advertises DMA-BUF import but not the Vulkan
   driver-owned export path. The validated app worker allocates DMA-BUF from the system
   heap, imports it into renderer/Vulkan, and duplicates/maps that same resource. That
   logic is in the worker; simply linking the unchanged static renderer does not make
   arbitrary guest `vkAllocateMemory(HOST_VISIBLE)` use it. A renderer allocation/import
   integration is still required.
3. **Fail-closed backend.** Rutabaga currently falls back to 2D when VirglRenderer
   initialization fails. For an explicitly selected Venus profile this must be rejected
   or independently detected by a required nonzero Venus capset; successful VM startup
   must not become a false acceleration verdict.
4. **Guest mapping/cache contract.** The host path uses explicit DMA-BUF CPU cache
   synchronization with HOST_VISIBLE|HOST_CACHED memory. It has not established guest
   virtio/KVM cache maintenance or correct guest GPU readback.

Sources: [crosvm renderer flags][gpu-worker], [GPU defaults][gpu-params],
[renderer initialization][renderer-init], [rutabaga fallback][rutabaga-core],
[host validation](venus-host-validation.md).

## Native dependencies: headless and Android Surface are different scopes

| Dependency | Why it exists | NDK-only status |
| --- | --- | --- |
| Bionic, log, dl, m; optional AAudio | Runtime and Android logging/audio | Public NDK portions available; match selected features |
| libcap | `base` explicitly links cap_init/cap_free/cap_set_proc | Not supplied as an NDK platform library; cross-build/provide reviewed Android library |
| libminijail + generated constants/seccomp policies | Unconditional Linux/Android VMM dependency | Separate source/build input; disabling runtime sandbox does not remove the build dependency |
| libprocessgroup | Android task-profile application | Platform-private library, not an NDK API; preserve existing behavior/ABI |
| wayland-client, libffi, host wayland-scanner/protocols | gpu_display build script runs Wayland build for Android, including headless GPU builds | Cross-build target libraries and provide host generator, or make a reviewed build-only backend change |
| virglrenderer static archive | Actual Venus protocol renderer | Already built, with pinned teardown fix; allocation integration still missing |
| Android display client + generated Binder AIDL + nativewindow/binder_ndk/libcutils/libbase/libc++ | Android Surface presentation and internal AVF display service | AOSP-generated/platform inputs; not produced by enabling a Cargo flag |

Sources: [capability FFI][base-cap], [base Android module][base-bp],
[Minijail build][minijail-build], [jail policy build][jail-build],
[Android task profiles][processgroup], [gpu_display build.rs][display-build],
[gpu_display Android module][display-bp], [AVF display-client module][display-client].

The display implementation is not just a stable `ANativeWindow` wrapper. It uses
`libcrosvm_android_display_client` and generated internal AVF/display Binder AIDL. The
`android_display_stub` feature is for non-Android compile coverage; it is not a working
device renderer. For the first guest compute check, omit display configuration and do
not count a stub as presentation. Source: [Android GPU display FFI][display-android].

## Existing toolchain/archive audit

Local WSL has Rust `1.97.1`, but `rustup target list --installed` currently lists only
`x86_64-unknown-linux-gnu`. The renderer workspace contains Linux NDK r30 and these
static archives:

```text
/tmp/viruaisor-venus-host-20261015/build-android/src/libvirglrenderer.a
/tmp/viruaisor-venus-host-20261015/build-android/src/libvirgl.a
```

The former is about 4.48 MB and contains renderer/utility objects. A read-only
`llvm-nm -u` inspection found no unresolved `drm`/`gbm` symbols in that archive. The
generated `meson-private/virglrenderer.pc`, however, has `prefix=/usr/local` and
`Requires: libdrm >= 2.4.50`. It must **not** be handed unchanged to Cargo's cross-target
pkg-config: it could resolve host metadata/libraries. A controlled target-only prefix
and metadata must describe the actual Android archives and their audited dependencies.
Do not remove a dependency merely to make a failing link green; confirm the archive's
undefined-symbol closure and final ELF dependencies.

Rust supports `aarch64-linux-android` via the NDK, but that establishes the language
target, not crosvm's native dependency graph. Source: [Rust Android target][rust-android].

## Reproducible platform path and bounded Cargo alternative

**Platform path:** resolve an Android 17 manifest and an existing arm64 AVF-enabled
product/release configuration; record `repo manifest -r`, the product, compiler/toolchain
revisions and the exact source patch set. Use the tree's Soong/prebuilt toolchain, add
the reviewed Venus modules/dependencies, then build only the crosvm module with
`m crosvm`. An existing complete Android tree may need only targeted module work; the
current NDK workspace is not such a tree. No `m com.android.virt`, flashing, APEX install,
or system-file copy is required for producing the candidate artifact.
Source: [AOSP module build workflow][aosp-build]. The exact lunch product must be
selected from the pinned tree; inventing one is not a reproducible build recipe.

**Cargo alternative:** first provision the Android Rust standard library, a matching
target-only native prefix, the Minijail source tree and generators. Pin all Cargo
dependencies and map crosvm to the reviewed rutabaga checkout. This version detail
must be resolved explicitly: crosvm requests registry rutabaga
`=0.1.76-alpha.0`, while the AOSP rutabaga checkout declares `0.1.76`; an ordinary
`[patch.crates-io]` pointing at that different version does not satisfy the exact
requirement. A small reviewed manifest/lockfile change or the matching registry source
with reviewed Android changes is needed.
Sources: [crosvm manifest/patches][crosvm-cargo], [rutabaga version][rutabaga-cargo].

After those prerequisites and source integrations exist, the headless build shape is:

```sh
# Template, not a claim that the current unmodified source builds successfully.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android37-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_LIBDIR="$ANDROID_TARGET_PREFIX/lib/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR="$ANDROID_TARGET_SYSROOT"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
cargo build --locked --release --target aarch64-linux-android \
    --no-default-features --features gpu,virgl_renderer,balloon --bin crosvm
```

Headless features are only a starting point. Replay and validate the actual stock
virtmgr argument set and required disk format before reducing any other feature.
Composite disks, networking, audio, debugger, device assignment and Android display
need their corresponding features when used. The Cargo `all-android` set is not the
Android.bp set and does not itself include `gpu`/`android_display`.
Minijail's Cargo fallback assumes a compiler-prefix/build setup and regenerates bindings;
provide a target pkg-config library and correct bindgen target/sysroot rather than
letting it pick host libraries. Sources: [feature sets][crosvm-cargo],
[Minijail cross-build script][minijail-build], [jail generators][jail-build].

## Keeping stock virtmgr: compatibility requirements

Retaining unchanged stock virtmgr is architecturally possible: it spawns crosvm at a
fixed path and passes configuration/control FDs, rather than linking the VMM into
its process. The scoped overlay candidate would change only that path's view in
Viruaisor's dedicated namespace. This is a design possibility, not a demonstrated OEM
ABI guarantee. Source: [virtmgr spawn/control][avf-crosvm].

Preserve the following contracts before any GPU test:

- All arguments emitted by stock virtmgr, including `--extended-status`, inherited
  `--socket` FD, locking/unmap options and guest resource FD handling.
- The extended exit mapping: 0 normal stop, 32 reset, 33 crash, 34 guest panic,
  35 invalid arguments and 36 watchdog in the pinned crosvm. Stock virtmgr recognizes
  its corresponding normal/reset/crash/watchdog values and start-error status.
- Control-socket request/response schema and FD transport. The Unix Tube transport is
  JSON serialization plus SCM_RIGHTS; virtmgr's linked crosvm_control uses that schema
  for stop/suspend/balloon/display/memory operations. Do not swap in an arbitrary
  current upstream revision with changed requests.
- The expected pKVM/KVM UAPI, Android task profiles, VM naming, Android display Binder
  behavior when enabled, and the stock executable's dynamic-library search context.

Sources: [CLI/exit status][crosvm-main], [CLI configuration][crosvm-cli],
[control request types][control-types], [Tube transport][tube],
[crosvm_control implementation][control-api], [virtmgr platform checks][avf-crosvm].
The hard-coded AVF platform version `1.0.0` checks the requested VM configuration; it
does not cryptographically or dynamically negotiate a substituted executable's ABI.

Before deployment, audit ELF architecture, interpreter, page alignment, `DT_NEEDED`,
RPATH/RUNPATH and symbol versions; compare launch/control behavior against the unchanged
stock binary. The namespace/source-label/execution gate in
[the scoped-overlay research](avf-app-scoped-overlay-research.md) is independent and
must pass without new permissions/policy. A successful candidate must then demonstrate
capset 4, guest mapping, input-dependent compute/readback and clean shutdown. None of
those downstream gates is claimed by this build feasibility note.

[crosvm-bp]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/Android.bp
[crosvm-cargo]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/Cargo.toml
[devices-cargo]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/Cargo.toml
[devices-bp]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/Android.bp
[rutabaga-bp]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/Android.bp
[rutabaga-cargo]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/Cargo.toml
[rutabaga-build]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/build.rs
[rutabaga-core]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/src/rutabaga_core.rs
[gpu-worker]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/gpu/mod.rs
[gpu-params]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/gpu/parameters.rs
[renderer-init]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/virglrenderer.c
[base-cap]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/base/src/sys/linux/capabilities.rs
[base-bp]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/base/Android.bp
[minijail-build]: https://android.googlesource.com/platform/external/minijail/+/refs/tags/android-17.0.0_r1/rust/minijail-sys/build.rs
[jail-build]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/jail/build.rs
[processgroup]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/src/crosvm/sys/linux/android.rs
[display-build]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/gpu_display/build.rs
[display-bp]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/gpu_display/Android.bp
[display-android]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/gpu_display/src/gpu_display_android.rs
[display-client]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/android_display_backend/Android.bp
[rust-android]: https://doc.rust-lang.org/rustc/platform-support/android.html
[aosp-build]: https://source.android.com/docs/setup/build/building
[crosvm-main]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/src/main.rs
[crosvm-cli]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/src/crosvm/cmdline.rs
[control-types]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/vm_control/src/lib.rs
[tube]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/base/src/sys/unix/tube.rs
[control-api]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/crosvm_control/src/lib.rs
[avf-crosvm]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/crosvm.rs
