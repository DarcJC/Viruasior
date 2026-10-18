# Touch, cursor, password and performance checkpoint

Validated on the M367FC tablet (Android 17, 3408×2272, Mali-G2-Ultra-NXMC16),
18–19 October 2026. This is an intermediate checkpoint: **1920×1200 at a stable
120 fps and native resolution at a stable 60 fps are not yet achieved**.

## Functional changes

- Rebuild touch events in local coordinates and refresh the boot ABS dimensions
  when attaching/updating the view. Five-point tests pass after native/1920 mode
  changes. EDID and default selection offer 1920×1280 at 120 Hz for this 3:2 panel;
  native 60 Hz and explicit 1920×1200 at 120 Hz remain available.
- Display the guest hardware cursor on a separate small BLAST surface, with a
  position parent and a bounded nonblocking position channel. Preserve the
  kernel-provided cursor origin. Hide the Android cursor while captured and
  reacquire capture after resolution changes. Guest cursor pixels pass at all
  three resolutions; the user confirmed that only one cursor is visible.
- Disable predictive Back dispatch for MainActivity. This keyboard maps physical
  Escape to Android BACK with scan code 1; the predictive Back stage intercepted
  it before the existing guest key mapper. Physical Escape now reaches guest
  evdev (three down/up pairs verified including an injected Escape) without
  leaving fullscreen. Ordinary Android Back still works.
- Add an App dialog for setting the fixed guest account `droid` password. Mask
  both entries, validate bounded UTF-8, keep credentials out of logs/persistence,
  authenticate the vsock peer, and use a fixed `chpasswd` invocation through stdin
  with a timeout. Tested real password changes and guest lock/unlock; the initial
  locked account state was restored once. There is no usable default password.

## Graphics correctness and measured improvements

- Correct the ParcelFileDescriptor presence marker in the consumer release-fence
  protocol. A device wire test reproduces the old missing-fence error and passes
  with the fix. Fences remain enabled throughout subsequent measurements.
- Bias the Android-only GPU BAR reservation when host/guest huge-page offsets
  match, preventing a pKVM stage-2 mapping fault on noncontiguous Mali PFNMAP
  pages. Alignment and bounds tests cover 4/16/64 KiB page sizes. Repeated desktop
  restarts and mode switches no longer reproduce the captured KVM_RUN failure.
- Limit small Venus/Zink uploads to cacheable dedicated buffers and smaller
  uploaders on the qualified Mali/Venus path. Retain buffer-object reuse.
- Make verbose allocation tracing opt-in and avoid diagnostic-only fd queries
  when disabled. Allocation/import failures remain visible. Normal drawing had
  generated approximately 9,500 graphics log lines in five seconds.
- At this checkpoint only Xorg and KWin select the two fastest guest CPUs;
  Plasma and applications retain their original affinity. This policy is being
  revised next to prefer the large-core tier and exclude the prime cores, per
  the user's power/thermal preference. No host scheduler or power policy is
  persisted; temporary per-VM thread affinity/uclamp experiments were reverted.

## Presentation measurements

The workload is a moving, multicolour 900×300 X11 window in the composited desktop.
Measurements use unique actual-present timestamps from the application's own
SurfaceFlinger layer, excluding timestamps preceding collection. Mode refresh
rate is not counted as frame rate. These results do not represent every workload.

| Configuration | Resolution | Duration | Actual fps | Interval p95 / p99 |
| --- | --- | ---: | ---: | ---: |
| Correct release fences, before quiet logging/CPU policy | 1920×1200 | 59.27 s | 59.51 | 25.00 / 25.00 ms |
| Quiet logging, default CPU scheduling | 1920×1200 | 29.46 s | 67.11 | 16.70 / 25.01 ms |
| Quiet logging + guest CPU wrapper | 1920×1200 | 60.06 s | 77.26 | 16.68 / 16.71 ms |
| Quiet logging + guest CPU wrapper | 3408×2272 | 59.90 s | 63.21 | 25.01 / 33.33 ms |

