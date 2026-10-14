# Android 17 AVF gfxstream: Linux guest requirements

## Scope and source limits

Research only: no adb, device/settings access, app/guest implementation inspection, or runtime tests. Root display-helper ownership is outside scope and is not redesigned here. Device observations below are supplied by the user, not independently measured.

The installed SDK uses `sources/android-37.0`, not `sources/android-37`. The initial source sweep
left Java signatures unverified. A subsequent direct read of the installed
`android/system/virtualmachine/VirtualMachineCustomImageConfig.java` confirms the builder contract
below. SDK source behavior is still not proof of the OEM device's runtime API or GPU execution.

Official `android17-release` branches were resolved to these immutable source snapshots:

- AVF: `22f1c9ee92b146e10c8f5e71338618f77ab1631e`.
- crosvm: `c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8`.
- gfxstream host: `b46f0d4b4545def09bdf30651d4bf848b2ba0c34`.
- AOSP Mesa mirror: `875160ed32cc5bc32777fa04870a0a57ed38c987`, whose `VERSION` is **26.1.0-devel**, not the guest's established Mesa version. Initial research did not know the guest Mesa build. The subsequent device diagnostic found
Debian Mesa `25.0.7-2+deb13u1`, including `gfxstream_vk_icd.json`; its full build options and the
device AVF/APEX revisions remain unknown. These snapshots establish source behavior, not exact binary provenance. Upstream Mesa GitLab returned an anti-bot page; fetching the official Mesa 25.0.7 archive did not complete. No unversioned-main source is used to establish requirements.

## 1. AVF flags versus effective crosvm defaults

In pinned AVF [`android/virtmgr/src/crosvm.rs`, `add_gpu_arg`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/crosvm.rs):

| GPU configuration field | crosvm argument emitted |
| --- | --- |
| `backend` when present | `backend=<value>` |
| `contextTypes` when present | `context-types=<colon-separated non-null entries>` |
| `pciAddress` when present | `pci-address=<value>` |
| `rendererFeatures` when present | `renderer-features=<value>` |
| `rendererUseEgl` true | `egl=true` |
| `rendererUseGles` true | `gles=true` |
| `rendererUseGlx` true | `glx=true` |
| `rendererUseSurfaceless` true | `surfaceless=true` |
| **`rendererUseVulkan` true** | **`vulkan=true`** |

False booleans are **omitted**, not serialized as `...=false`. This routine does not forward an `external-blob` or `system-blob` field. That establishes a limitation of this mapping, not proof that every API37 Java surface lacks such setters. `renderer-features` is renderer-specific passthrough, not a verified substitute for the separate crosvm blob switches. Builds without `paravirtualized_devices` ignore GPU configuration with a warning.

Pinned crosvm [`GpuParameters::default`](https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/gpu/parameters.rs) initializes EGL/GLES/surfaceless to true, GLX to false, `use_vulkan=None`, `capset_mask=0`, and `external_blob=false`, `system_blob=false`, `fixed_blob_mapping=false`. [`devices/src/virtio/gpu/mod.rs`](https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/gpu/mod.rs) passes `use_vulkan.unwrap_or_default()` into the renderer: absent Vulkan becomes false in this path.

**Installed API 37.0 Java contract:** `VirtualMachineCustomImageConfig.GpuConfig.Builder` exposes
`setBackend(String)`, `setContextTypes(String[])`, `setPciAddress(String)`,
`setRendererFeatures(String)`, and boxed-`Boolean` setters `setRendererUseEgl`,
`setRendererUseGles`, `setRendererUseGlx`, `setRendererUseSurfaceless`, and
**`setRendererUseVulkan(Boolean)`**. Its Java defaults are EGL/GLES/surfaceless true,
GLX/Vulkan false, with the string/array fields null. `toParcelable()` copies those values to the
service fields directly. This installed class exposes no separate blob-switch setter.
Source: `C:/Users/i/AppData/Local/Android/Sdk/sources/android-37.0/android/system/virtualmachine/VirtualMachineCustomImageConfig.java`,
`GpuConfig` and `GpuConfig.Builder`, lines 1040–1257.

**Immediate constraint:** choosing backend `gfxstream` alone does not request Vulkan. The Java
builder setter for service field `rendererUseVulkan` is `setRendererUseVulkan(Boolean)`. Enabling EGL/GLES is not equivalent to enabling Vulkan. The user-reported command lacks `vulkan=true`, and the reported “Vulkan disabled” and blob-disabled logs are consistent with these source defaults.

### Capsets and blobs are separate concerns

The crosvm parameter source explicitly maps **`gfxstream-vulkan` to capset ID 3** in its serialization test. `context-types` controls a capset mask, not whether the host Vulkan renderer is enabled. With mask zero, the device source reports one default capset for gfxstream, versus zero for 2D. **Zero mask does not mean zero capsets.** Exact enumeration of that default capset and the full installed capset set still need runtime/source-version confirmation.

