# Android crosvm/Venus local build status

2026-10-15, after the repaired stock app-private execution gate passed. No candidate
was installed, executed or overlaid on Android for this build. No system/APEX/policy
or permission change was made. The device retains only the ordinary main Release APK.

## Result

The bounded Cargo/NDK compilation route now produces an **Android arm64 executable
containing the Venus renderer**, not merely a proposed command. This is a compilation
baseline, not a deployable, validated guest GPU implementation.

- Local artifact: `.pi/device-debug/venus-crosvm-build/crosvm-venus-cargo-baseline`
- Size: 13,261,040 bytes
- SHA-256: `dc5abb38e26ecb310e609e47734116875e9bb4f21e929a25b6cb87ef8d93dbd6`
- ELF64/AArch64 PIE, `/system/bin/linker64`, every LOAD alignment 0x4000.
- DT_NEEDED: libprocessgroup.so, libcap.so, libc.so, libminijail.so, libdl.so, liblog.so.
  No RPATH/RUNPATH, dynamic Wayland/libffi, gfxstream, GBM or DRM library dependency.
- `llvm-nm` confirms linked `virgl_renderer_init`, `vkr_renderer_init`, `vkr_context_create`.
  Symbol presence proves code inclusion only; it is not renderer initialization/capset/GPU proof.
- Cargo completed the optimized Release build. Upstream unused-import/dead-code/Minijail
  ignored-return warnings remain recorded; no `cargo fix`, warning suppression or dummy
  native backend was used to make compilation succeed.

## Reproducible inputs and scope

| Input | Revision/version |
| --- | --- |
| crosvm, unchanged source | `c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8` |
| Rust toolchain and Android std | 1.88.0, as the checkout's rust-toolchain requests |
| Linux Android NDK | r30, Clang21, API37 |
| Minijail source headers/Rust binding | `26ff82bac9c77ef25d0a913ee09ed11ccdfdab78` |
| Renderer | `07a5bc398dfffdc36ad20a3476348753aa10a322`, existing tested teardown patch |
| Venus protocol | `ca19b6358d7cc491bc3e4de76f04c6700876a8fa` |
| Wayland 1.24.0 | `736d12ac67c20c60dc406dc49bb06be878501f86` |
| libffi 3.4.6 | `3d0ce1e6fcf19f853894862abcbac0ae78a7be60` |

**Important version distinction:** this first compilation deliberately retains crosvm's
unchanged Cargo.lock and registry `rutabaga_gfx`/`mesa3d_util` `0.1.76-alpha.0` checksums.
The separate Android17 rutabaga checkout `1f181b0b3e4b821459f21fd9025cd4c408c4f9ec`
declares 0.1.76 and has not yet been substituted. Do not describe this binary as built
from that local rutabaga revision. A controlled manifest/lock change and backend review
remain before candidate qualification; no broad dependency update was performed.

Device AVF libcap/libminijail/libprocessgroup were exported **read-only** as local linker
inputs, not replaced on-device or bundled in an APK. Their SHA-256 values are enforced
by the baseline script. Runtime availability and OEM ABI still need execution proof.
The static Android renderer links its real libvirglrenderer/libvirgl/libmesa closure;
its original generated pkg-config file is unsuitable because it points at /usr/local
and advertises unused libdrm. New target-only metadata describes the actual archives.

Host-only WSL prerequisites installed for the build: libclang-dev, libwayland-bin,
autoconf, automake, libtool and the Rust target/toolchain. This did not install any
Android system component, change device settings or start a large AOSP system build.

## Actual build feedback

1. `cargo tree --locked` initially failed because crosvm's `third_party/minijail` is a
   dangling `../../minijail` symlink. The pinned sibling checkout supplies it without
   modifying or deleting that symlink; dependency resolution then passed.
2. First cross build failed at rutabaga's virglrenderer pkg-config lookup. The controlled
   target-only renderer metadata resolved that gate without host-library search.
3. Second cross build found missing target `wayland-client.h`, even for headless GPU.
   Real Wayland/libffi archives were cross-built; their member ELF machines are AArch64.
4. Third build reached the final link and reported the six real libffi references that
   upstream gpu_display's lone `-lwayland-client` does not propagate for static linkage.
5. Explicit libffi then exposed `__clear_cache`; the NDK's actual compiler-rt archive
   supplies that builtin. With both genuine archives the final link passed.

Commands, with the pinned source checkouts and exported libraries already provisioned:

```sh
export ANDROID_NDK_HOME=/tmp/viruaisor-venus-host-20261015/android-ndk-r30
export VENUS_WORK_DIR=/tmp/viruaisor-venus-host-20261015
bash scripts/build-venus-crosvm-deps.sh
bash scripts/build-venus-crosvm-baseline.sh
```

The build scripts fail on source/library pin mismatch. Native pkg-config search is
restricted to the target prefix; host wayland-scanner remains a build-time generator.
Cargo feature set is `gpu,virgl_renderer,balloon`, not a full Android display/network/
composite-disk configuration. No Surface capability is claimed.

Logs are ignored under `.pi/device-debug/venus-crosvm-build/`:
`cargo-first-cross-build.log` through `cargo-fifth-cross-build.log`,
`native-target-deps.log`, `host-build-deps.log`. Build outputs remain in WSL `/tmp` and
the local ignored artifact directory; there is no device-side rollback for them.

## Remaining qualification gates

- Review/switch to pinned local Android rutabaga; select the embedded renderer server
  for Venus explicitly and reject failed initialization/zero capset rather than 2D fallback.
- Integrate Android DMA-heap allocation/import and resource FD sharing in the renderer,
  not just the separate host harness; establish the guest mapping/cache/fence contract.
- Carry the observed OEM libcrypto TLS lifetime safeguard into the actual VMM renderer
  lifecycle if needed, without changing system libraries or destructor semantics.
- Replay the OEM AVF argv/control protocol and loader/domain behavior with the exact
  candidate digest under a separately fixed app-private lease. The current root API
  still only accepts sentinel or byte-identical pinned stock, not arbitrary candidates.
- Exercise stock VM descendant owner-death/lease-timeout revocation before candidate
  deployment; app-side failure cleanup has been checked but is not that death test.
- Finally demonstrate capset4, real guest-visible memory mapping and input-dependent
  GPU compute/readback. Neither ELF link success nor the expected diskless panic
  fixture substitutes for those results.

The private-overlay execution route has passed its normal stock gate. Venus remains
an active integration task; this note does not claim guest acceleration works yet.