The native average exceeds 60, but its long frames still fail the stability
target. Profiling implicates the Xorg/KWin GLX/Venus presentation path; disabling
compositing reaches roughly 117 fps but is diagnostic only and is not shipped.
Forcing exact physical prime-core affinity made native performance worse and
was reverted. Uclamp experiments reached 84–87 fps at 1920 in short runs, still
below target, and were also reverted. Earlier measurements made before the
release-fence protocol correction are historical diagnostics, not strict A/B
baselines for this implementation.

## Verification and boundaries

Android unit tests, debug APK and instrumentation APK builds passed. Five device
tests cover touch/raw coordinates, cursor icon/channel and release-fence wire
format. Native policy tests (including sanitizer runs), GPU arena Rust tests,
guest command/password tests, CPU-policy tests and APK native-pin checks passed.
Native runtime SHA-256 at this checkpoint:
`c647a739fdf6d0b6da7e74bfe00acfd56456dc132cb9a06fc14633d85c871021`
(14,116,120 bytes); generated binaries and VM images are excluded from Git.

Root access and the existing Android grants were available; AVF booted the VM,
the Surface attached, and actual guest motion/cursor pixels were verified.
SELinux remains Enforcing. Host mounts, global power settings and system images
are not modified. Temporary guest diagnostics are bounded and are removed at
the end of the optimization session; the existing VM disk and user data are
preserved. Raw test artifacts are local under `.pi/device-debug/`.

## Large-core follow-up, 19 October

The guest wrapper now selects the large-core tier on a three-tier CPU topology,
excluding the prime tier. It uses the complete capacity topology even when an
inherited affinity mask excludes little cores; it never widens that mask and
preserves it if fewer than two suitable CPUs or valid capacity data are
available. Two-tier big.LITTLE systems select their big tier. On this tablet the
selected CPUs are 4–7. Four policy tests pass, including a failing test against
the preceding prime-core implementation. A real desktop restart confirmed Xorg
and KWin main-thread affinity 4–7 and Plasma affinity 0–9. Driver workers may
independently reset their affinity; no host CPU governor/frequency limit changes
are involved.

A 60-second trial after moving the existing Xorg/KWin threads to 4–7 measured
74.03 fps at 1920×1200 (p95 16.69 ms, p99 25.00 ms) and 62.28 fps at native
resolution (p95 25.01 ms, p99 33.34 ms). This is close to the preceding prime-core
trial but does not establish a measured energy saving or meet the stability
targets. The prepared bundle was rebuilt and copied to the App's import cache
after SHA-256 verification; it was **not imported over the existing VM disk**.

Further isolated diagnostics rejected native BGRA scanout on this driver:
AHardwareBuffer format 5 allocates, but reports `VK_FORMAT_UNDEFINED`, and an
external `VK_FORMAT_B8G8R8A8_UNORM` image query returns
`VK_ERROR_FORMAT_NOT_SUPPORTED`. The existing colour-correct RGBA path remains.
Temporary guest library timing separates a roughly 5.5–6.5 ms `glFlush` from a
roughly 0.2–0.3 ms subsequent Kopper flush/present. The wait is concentrated in
the submit worker's DMA-BUF synchronization, rather than Vulkan queue submission
itself. Disabling Gallium threading reduced the short-run rate to about 61 fps
and was reverted. These timing libraries are diagnostic-only, loaded from guest
`/tmp`; the shipped guest libraries remain unchanged.

The finer measurement attributes about 1.6–1.8 ms per synchronization export to
`vkGetSemaphoreFdKHR`; exporting the memory fd and importing its dependency take
only about 5 and 7 microseconds. An opt-in prototype exported a single shared
completion fence for all DMA-BUF resources in one GPU batch, preserving each
resource's dependency import. It reduced the measured flush wait but did not
produce a convincing presentation gain: 72.65 fps for KWin alone, and 75.01 fps
for Xorg and KWin together, each in a 60-second 1920×1200 run. It was not retained.
A trial binding only the VM renderer threads to host large cores also measured
73.27 fps and was reverted. A run spanning desktop startup and an unintended
native mode was excluded from comparisons.

