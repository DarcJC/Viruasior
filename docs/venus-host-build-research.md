# Android Venus host renderer: build and transport requirements

Research date: 2026-10-15. This note describes the upstream implementation contract.
Actual device outcomes are recorded separately; compiling a library, creating a context,
or receiving a fence does not establish guest GPU acceleration.

## Pinned renderer and Android build

The inspected upstream virglrenderer revision is
`07a5bc398dfffdc36ad20a3476348753aa10a322` (Meson project version `1.3.0`). Its
Venus dependency is exactly protocol version `1.1.3`, whose `v1.1.3` tag resolves to
`ca19b6358d7cc491bc3e4de76f04c6700876a8fa`. Pin both revisions in the build rather
than following `main` or assuming the old AOSP virglrenderer options are current.
Sources: [renderer build][renderer-meson], [options][renderer-options],
[protocol wrap][renderer-wrap], [protocol build][protocol-meson].

For a headless renderer embedded in an arm64 Android APK, the minimal current options
are:

```text
-Dvenus=true
-Dvrend=false
-Dplatforms=[]
-Ddrm-renderers=[]
-Dvideo=false
-Dtests=false
-Dfuzzer=false
-Dvulkan-dload=true
-Dvulkan-preload=false
-Drender-server-mode=thread
-Drender-server-worker=thread
-Ddefault_library=static
-Db_staticpic=true
```

Use an NDK cross file with `system = 'android'`, `cpu_family = 'aarch64'`, little
endianness, and the desired Android API clang target. Host Python requires Mako for the
protocol generator. Upstream bundles the matching Vulkan headers, currently
`VK_HEADER_VERSION 357`, so the generator and renderer should use those headers together.
Do not replace them with an older NDK Vulkan header set. The GL-free configuration
does not require epoxy, GBM or a physical DRM render node. These dependency claims follow
from the selected options in [Meson][renderer-meson] and
[source composition][renderer-src-meson].

`thread` server mode includes the server and worker implementation in the renderer
library; it avoids an external `virgl_render_server` executable and `fork`/`exec` from
the Android application. Static PIC linkage into the application JNI library also avoids
upstream's versioned shared-library SONAME (`libvirglrenderer.so.1`) becoming an APK
packaging dependency. The Vulkan loader is opened dynamically, trying
`libvulkan.so.1` and then Android's `libvulkan.so`.
Sources: [source composition][renderer-src-meson], [loader][renderer-library].

The normal upstream `tests=true` path requires `vrend` and EGL and exercises the VirGL
renderer. Similarly the upstream vtest executable is only built with `vrend` enabled.
Neither is an appropriate dependency for this minimal GL-free Android host proof.
The protocol repository has serialization compilation tests, but those do not run a
GPU workload. Sources: [renderer build][renderer-meson],
[renderer tests][renderer-tests], [protocol tests][protocol-tests].

## Minimal actual Venus protocol workload

The host probe should call the exported renderer API and encode commands with the
generated `vn_protocol_driver_*.h` helpers. A small encoder/decoder adapter can implement
the hooks in the protocol's `vn_cs.h` and `vn_ring.h` contract. A direct Vulkan-only
workload can check the vendor driver's external memory prerequisite, but it bypasses
Venus decoding and cannot establish that the new renderer works.
Sources: [driver encoder contract][protocol-cs],
[driver command generation][protocol-commands], [renderer API][renderer-api].

Initialize the renderer using callbacks version 3 or 4, including
`write_context_fence`, and these flags:

```text
VIRGL_RENDERER_VENUS
| VIRGL_RENDERER_NO_VIRGL
| VIRGL_RENDERER_RENDER_SERVER
| VIRGL_RENDERER_THREAD_SYNC
| VIRGL_RENDERER_ASYNC_FENCE_CB
| VIRGL_RENDERER_USE_EXTERNAL_BLOB
```

