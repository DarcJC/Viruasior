# Chromium GPU color corruption

Chromium 122 on the Manjaro guest displayed red/purple rectangles over qq.com's
header and incorrect colors in GPU Canvas. The fix belongs to Mesa Venus/Zink;
no Chromium feature flags or user configuration are installed.

## Driver changes

- The Mali host exposes `VK_KHR_vertex_attribute_divisor`, with
  `supportsNonZeroFirstInstance=false`. The guest's older Venus protocol and Zink
  only recognized the EXT extension. ANGLE used instanced Skia paths, but their
  attribute divisors reached Gallium as zero. Add KHR protocol encoding, feature
  and property forwarding, and Zink support. Keep Gallium's `start_instance`
  capability disabled when the KHR implementation cannot support it.
- Non-coherent constant and threaded staging/index uploaders must publish CPU
  writes before queuing the GPU consumer. Preserve the coherent uploader path.
- Image readback must invalidate non-coherent CPU mappings after GPU completion.
  The linear image path previously flushed stale CPU data; the image-to-buffer
  staging path omitted invalidation.

The protocol change was generated from venus-protocol
`ca1e922094eded4a3790b35983461685c3237d9f`, adding just the KHR divisor extension
and its property structure to that registry. It retains wire format version 1
and requires the renderer to advertise extension 526. The paired renderer
already supports it. KHR adds a restriction reported by
[`supportsNonZeroFirstInstance`](https://registry.khronos.org/vulkan/specs/latest/man/html/VK_KHR_vertex_attribute_divisor.html);
it must not be silently aliased to EXT.

## Build and regressions

Run under WSL with the existing Manjaro cross sysroot:

```sh
bash scripts/build-manjaro-venus-icd.sh
bash scripts/build-manjaro-zink.sh
```

Both scripts apply the checked-in patches and build from the pinned Mesa 25.0.7
source. The Zink script also runs `test-zink-noncoherent-upload.py`, which compiles
the real constant, staging-copy, and index upload call sites against a memory
visibility fixture. It fails on the previous source.

On the guest, as the desktop user with the normal Venus/Zink environment:

```sh
python3 guest-gpu-instancing-probe.py
```

The probe checks instance divisors 1 and 2, three draws each, with 64 distinct
single-pixel reads per draw. The old driver fails because instanced attributes
are unavailable. Before the image-map fix, repeated reads returned stale pixels.

Capture each regression page with Chromium DevTools at device scale 1 and a
viewport of at least 1000 by 1000, then run:

```sh
node scripts/assert-chromium-colors.cjs colors.png
node scripts/assert-chromium-paths.cjs paths.png
```

Pages live in `guest/chromium/`. The color test covers 84 opaque, blended,
overlay, and background samples. The path test covers 13 stroke/background
samples; the previous driver missed all ten diagonal-path samples.

Device acceptance also requires opening qq.com with normal Chromium GPU
features, reloading and scrolling, then restarting the VM and checking again.
Keep binaries, screenshots, and traces in ignored `.pi/device-debug/`.

## Device acceptance and rollback (2026-10-21)

Both cross builds passed. After installing the libraries and restarting the VM,
normal Chromium displayed qq.com correctly on the Android Surface. Canvas,
compositing, rasterization, WebGL, and WebGL2 remained hardware accelerated.
The native probe passed 384 pixel checks; both page checks passed twice after
reload (84 color and 13 path samples per run).

The live guest libraries are `/opt/viruaisor/venus/libvulkan_virtio.so` and
`/opt/viruaisor/zink25/lib/libgallium-25.0.7.so`. Each original is retained beside
it with suffix `.before-20261021-chromium`. To undo the deployment, stop the guest
desktop, copy each backup to a sibling `.next` file with its original mode,
atomically rename it over the library, and restart the VM.

The VM booted and displayed actual guest frames. Existing Android root access
was used for the bounded vsock diagnostic connection; guest library installation
used the image's existing sudo grant. No Android permissions were added, and
SELinux remained Enforcing.
