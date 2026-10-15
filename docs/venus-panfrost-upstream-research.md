# Venus and Panfrost/PanVK feasibility for the AVF guest

Research date: 2026-10-15. This note distinguishes upstream support, installed-host
capability, and demonstrated guest execution. It does not establish acceleration from a
backend name, an ICD file, a Vulkan device listing, or an attached Android Surface.

## Findings

Venus is a plausible alternative protocol for this Mali host, but it needs a Venus renderer
on the host. It is not an ICD that can talk to the existing gfxstream renderer. The pinned
Android 17 AOSP build enables only gfxstream in rutabaga. An isolated AVF startup on this
OEM binary also fails with `invalid rutabaga build parameters` when selecting Venus,
establishing an installed-host backend gate before any guest workload.

Panfrost/PanVK is a hardware-driver route. The current guest's virtual GPU does not become
a Mali GPU by installing that driver. Direct use would need an assigned physical GPU and
a matching guest DRM kernel driver. The observed Android host has proprietary `mali_kbase`
and `/dev/mali0`, rather than a Panthor/Panfrost DRM render device. No exact Mali-G2 support
claim follows from the current upstream hardware table.

These findings do not prove that gfxstream is intrinsically impossible on this hardware.
The prior failures establish that the tested installed stack has not passed execution,
presentation, and teardown gates; they do not isolate every failing component.

## Device facts and limits

Read-only observations supplied by the coordinating agent in this run:

- Xiaomi M367FC, Android 17/API 37, arm64; host Vulkan reports Mali-G2-Ultra-NX MC16,
  Vulkan 1.4, driver 56.0.0.
- `/sys/class/misc/mali0/device/gpuinfo` reports
  `Mali-G2-Ultra-NX 16 cores r0p1 0x0F080600`; the active GPU driver is `mali_kbase`.
- `/dev/mali0` exists. `/dev/dri/card0` belongs to the `xring_drm` display controller;
  no host DRM render node was found.
- VFIO platform infrastructure and `/dev/vfio/vfio` exist. The physical GPU has IOMMU
  group 7 but remains bound to its Android driver.
- The crosvm argument parser lists `2d`, `virglrenderer`, and `gfxstream`, and accepts a
  Venus context name. Backend initialization remains a distinct gate.
- Android `cmd gpu vkjson` reports all three documented Venus Android extensions, plus
  `VK_KHR_external_memory_fd`. This passes the extension-list gate, not the mapping gate.
- `/vendor/etc/avf/assignable_devices.xml` is absent, the VM DTBO index property is empty,
  and no VFIO group device node was found. The vendor assignment path is not provisioned.

Final native evidence is captured under
`.pi/device-debug/gpu-alternatives-2026-10-14T17-34-11-436Z/` (UTC in the directory name;
the actual device test was 2026-10-15 01:34 Singapore time). Read-only guest inventory is in
`.pi/device-debug/gpu-alternatives-2026-10-14T17-35-06-278Z/`.

The isolated instrumentation probe ran as application UID 10278 and created a temporary
256 MiB VM with one vCPU and the existing kernel, without an initrd, any disk, a network,
or an Android Surface. The effective host argument was:

```text
--gpu=backend=virglrenderer,context-types=venus,egl=true,gles=true,surfaceless=true,vulkan=true
```

The host `v_gpu` thread immediately panicked at `devices/src/virtio/gpu/mod.rs:1709`:

```text
Failed to create virtio gpu worker thread: invalid rutabaga build parameters
```

The same crosvm PID 24803 exited with SIGABRT. This matches the upstream build-time
VirglRenderer guard and proves that the installed stack cannot initialize this Venus
configuration. The test's `OK (1 test)` means evidence collection and cleanup completed;
it is not a Venus or GPU success. The temporary definition was deleted, no crosvm/helper
remained, and SELinux/hidden-API snapshots were identical. This test does not reach the
Mali memory-export gate and cannot establish whether a newly built Venus renderer would work.

The read-only image inventory independently confirms both `virtio_icd.json` pointing at
`libvulkan_virtio.so` and `panfrost_icd.json` pointing at `libvulkan_panfrost.so`, plus both
libraries. Guest packages are already present. The inventory used a read-only loop device
and `mount -o ro,noload`, with no filesystem journal replay, chroot, or image modifications;
it unmounted and detached the loop device afterward.

The reported proprietary Vulkan version is not translated into an Arm `rXXpY` release
number. The `gpuinfo` value is not assumed to use the same register encoding as a given
upstream DRM driver's `GPU_ID` field. These observations are not a successful Venus test
or a passthrough test. Runtime evidence and final probe outcomes belong in the accompanying
device-validation report.

## The three stacks are different

