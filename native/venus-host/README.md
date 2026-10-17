# Android Venus host validation worker

This is a bounded **host** protocol/compute validator, not a guest ICD or an AVF
GPU backend. The normal APK executes it from `nativeLibraryDir` under its own UID.
It never starts a root process, mounts a guest disk, or substitutes a software GPU.
The UI separately offers an isolated, diskless AVF backend check after host success.

Pinned dependencies:

- virglrenderer `07a5bc398dfffdc36ad20a3476348753aa10a322` (1.3.0).
- venus-protocol `ca19b6358d7cc491bc3e4de76f04c6700876a8fa` (1.1.3).
- Linux Android NDK r30, arm64/API 37, 16 KiB-compatible ELF alignment.

Build in Linux/WSL with Meson, Ninja, GCC, pkg-config, Python Mako and xxd installed:

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r30
export VENUS_WORK_DIR=/path/to/venus-build
bash scripts/build-venus-host.sh
glslangValidator -V --target-env vulkan1.1 -o "$VENUS_WORK_DIR/probe.spv" native/venus-host/compute.comp
export VENUS_SHADER_SPV="$VENUS_WORK_DIR/probe.spv"
bash scripts/compile-venus-host-probe.sh
```

On Windows, the SDK emulator's `lib64/vulkan/glslangValidator.exe` can compile the
shader before invoking the Linux build. Gradle packages the generated
`app/src/main/jniLibs/arm64-v8a/libvenus_host_probe.so`; it does **not** build it.
It is a PIE executable despite the packaging suffix, not a JNI shared library.
Preserve the existing Binder shim before building the APK.
The compile script also mechanically collects upstream license headers into a
generated APK notice asset; do not omit that asset when distributing the worker.

The build is Venus-only: no vrend, EGL, GLES, GBM or DRM renderer dependency.
NDK compatibility headers implement only the Android logging/read-only property
helpers referenced by the upstream Mesa utility code. The included upstream patch
keeps `object_mutex` alive until device objects have been destroyed; the original
order aborts under Bionic's destroyed-mutex checks.

## Tested memory path

The normal APK sets `VENUS_ALLOCATOR=dmaheap`. The worker allocates a bounded
page-aligned buffer from `/dev/dma_heap/system`, imports its FD into a renderer
resource, queries compatible Vulkan memory types through the Venus protocol,
imports it into Vulkan using `VkImportMemoryResourceInfoMESA`, then exports a
duplicate resource FD and maps that same storage. This validates external
allocation/import/resource-FD export, **not** Vulkan-driver DMA-BUF export.

This distinction matters: the tested Mali driver reports DMA-BUF IMPORTABLE only
and no OPAQUE_FD support. Unmodified renderer-owned HOST_VISIBLE allocation would
fall back to GBM, which is unavailable here. A full native virtio-gpu integration
still needs an Android-compatible renderer allocation/guest blob-sharing path.

DMA-BUF `START/END WRITE` surrounds CPU seeding, and `START/END READ` surrounds
readback after the GPU fence. The selected imported memory type is HOST_VISIBLE
and HOST_CACHED, not HOST_COHERENT. Omitting cache synchronization leaves unchanged
sentinels even when the Vulkan fence reports success.

The generated protocol encodes real Vulkan compute commands. Reply stream and
CPU timeline 0 are separate from the actual Vulkan GPU fence. All 16 arithmetic
outputs must match independently calculated values, and the child must exit 0.
The main app bounds the worker to 30 seconds and rejects partial, root-UID,
missing-word, unchanged-result and crashed runs.

`--repeat-contexts` reuses one renderer for context IDs 1, 2, 1 with three
different input vectors. Each must complete 16 real compute readbacks, followed
by global renderer cleanup and exit 0. On the device, this passed under ordinary
app UID 10278 while the display stayed asleep. Run
`node scripts/assert-venus-contexts.cjs <log> 0 <uid>` to classify the result.
This does not validate guest GL, WSI, Surface or input.

When reusing the paired AVF build workspace, use
`scripts/build-venus-context-probe.sh` to create a separate standalone renderer
profile. The AVF renderer requires an exported system linker namespace that a
standalone executable does not have; keep that AVF loader check strict.

`scripts/build-venus-vtest-diag.sh` builds a separate Venus-only Unix-socket
diagnostic server. Optional `--android-memory` and `--android-memory-filter`
profiles compare the Android allocator and conservative import compatibility
filter. They do not replace the packaged AVF backend. Manjaro userspace can run
from the owning debug app's private directory using its own glibc loader, without
root or chroot. `scripts/prepare-venus-linux-diag.py` stages its fixed dependency
closure and `scripts/venus-vtest-draw-probe.py` requires the app-owned socket,
explicit Venus/Zink selection and real Mali shader pixels. Its diagnostic
markers cannot satisfy the formal desktop acceptance script. Mesa 24 failed the
original FragCoord shader; the private Mesa 25 Zink comparison passes all three
actual Mali pixel samples after the memory compatibility filter. AVF desktop
qualification remains pending; see [device evidence](../../docs/manjaro-plasma-preparation.md).

## OEM thread teardown compatibility

The initial ordinary-app worker completed compute but exited 139 in
`pthread_key_clean_all`. With `VENUS_TLS_TRACE=1`, a pass-through key-registration
trace showed the exact faulting destructor belonged to `/system/lib64/libcrypto.so`.
It had been unloaded by thread teardown. Retaining this module with
`RTLD_NODELETE` for the **worker process lifetime** fixed three repeated Release
APK runs. It does not disable destructors, intercept them in the normal build,
change loader namespaces, replace system libraries, or alter SELinux.

This compatibility gate uses a system-private library and is device-specific;
failure to load it fails validation. It is not a portable production Android API.
Tracing is opt-in and is not compiled into the delivered worker.

## Verification

```sh
gcc -std=c11 -Wall -Wextra -Werror -Inative/venus-host \
  -I"$VENUS_WORK_DIR/virglrenderer/subprojects/venus-protocol-1.1.3/include" \
  native/venus-host/vn_cs_test.c -o "$VENUS_WORK_DIR/vn_cs_test"
"$VENUS_WORK_DIR/vn_cs_test"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Release signing is deliberately separate. The on-device Release verification used
the existing installation's developer certificate for a data-preserving update;
this is not a new production signing key or a Play release.