Both the guest's original Gallium library and all six instrumented Mesa source
files were restored. Rebuilding the restored source reproduced the original
Gallium SHA-256 `a7aae0f85e853aa7c80f1202a5c642877061665e44c8ff9c0d16bfa8e40d0723`.
The guest runtime service override was removed, and a VM restart cleared the
temporary libraries. The current production CPU selection remains large cores
4–7; the unsuccessful fence and host-affinity experiments are not shipped.

Matching the App window to the native guest's 60 Hz was also rejected. The
physical display confirmed 60 Hz, but a 29.47-second sample fell to 25.11 fps
(p95 66.67 ms, p99 66.68 ms). The window-only 120 Hz request was restored; no
global display setting was changed. Guest native mode remains 60 Hz.

## Wayland diagnostic and external-image allocation repair

A temporary KWin Wayland session used guest `/tmp` configuration and a `/run`
service override, with a timed X11 recovery. The installed KWin binary's
`cap_sys_nice` file capability strips the library-path environment. A temporary
copy without that capability retained the paired Mesa runtime and reached DRM
initialization; neither the installed binary nor its permissions were changed.

Starting the moving Xwayland window then crashed crosvm PID 13581 inside Mali's
`BindImageMemory2`, with a null `android::RefBase::incStrong` receiver. The Android
image translation allowed an AHB external image to bind ordinary device-local
memory. The repaired renderer reports dedicated-allocation requirements for
translated images, allocates their AHB backing even without a host-visible or
export allocation request, and rejects non-AHB or nonzero-offset bindings before
calling the vendor driver. Both legacy and batched image binding are checked.
Because Venus enqueues binds asynchronously, rejection also marks that renderer
context fatal before later commands can access an unbound image.

The binding policy regression failed before the fix and passed afterward.
Native compilation, Android unit tests, debug APK build and packaged native-pin
verification passed. The new desktop runtime SHA-256 is
`7600b50a10e17c50452433a2c3783da2ca78e2fc4e934c3ed2f3ff894fa0714f`
(14,115,952 bytes). The same Wayland workload remained alive after the allocation
repair (before the final fatal-context refinement),
and translated bindings returned Vulkan success. Its actual displayed pixels
were black, so there is no valid Wayland frame-rate result and it is not enabled
in the production desktop. X11 was restored and its visible frame, hardware
renderer, large-core affinity and touch coordinates at all three resolutions
were verified. The native cursor matched all 58 sampled opaque interior pixels.

The guest-only `native/venus-crosvm/ahb_bind_guest_probe.c` exercises both paths
on the final APK. `dedicated` confirms that requirements demand a dedicated
allocation and that allocation/binding succeeds without export flags.
`reject-ordinary` deliberately ignores that requirement: the renderer logs
`VIRUAISOR_AHB_BIND_REJECTED`, the test process exits with status 134 after a
Venus fatal-context notification, and VM PID 22690 remains alive and displays
the desktop afterward. This test must not be run against the preceding renderer.

A post-Wayland X11 sample measured 64.97 fps at 1920×1200; after a full VM restart,
the same workload measured 72.26 fps over 59.48 seconds (p95 16.69 ms, p99
25.00 ms). A native run after resolution cycling measured 58.74 fps over 59.58
seconds. These are not improvements over the earlier large-core baseline.
A subsequent paired native comparison, restarting the VM for each APK and
setting the same 3408×2272@60 mode directly, measured 62.54 fps for the preceding
runtime (59.80 s) and 62.51 fps for the final repair (59.32 s). Both had p95/p99
intervals near 25 ms. No material regression appeared in this comparison; neither
meets stable 60 fps. This repair is a crash fix, not a claimed frame-rate gain.