| Guest userspace | Host counterpart | Physical driver underneath |
| --- | --- | --- |
| gfxstream Vulkan, `libvulkan_gfxstream` | gfxstream Vulkan renderer, capset 3 | Android vendor Vulkan |
| Venus, `libvulkan_virtio` | virglrenderer built with Venus, capset 4 | Android vendor Vulkan, if its memory behavior is compatible |
| Panfrost OpenGL / PanVK Vulkan | A physical Mali DRM device, or a separately implemented compatible native-context transport | Panfrost/Panthor kernel DRM driver |

Rutabaga defines gfxstream Vulkan and Venus as different capsets and maps Venus to its
VirglRenderer component. Its selection code cannot manufacture an uncompiled component.
Sources: pinned [capset constants][rutabaga-utils] and [component selection][rutabaga-core].
The inspected Mesa source accepts only the `panfrost` and `panthor` DRM driver names in
[its kernel-device abstraction][mesa-kmod]; PanVK also checks these names before creating a
[physical device][mesa-panvk]. That source snapshot is a versioned API-contract example,
not evidence that it supports this device's exact GPU revision.

## Venus: what is required

Mesa documents Android host requirements of Vulkan 1.1 plus
`VK_EXT_external_memory_dma_buf`, `VK_EXT_image_drm_format_modifier`, and
`VK_EXT_queue_family_foreign`. Its tested host list includes proprietary Arm Mali r32p0+
and PanVK 25.1+. Matching advertised extensions is necessary but not sufficient: the
renderer relies on driver-specific host-visible memory export and mapping behavior.
The guest virtio path needs `3D_FEATURES`, `CAPSET_QUERY_FIX`, `RESOURCE_BLOB`,
`HOST_VISIBLE`, and `CONTEXT_INIT`. Mesa's native renderer build enables `-Dvenus=true`.
The documented local vtest test selects the `virtio_icd` and `VN_DEBUG=vtest`.
Source: [Mesa Venus requirements and setup][venus].

The existing guest library `libvulkan_virtio.so` is therefore useful, but it establishes
only guest userspace availability. Before changing a desktop profile, verify a nonzero
Venus capset, its compatible protocol version, negotiated virtio features, and working
host-visible memory. An unchanged sentinel after submit/wait is not a pass for memory
visibility or GPU execution.

### Android 17 host-build gap

The Android 17 AOSP rutabaga snapshot
`1f181b0b3e4b821459f21fd9025cd4c408c4f9ec` has only `gfxstream` in the
`librutabaga_gfx` feature list and links `libgfxstream_backend` on Android. It does not
enable `virgl_renderer`. In the same snapshot, selecting the VirglRenderer component
without that feature returns `InvalidRutabagaBuild`. This supports a concrete stock-AOSP
gap; OEM binary provenance must still be checked or tested.
Sources: [Android build definition][rutabaga-bp], [build-time backend guard][rutabaga-core].

AVF's pinned GPU argument builder forwards backend, context types, renderer flags, and
renderer features. It exposes neither `external-blob`/`system-blob` switches nor an external
GPU render-server path through this mapping. The inspected API 37 Java builder likewise
has no separate blob setters, as recorded in [the existing API audit](gfxstream-guest-research.md).
These are integration gaps, not proof that every Venus implementation requires both
particular crosvm blob switches. Source: [AVF `add_gpu_arg`][avf-gpu].

For a minimal native-backend check, merely opening a vhost-user GPU socket is insufficient:
the pinned crosvm backend initializes its GPU frontend when control queue 0 starts.
The frontend then builds rutabaga. A complete vhost-user setup or an isolated VM startup
is needed to reach this check. Source: [crosvm vhost-user GPU backend][vhost-gpu].

### Why an ordinary vtest socket bridge is not the missing transport

Mesa's inspected Venus vtest code uses an `AF_UNIX` socket, receives blob file descriptors
with `SCM_RIGHTS`, and maps the received descriptors. A byte relay over TCP/vsock cannot
transfer a host kernel file descriptor into the guest kernel or create the corresponding
shared mapping. A same-kernel Android vtest test can isolate the host renderer/driver, but
cannot by itself prove AVF guest acceleration. Cross-VM use needs the virtio resource and
mapping integration, or a new transport implementing its equivalent semantics.
Source: [Mesa Venus vtest implementation][mesa-vtest].

## Panfrost, PanVK, and Panthor

Mesa uses Panfrost for Mali OpenGL and PanVK for Vulkan. The current official supported
hardware table reaches Mali-G1 Pro/Premium/Ultra (architecture v14); it does not list the
observed Mali-G2-Ultra-NX. An experimental-enable environment variable cannot add a
missing kernel interface or an unsupported architecture. Source: [Mesa Panfrost support][panfrost].

Panthor is a Linux DRM kernel driver for CSF-based Mali GPUs; Panfrost handles older
job-manager devices. The inspected upstream Linux snapshot
`0fcb05b72344f40806cbe810de8872f1698bf053` matches architecture ranges 10–13 and 14 in
Panthor hardware binding, and requests architecture-specific CSF firmware. This is not
evidence for compatibility with the observed G2 ID. Firmware, register layout, power,
interrupts, IOMMU, and SoC integration all remain required.
Sources: [Panthor Kconfig][panthor-kconfig], [hardware binding][panthor-hw],
[firmware loading][panthor-fw].