`RENDER_SERVER` remains required with the embedded thread server: the public initializer
selects the Venus proxy from that flag. A Venus context uses capset ID 4. Query and record
the capset fields before the workload, including wire format and extension versions.
Sources: [initializer][renderer-core], [capset][renderer-capset].

The exact upstream Mesa `25.0.7` tag resolves to
`742a20f48c59e8649533c84c4d49dd95b403f5da`. Its generated driver metadata uses
wire format `1`, Vulkan XML `1.3.269`, and `VK_MESA_venus_protocol` specification
version `2`. The pinned new renderer uses the same wire format, with newer XML and
private-extension versions. Mesa's instance initialization requires an exact wire-format
match and clamps newer XML/private-extension versions down to its own supported versions.
This provides source-level negotiation compatibility, not an execution result for the
installed Debian build. The runtime capset and extension-mask negotiation must still be
verified. Sources: [Mesa 25.0.7 protocol metadata][mesa25-info],
[Mesa 25.0.7 negotiation][mesa25-instance].

The sequential reply setup does not need a Venus command ring:

1. Create the Venus context and a reply blob using `HOST3D`, `blob_id=0`, a nonzero
   resource handle, and **exactly** `USE_MAPPABLE` as its blob flags. This particular
   combination makes a shared-memory allocation rather than looking up a Vulkan memory
   object. Adding `USE_SHAREABLE` to this special blob changes that dispatch.
2. Call `virgl_renderer_resource_map` on the reply resource. Submit
   `vkSetReplyCommandStreamMESA` with `VkCommandStreamDescriptionMESA` containing its
   `resourceId`, `offset=0`, and bounded `size`.
3. Before each reply command, encode `vkSeekReplyCommandStreamMESA(0)`, followed by
   the actual command with `VK_COMMAND_GENERATE_REPLY_BIT_EXT`.
4. Submit the encoded buffer, then create a renderer context fence on `ring_idx=0`.
   Wait with a deadline for the matching `write_context_fence` callback before decoding
   the mapped reply. CPU timeline 0 retires after preceding context requests are
   processed. It is not itself a GPU-completion fence.

The proxy's socket preserves request order; its shared atomic timeline and eventfd drive
the fence callback. A disconnected worker can force fence retirement, so the callback
alone is never a success verdict: verify the reply command tag, bounds, return code and
workload bytes. Sources: [blob special case][renderer-context],
[reply stream dispatch][renderer-transport], [proxy ordering/fences][renderer-proxy],
[protocol transport definitions][protocol-transport].

Use unique nonzero wire object IDs for output handles, including the instance, enumerated
physical-device array, device, queue, buffer, memory, command buffer, and fence. Query
physical-device properties, queue families, memory properties and buffer memory
requirements through the renderer. It can split a host memory type into guest-visible
types and shift their indices; a host-native memory type index is not interchangeable.
Sources: [physical-device handling][renderer-physical],
[object decoder][renderer-cs], [device creation][renderer-device].

For queue initialization use `vkGetDeviceQueue2`, with a
`VkDeviceQueueTimelineInfoMESA` in `VkDeviceQueueInfo2.pNext`, and a nonzero `ringIdx`
(for example 1). The old `vkGetDeviceQueue` protocol command is deliberately rejected
by the current renderer. Retrieve each queue only once. Queue timeline 1 can then
provide real GPU context fences; timeline 0 is reserved for decoder completion.
Source: [queue initialization][renderer-queue].

## Export, map and correct readback

Start with a memory type that has both `HOST_VISIBLE` and `HOST_COHERENT`, and that is
allowed by the renderer-reported buffer `memoryTypeBits`. Allocate a page-aligned size,
bind the buffer, and initialize every checked word to a sentinel that differs from all
expected GPU output values.

