# Venus host validation and remaining AVF boundary — 2026-10-15

Historical host/stock-AVF result. The subsequent app-private candidate now has
guest compute and explicit-cache evidence; see [2026-10-16 guest validation](venus-guest-runtime-validation.md).
The original system crosvm remains unchanged and retains the limitation below.

## Outcome

The Android arm64 Venus host renderer is built and its memory/compute gate passes
in the ordinary, **non-debuggable Release APK** under UID 10278. Three consecutive
final runs exported/mapped the same imported DMA-BUF storage, submitted real Venus
protocol compute commands to Mali, read back all 16 correct results, and exited 0.

**End-to-end AVF guest GPU acceleration is not complete.** The same Release APK
then selected the native Venus backend on an isolated diskless AVF VM. The installed
crosvm rejected its rutabaga build parameters and aborted before guest boot.

## Device and build

- Xiaomi yingtian/M367FC, Android 17/API 37; final crash evidence identifies
  `OS4.0.15.0.XBMCNXM`, kernel `6.18.21-android17-5-g284297031c53-abogki553682993-4k`.
- Mali-G2-Ultra-NX MC16, ARM vendor `0x13b5`, Vulkan API 1.4.345.
- Pinned renderer/protocol/NDK and build instructions:
  [native worker README](../native/venus-host/README.md).
- Both the Venus worker and existing Binder shim were present in the signed APK.
- Existing certificate SHA-256:
  `82c1caf021cab5f539ed3db1c9ce1e53d56e1866deed357670bb2b598e647634`.
  Release update used that developer certificate; it is not production-key signing.
- Package flags had no `DEBUGGABLE`; existing MANAGE_VIRTUAL_MACHINE and
  USE_CUSTOM_VIRTUAL_MACHINE grants were retained. Main app/VM data were preserved.

## What actually passed

1. Venus capset 4, context creation, reply blob mapping and bounded CPU fences.
2. Protocol instance/device/queue creation and identification of the actual Mali GPU.
3. External allocation from `/dev/dma_heap/system`, renderer FD import, protocol
   memory-resource compatibility query, Vulkan memory import and buffer binding.
4. Renderer resource-FD export/duplication and shared mapping of 4096 bytes.
5. Descriptor/pipeline/command-buffer construction, input-dependent compute dispatch,
   shader-write/host-read barrier, successful finite Vulkan GPU fence wait.
6. Correct 16/16 readback, complete normal renderer cleanup and child exit 0.

The ordinary-app allocation uses spoofed renderer memory type 15, flags 11
(DEVICE_LOCAL, HOST_VISIBLE, HOST_CACHED). DMA-BUF cache synchronization is explicit.
Driver-level external-buffer features are DMA-BUF=4 (IMPORTABLE), export-from-imported=0,
OPAQUE_FD=0. Therefore **driver-owned Vulkan FD export is unsupported for the tested
STORAGE_BUFFER | TRANSFER_DST buffer/handle combinations**; this does not establish
the capability of every image format or other usage combination.
The working Android allocation/import/resource-export path is not evidence otherwise.
The earlier UID 0 udmabuf run queried the same buffer combinations and returned the
same capability values, so root did not add native Vulkan FD export in that test.

The earlier root-only udmabuf path also passed, but it is not the delivered app path.
Shell udmabuf access had an exact SELinux denial; no rule was added. The normal APK
does not use udmabuf or root for host validation.

## Bugs found and reproduced

- Without DMA-BUF cache synchronization, Vulkan success/fence completion was followed
  by unchanged sentinel words. Adding START/END CPU access synchronization fixed it.
- Upstream renderer destroyed `object_mutex` before destroying tracked objects.
  Bionic aborted on the subsequent lock. The pinned build applies the small ordering patch.
- Ordinary-app teardown initially crashed in `pthread_key_clean_all` after correct GPU
  results. Pass-through TLS-key instrumentation recorded a libcrypto destructor at the
  exact subsequent fault address. Process-lifetime libcrypto retention fixed the final
  three runs. Pinning only libvulkan did not fix it; directly pinning Mali was inaccessible
  to the app linker namespace and is not in the final build. No namespace/policy change
  or suppression of thread destructors was used.

## AVF attempt and why it stops here

At 02:05:41 SGT, the Release APK's UID 10278 created a temporary 256 MiB VM with
only the imported kernel. No disks, initrd, networking or Surface were attached.
Effective crosvm launch contained:

```text
--gpu=backend=virglrenderer,context-types=venus,egl=true,gles=true,surfaceless=true,vulkan=true
```

The exact native result was:

```text
28805 E crosvm: Failed to create virtio gpu worker thread: invalid rutabaga build parameters
virtmgr: crosvm(28805) exited with status signal: 6 (SIGABRT)
VENUS_RUN_RETURNED status=0
VENUS_PROBE_END definitionDeleted=true; no GPU execution claimed
```

The capture classifier independently reports `blocked-host-renderer`. A returned
`run()` and green app wrapper are not backend success or guest acceleration.
The temporary definition was deleted and no probe crosvm remained.

Stock AVF has no API to supply this bundled renderer/server FD to its GPU device.
The separate `addMemoryToGuest` engineering path is also gated: this device's
`hypervisor.memory_share.supported` property is absent (AOSP default false).
Changing a property would not implement the required hypervisor support.
See [pinned upstream analysis](venus-host-build-research.md) for primary-source links.

Continuing native AVF Venus requires a compatible AVF/crosvm/rutabaga build with
virgl_renderer/Venus enabled, Android memory allocation/import integration, and
validated guest blob mapping/fences. Replacing the system AVF/APEX or installing a
persistent root overlay requires separate explicit approval under AGENT.md. None
was done. Guest Vulkan/desktop frames are **not** claimed.

## Tests and evidence

- `:app:testDebugUnitTest`: 64 tests, including five fail-closed host-report tests.
- `:app:assembleDebug` and `:app:assembleRelease`: successful.
- Native encoder/decoder bounds/padding/handle tests: successful.
- Node regression tests: 44/44 successful, including the AVF backend classifier.
- Final artifacts/captures are ignored under `.pi/device-debug/venus-host/`:
  `Viruaisor-venus-release.apk`, `host-release-final.log`,
  `host-release-final-logcat.log`, `host-release-tls.log`,
  `avf-release-backend.log` and UI XML snapshots.

The instrumentation package `pro.darc.viruaisor.test` was uninstalled before work.
Only the normal main app is retained. SELinux stayed Enforcing throughout; no
system-image, policy, hidden-API setting or persistent module was changed.
The ADB-only native probe executable and temporary UI hierarchy file were also
removed from `/data/local/tmp`; their local captures/build outputs are retained.