Passing `/dev/mali0` through a filesystem mount would expose only a pathname, not the host
driver's ioctl implementation to a different kernel. Installing Mesa PanVK in the guest
cannot consume proprietary kbase UAPI through a virtio render node. This conclusion follows
from the inspected DRM driver checks and from the guest/host kernel boundary.

Physical assignment is a separate project. AVF documents `vfio-platform`, a vendor
assignable-device XML, and a bootloader-provided VM DTBO describing physical resources and
dependencies. Linux VFIO requires ownership of the relevant IOMMU group and driver binding;
the container node alone provides no usable assigned device. Sources:
[AVF device assignment][avf-assignment], [Linux VFIO ownership][vfio].

For this Android display GPU, unbinding the active host driver is not a minimal availability
check and can interrupt Android graphics. No such operation is part of this research.

## Minimal decision gates and next route

1. **Installed Venus renderer: failed.** The isolated AVF probe reaches the native GPU
   worker and rejects the rutabaga build before guest execution. No production Venus
   profile was added, and no software fallback is counted as acceleration.
2. **Host Vulkan memory:** check the documented Android extensions and exercise dma-buf
   export/map with the actual vendor driver. A bounded headless Venus test on Android can
   isolate this before a desktop or VM transport is involved.
3. **Guest Venus transport:** query capset 4, protocol version, virtio parameters, and a
   real host-visible mapping. Then run a bounded fill/compute or draw with validated
   readback, explicit Venus ICD selection, and no software fallback.
4. **Presentation and lifecycle:** only after execution passes, test Zink/EGL, compositor
   frames, Android presentation, and clean shutdown independently.
5. **Panfrost/PanVK gate:** require both a supported exact GPU identity and an actual
   compatible DRM render device. The present kbase/display-only-node inventory fails the
   kernel-interface gate. Physical assignment also requires the vendor/DTBO/reset/power
   integration; an IOMMU group is only one prerequisite.

The practical priority is Venus with a host renderer built and integrated for this Android
Vulkan driver. If the installed AVF binary lacks it, this requires host-side engineering
beyond selecting a different guest ICD: a custom AVF/crosvm/rutabaga build, or a separate
VM/renderer integration. Keep a host-only renderer test separate from guest success. A
Panfrost/PanVK detour currently adds GPU support, kernel, and assignment work before it can
reach the same execution test, so it is not the smaller next experiment.

## Reproduce the availability checks

The checked-in instrumentation probe is opt-in (`gpuBackendProbe=venus`) and skipped by
ordinary instrumentation runs. It must be run only while the project's normal VM is stopped.
Its successful JUnit result means collection/cleanup succeeded; use the native log verdict.

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
node scripts/probe-gpu-alternatives.cjs --venus-backend --guest-inventory
```

Approve the test APK's normal device installer when prompted. The script writes captures
beneath ignored `.pi/device-debug/`, records policy before/after, and classifies the actual
native launch and same-PID SIGABRT as `blocked-host-renderer`. The read-only guest inventory
requires the existing root authorization. It never changes driver bindings, permissions,
the normal manifest or guest files. The generated APK is a test-only package; it can be
removed with `adb uninstall pro.darc.viruaisor.test` without removing the main application.

[venus]: https://docs.mesa3d.org/drivers/venus.html
[panfrost]: https://docs.mesa3d.org/drivers/panfrost.html
[rutabaga-bp]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/Android.bp
[rutabaga-utils]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/src/rutabaga_utils.rs
[rutabaga-core]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/src/rutabaga_core.rs
[avf-gpu]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/crosvm.rs
[vhost-gpu]: https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/vhost_user_backend/gpu.rs
[mesa-vtest]: https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/virtio/vulkan/vn_renderer_vtest.c
[mesa-kmod]: https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/panfrost/lib/kmod/pan_kmod.c
[mesa-panvk]: https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/panfrost/vulkan/panvk_physical_device.c
[panthor-kconfig]: https://github.com/torvalds/linux/blob/0fcb05b72344f40806cbe810de8872f1698bf053/drivers/gpu/drm/panthor/Kconfig
[panthor-hw]: https://github.com/torvalds/linux/blob/0fcb05b72344f40806cbe810de8872f1698bf053/drivers/gpu/drm/panthor/panthor_hw.c
[panthor-fw]: https://github.com/torvalds/linux/blob/0fcb05b72344f40806cbe810de8872f1698bf053/drivers/gpu/drm/panthor/panthor_fw.c
[avf-assignment]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/docs/device_assignment.md
[vfio]: https://docs.kernel.org/driver-api/vfio.html