Use ordinary `VkMemoryAllocateInfo` with a unique memory object ID. A
`VkMemoryOpaqueCaptureAddressAllocateInfo` chain is unnecessary for this test. The
renderer automatically makes host-visible allocations external. It prefers dma-buf
export when the host advertises compatible export properties, otherwise it can use an
opaque Vulkan FD. Create a second `HOST3D | USE_MAPPABLE` resource whose `blob_id` is
the Vulkan memory object's **wire ID**, not a Vulkan opaque capture address. A given
device memory can be exported as a blob only once.
Source: [allocation and export implementation][renderer-memory].

Call `virgl_renderer_resource_export_blob` to record the actual FD type, check the FD
and allocation size, and call `virgl_renderer_resource_map`. A dma-buf resource uses
`mmap(..., MAP_SHARED, fd, 0)`. An opaque FD is imported by the renderer allocator and
mapped with Vulkan; an opaque FD is not necessarily mmapable. Record which path passed.
Sources: [resource mapping][renderer-core], [opaque allocator][renderer-allocator].

Submit a transfer or compute workload through the generated Venus protocol. Include
the corresponding transfer/shader write to host-read memory dependency. Use a finite
`vkWaitForFences` timeout and a reply command, then wait for its CPU context fence and
decode the Vulkan result before checking **all** output words. Two different
deterministic patterns or input-dependent compute results help distinguish real
execution from a stale buffer, unchanged sentinel or unconditional success code.
Source for the required availability/visibility rules:
[Vulkan memory synchronization][vulkan-memory].

Do not send `vkMapMemory`, `vkUnmapMemory`, `vkFlushMappedMemoryRanges` or
`vkInvalidateMappedMemoryRanges` through the current renderer protocol: those dispatch
entries are explicitly null. Mapping belongs to the blob transport. A noncoherent
dma-buf path additionally needs the applicable transport/cache synchronization; it must
not be declared working solely because fence completion returned success.
Sources: [memory dispatch][renderer-memory], [Mesa Venus mapping assumptions][venus].

Mesa documents that its host-visible memory path intentionally relies on driver
behavior beyond the external-memory Vulkan contract. Advertised extensions therefore
do not establish the export/map/readback gate. A same-kernel Android renderer test also
does not prove KVM can install and coherently expose the mapping to another kernel.
Source: [Mesa Venus requirements and mapping assumptions][venus].

## AVF integration boundary

The already recorded installed-host result is still the first stock AVF gate:
`backend=virglrenderer,context-types=venus` fails with
`invalid rutabaga build parameters`. The pinned Android build only enables gfxstream
in rutabaga. Adding a renderer library to the application does not change that compiled
backend selection. Sources: [prior runtime evidence](venus-panfrost-upstream-research.md),
[rutabaga Android build][rutabaga-bp], [backend build guard][rutabaga-core].

The pinned AVF GPU AIDL/configuration forwards backend, context types, renderer features,
PCI address and boolean flags. It has no renderer-server path/FD or external vhost-user
GPU configuration. The normal native Venus integration therefore requires an
AVF/crosvm/rutabaga build with the backend enabled and its blob transport connected;
the current normal APK cannot select a bundled external renderer through this API.
Sources: [GPU AIDL][avf-gpu-aidl], [raw config][avf-raw],
[crosvm argument mapping][avf-crosvm].

There is a possible separate engineering path, not an established replacement:
`IVirtualMachine.addMemoryToGuest(fd, offset, rangeStart, rangeEnd, cacheable)` can
register a host FD in guest IPA space. It is gated by the hypervisor's dynamic zero-copy
memory-sharing capability, read from `hypervisor.memory_share.supported` with a default
of `false`; the next read-only check is `adb shell getprop hypervisor.memory_share.supported`.
Changing that property cannot add a missing hypervisor implementation.
The Binder contract requires guest relinquishment before
removal. The inspected Java `VirtualMachine` wrapper does not expose these methods.
A custom Venus transport would still need guest-side allocation/mapping/lifecycle,
command and fence transport, and exact installed Binder compatibility; a byte-only
vsock relay does not transfer host kernel FDs. This path should be assessed explicitly
after the host memory test, without claiming native virtio-gpu Venus has been integrated.
Sources: [VM Binder memory API][avf-vm-aidl], [capability gate][avf-vm],
[hypervisor property][avf-hypervisor],
[Java wrapper][avf-java], [Mesa vtest FD transport][mesa-vtest].