The device source advertises `VIRTIO_GPU_F_RESOURCE_BLOB` even in its base feature set. Thus **virtio resource-blob support is not identical to `external-blob` or `system-blob` being enabled**. The latter are host resource/memory-handling controls; do not declare both universally mandatory merely because they appear in logs. [`virtio_gpu.rs`, `resource_map_blob`](https://android.googlesource.com/platform/external/crosvm/+/c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8/devices/src/virtio/gpu/virtio_gpu.rs) documents external-blob handling in connection with sandboxing and opaque-FD mapping. This research has not established an API37-supported way to set either switch, or the precise requirement for this device's Linux gfxstream memory/scanout path.

## 2. Matching Linux userspace to the host protocol

| Guest path | Required counterpart / distinguishing evidence |
| --- | --- |
| **gfxstream Vulkan guest ICD** | gfxstream Vulkan transport/capset, not Venus. The pinned Mesa mirror builds `libvulkan_gfxstream` and a `gfxstream_vk_icd.<manifest suffix>` JSON. Its Vulkan device code requests `kCapsetGfxStreamVulkan`. Installed arm64 naming/build availability must be checked rather than assuming Debian ships it. |
| **Mesa Venus** | Separate Vulkan serialization protocol, rendered by virglrenderer. Mesa calls its library `libvulkan_virtio` and generates `virtio_icd.<manifest suffix>`. A generic virtio-gpu node or a gfxstream backend does not establish a compatible Venus renderer/capset. |
| **Mesa virgl** | Gallium/OpenGL virtualization implemented by host virglrenderer. It is not interchangeable with a gfxstream GLES decoder merely because both use virtio-gpu. |
| **Mesa Zink** | OpenGL-to-Vulkan translation layer. It can sit above a functioning gfxstream Vulkan ICD, subject to that version's Vulkan features and external-memory/presentation support. “Zink” alone does not identify a hardware renderer. |
| **Lavapipe / llvmpipe** | CPU software rendering. A Vulkan ICD pointing at Lavapipe does not virtualize the host hardware GPU. |

Sources: pinned Mesa [`gfxstream Vulkan meson.build`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/gfxstream/guest/vulkan/meson.build), [`gfxstream_vk_device.cpp`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/gfxstream/guest/vulkan/gfxstream_vk_device.cpp), [`Venus documentation`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/docs/drivers/venus.rst), [`Venus build`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/virtio/vulkan/meson.build), [`virgl`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/docs/drivers/virgl.rst), [`Zink`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/docs/drivers/zink.rst). Gfxstream's pinned [design notes](https://android.googlesource.com/platform/hardware/google/gfxstream/+/b46f0d4b4545def09bdf30651d4bf848b2ba0c34/docs/design.md) explicitly describe Linux guests and virtio-gpu transport.

Venus documentation requires kernel-driver parameters `3D_FEATURES`, `CAPSET_QUERY_FIX`, `RESOURCE_BLOB`, `HOST_VISIBLE`, and `CONTEXT_INIT`. These are **Venus-specific documented requirements**, not automatically a complete gfxstream checklist. Kernel 6.12.92 and a loaded `virtio_gpu` module alone do not prove negotiated device features or compatible userspace.

The old environment is a software-oriented configuration: Mesa documents `LIBGL_ALWAYS_SOFTWARE=true` as forcing software rendering and `MESA_LOADER_DRIVER_OVERRIDE=zink` as selecting the driver binary. `VK_ICD_FILENAMES=.../lvp_icd.json` directs Vulkan loading toward Lavapipe; Mesa documents that variable as deprecated in favor of `VK_DRIVER_FILES`. A hardware test must not accidentally retain a software-only ICD selection. See pinned [`envvars.rst`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/docs/envvars.rst).

The reported GPU2d `drmPrimeFDToHandle EINVAL` establishes an import failure, not its root cause. 2D advertises no default 3D capsets; forcing Zink/Lavapipe does not add a virtual hardware GPU. Rendering success and dma-buf/scanout compatibility remain distinct.

## 3. What counts as acceleration proof

Recommended evidence criteria, **not tests executed by this research**:

1. Record effective host arguments/flags and actual guest capsets/features, then identify the loaded arm64 ICD and shared library. A backend label or successful initialization is insufficient.
2. Record Vulkan physical-device identity/type and driver properties for the **selected workload device**, not merely every enumerated device. Pinned [`lvp_device.c`](https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/gallium/frontends/lavapipe/lvp_device.c) reports Lavapipe as `VK_PHYSICAL_DEVICE_TYPE_CPU`, driver ID `VK_DRIVER_ID_MESA_LLVMPIPE`, and driver name `llvmpipe`. Treat those as explicit software evidence. A vendor-like name/non-CPU type alone is not definitive proof through a virtualization layer.
3. Correlate a real guest rendering workload with the host's selected vendor hardware Vulkan driver and GPU execution/counters or a GPU trace. Exclude a software host backend as well as guest Lavapipe. FPS and lower guest CPU usage alone cannot establish hardware execution.
4. Separately confirm the compositor's actual GL/EGL renderer and visible guest frames. A Vulkan probe can pass while Weston uses Pixman/software GL or fails buffer presentation. Zink's underlying Vulkan device matters more than the word “Zink”. Surface attachment, renderer initialization, Vulkan rendering, compositor rendering, and presented guest frames are different milestones.

## Unresolved before implementation

- Follow-up device execution now confirms the SDK-verified Vulkan setter took effect: actual
  crosvm arguments include `vulkan=true` and the native log says `Vulkan: enabled`. See
  [runtime validation](gfxstream-validation.md) for the separate resource errors and remaining
  guest access/execution/presentation gates. The installed SDK builder has no blob setters.
- Device AVF/crosvm/gfxstream revision and full capset enumeration.
- Guest Mesa 25.0.7 and its gfxstream ICD manifest are now confirmed by runtime inspection;
  the actual shared-library load, driver build details and hardware execution remain unverified.
- Version-specific gfxstream blob/host-visible memory and dma-buf requirements on this Android host.
- Host vendor hardware execution and accelerated Weston presentation.

No claim of hardware acceleration is made. No permission, SELinux, shell-policy, or root-display change is recommended.