This research does not authorize system-image replacement, persistent policy changes,
or a custom privileged VMM. The repository's conservative-root rule requires separate
explicit approval for system-image or persistent root-module changes.

[venus]: https://docs.mesa3d.org/drivers/venus.html
[renderer-meson]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/meson.build
[renderer-options]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/meson_options.txt
[renderer-wrap]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/subprojects/venus-protocol.wrap
[renderer-src-meson]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/meson.build
[renderer-tests]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/tests/meson.build
[renderer-library]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_library.c
[renderer-api]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/virglrenderer.h
[renderer-core]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/virglrenderer.c
[renderer-capset]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_renderer.c
[renderer-context]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_context.c
[renderer-transport]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_transport.c
[renderer-proxy]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/proxy/proxy_context.c
[renderer-physical]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_physical_device.c
[renderer-device]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_device.c
[renderer-cs]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_cs.h
[renderer-queue]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_queue.c
[renderer-memory]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_device_memory.c
[renderer-allocator]: https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/07a5bc398dfffdc36ad20a3476348753aa10a322/src/venus/vkr_allocator.c
[protocol-meson]: https://gitlab.freedesktop.org/virgl/venus-protocol/-/blob/ca19b6358d7cc491bc3e4de76f04c6700876a8fa/meson.build
[protocol-tests]: https://gitlab.freedesktop.org/virgl/venus-protocol/-/blob/ca19b6358d7cc491bc3e4de76f04c6700876a8fa/tests/meson.build
[protocol-cs]: https://gitlab.freedesktop.org/virgl/venus-protocol/-/blob/ca19b6358d7cc491bc3e4de76f04c6700876a8fa/templates/driver_cs.h
[protocol-commands]: https://gitlab.freedesktop.org/virgl/venus-protocol/-/blob/ca19b6358d7cc491bc3e4de76f04c6700876a8fa/templates/driver_commands.h
[protocol-transport]: https://gitlab.freedesktop.org/virgl/venus-protocol/-/blob/ca19b6358d7cc491bc3e4de76f04c6700876a8fa/xmls/VK_MESA_venus_protocol.xml
[vulkan-memory]: https://docs.vulkan.org/spec/latest/chapters/synchronization.html
[rutabaga-bp]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/Android.bp
[rutabaga-core]: https://android.googlesource.com/platform/external/rust/rutabaga_gfx/+/1f181b0b3e4b821459f21fd9025cd4c408c4f9ec/src/rutabaga_core.rs
[avf-gpu-aidl]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtualizationservice/aidl/android/system/virtualizationservice/GpuConfig.aidl
[avf-raw]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtualizationservice/aidl/android/system/virtualizationservice/VirtualMachineRawConfig.aidl
[avf-crosvm]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/crosvm.rs
[avf-vm-aidl]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtualizationservice/aidl/android/system/virtualizationservice/IVirtualMachine.aidl
[avf-vm]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/virtualmachine.rs
[avf-java]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachine.java
[avf-hypervisor]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/hypervisor_props/src/lib.rs
[mesa-vtest]: https://android.googlesource.com/platform/external/mesa3d/+/875160ed32cc5bc32777fa04870a0a57ed38c987/src/virtio/vulkan/vn_renderer_vtest.c
[mesa25-info]: https://gitlab.freedesktop.org/mesa/mesa/-/blob/742a20f48c59e8649533c84c4d49dd95b403f5da/src/virtio/venus-protocol/vn_protocol_driver_info.h
[mesa25-instance]: https://gitlab.freedesktop.org/mesa/mesa/-/blob/742a20f48c59e8649533c84c4d49dd95b403f5da/src/virtio/vulkan/vn_instance.c
