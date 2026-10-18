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

## Rejected queue-completion cache experiment (2026-10-19)

A candidate cached each Vulkan queue's completed work epoch. Successful guest
`QueueSubmit`, `QueueSubmit2` and `QueueBindSparse` advanced the epoch; display
synchronization reused completion only after draining the guest command rings,
with no pending fence and no newer work. Timeout/error paths retained their
pending fences. The intent was to avoid empty fence submissions for idle queues,
without removing synchronization for active queues.

The idle-queue regression failed before implementation, then passed alongside
the existing fence-lifetime tests, including AddressSanitizer/UndefinedBehaviorSanitizer.
The native build, Android unit tests, debug APK build and packaged artifact
verification passed. The trial runtime was 14,117,016 bytes with SHA-256
`cf5888e61a04e3b10502e7e22b7d73995f67bfd0815607cb13180aaddc7fe77f`.
Actual moving colour-window pixels and both sampled corner regions passed.

The candidate and then the baseline APK each received a fresh VM start, followed
by the same native-then-1920 mode sequence and fullscreen motion workload.
Each mode received a nominal 60-second capture of unique presentation timestamps
from the App's own SurfaceFlinger layer, excluding timestamps before capture.
Xorg and KWin used large cores 4–7 in both runs; neither prime-core pinning nor
host system changes were used.

| Mode | Baseline fps | Candidate fps | Baseline p95 / p99 | Candidate p95 / p99 |
| --- | ---: | ---: | ---: | ---: |
| 3408×2272@60 | 62.53 | 62.57 | 25.00 / 25.01 ms | 24.99 / 25.01 ms |
| 1920×1200@120 | 72.70 | 71.58 | 16.69 / 25.00 ms | 16.69 / 24.99 ms |

Native samples covered 59.62 seconds (baseline) and 59.27 seconds (candidate);
1920 samples covered 59.64 and 59.25 seconds respectively. Intervals over 20 ms
were 225/3728 versus 211/3709 at native resolution and 60/4336 versus 58/4241
at 1920. Neither the average nor the long-frame distribution establishes a
useful improvement. A preceding independent baseline native sample also measured
62.51 fps. These results still do not meet stable 120 fps at 1920 or stable
60 fps at native resolution; the collector's generic `passed` flag is not a
120-fps acceptance check.

The queue cache, its build integration, tests and artifact-pin changes were
reverted. The external renderer source was also restored, and the known-good
`7600b50a10e17c50452433a2c3783da2ca78e2fc4e934c3ed2f3ff894fa0714f`
runtime was restored in both the installed APK and local packaged artifacts.
Only this experiment record is retained. Raw captures and the rejected candidate
remain private under `.pi/device-debug/`, outside version control.

After restoration, Android unit tests, debug build and packaged native-pin
verification passed again. The 1920×1280 mode filled the 3408×2272 fullscreen
Surface without letterboxing. SELinux remained Enforcing and the existing
host hidden-API settings were unchanged. The two temporary guest probe hooks
and their Python files were removed without modifying the droid account.

## Wayland image-size regression (2026-10-19)

An isolated Wayland session displayed a native shared-memory colour window with
hardware KWin composition. A GPU-backed version instead exited with
`invalid buffer stride of height for plane 0`. KWin's
[DMA-BUF bounds check](https://github.com/KDE/kwin/blob/Plasma/5.27/src/wayland/linuxdmabufv1clientbuffer.cpp)
requires the advertised offset plus stride times height to fit the exported fd.
The renderer synthesized the layout with 64-byte row alignment but synthesized
memory requirements from tightly packed pixels, producing contradictory sizes.

The guest Vulkan regression reproduced the problem directly: a 900×600 image
reported stride 3648 and needed 2,188,800 bytes, but its memory requirement and
exported fd were only 2,162,688 bytes. The aligned 1920×1200 control passed.
The repaired calculation includes row padding, layout size/offset and array-layer
spacing, preserves larger driver requirements and alignment, and checks overflow.
After repair, both memory-requirement APIs and the exported fd report 2,191,360
bytes for 900×600. Guest checks at 901×2272, 1920×1200 and 3408×2272 also pass.

Native size/overflow tests with AddressSanitizer and UndefinedBehaviorSanitizer,
native compilation, Android unit tests, APK build and artifact verification pass.
The runtime is 14,116,096 bytes, SHA-256
`81d45ebb8a55a5f212be3a76ce3da1a78dc3b68f0d5f718f27b22844a0bfae5b`.
The native GPU Wayland colour window now stays alive and displays correctly;
the existing X11 desktop also displays correctly. Temporary Wayland testing
retains large-core affinity 4–7 and uses no host system changes.

The Plasma shell starts on Wayland, but its Xwayland motion window initially
remained invisible. Protocol tracing showed a separate Zink export failure
before any window buffer was attached: an ordinary shared pixmap was created
with optimal tiling, lost exportability, then required the disabled DRM-modifier
extension when exporting later. A presentation capture without that visible
workload was rejected.

## Shared Xwayland pixmap export (2026-10-19)

The private Zink path previously made only shared scanout images exportable at
creation. Xwayland also exports ordinary shared window pixmaps. Extend the same
linear DMA-BUF declaration/allocation path to 2D BGRA8 shared images with one
sample, one mip and one array layer, qualified to Venus on Mali without DRM
modifiers. Imports and the existing scanout path retain their prior behavior;
GPU synchronization is unchanged.

The failing window now displays with the original six-colour motion workload.
Three real Android captures verify motion, RGB content and both corner regions
at native and 1920×1200 resolutions. KWin and Xwayland map the temporary candidate
library, SHA-256
`d214dcabfb02964e1a0c87317e2157f54c75a68244793752658997d49b8ef33e`
(19,276,176 bytes). Mesa compilation, the existing private DMA-BUF/upload policy
tests and patch integration check pass.

With the experimental Wayland compositor and Plasma shell, native 3408×2272@60
measures 75.72 fps over 59.49 seconds, p95/p99 16.69/16.72 ms, with 24 intervals
over 20 ms. At 1920×1200@120, the same visible workload measures 91.16 fps over
60.13 seconds, p95/p99 16.68/16.70 ms, with 9 intervals over 20 ms. Both use large
cores 4–7 and full repainting. These are display-path experiments, not acceptance
of a complete production Wayland session or of stable 120 fps. Session startup,
App mode control and input compatibility must be validated before changing the
default desktop. The installed production session remains X11.

A follow-up X11 comparison on the same running VM and AHB-size-fixed runtime
measures 61.66 fps native and 71.86 fps at 1920 with the candidate in KWin, versus
61.10 and 69.30 fps with the original library. Both native samples have p95/p99
25.01/33.33 ms; their over-20-ms counts are 951 and 696 respectively. The 1920
counts are 76 and 109. Xorg's secure execution loads the original installed
library in both runs, while KWin's mapped library confirms the intended change.
This does not establish an X11 performance gain or explain the change in native
cadence relative to earlier runs; retain the patch for the reproduced Xwayland
export repair, not as a queue or X11 performance optimization.

## Exported AHB lifetime regression (2026-10-19)

An experimental partial-repaint Wayland run reached 81.35 fps at native
resolution, but its p95/p99 regressed to 24.99/25.01 ms (344 intervals over 20 ms).
The subsequent mode query exposed a separate crash: KWin imported a 1×1 image
after its short-lived exporting client exited. The AHB registry entry had been
removed with the exporting Vulkan memory, although the guest still held the
exported DMA-BUF. The fallback raw-fd allocation returned
`VK_ERROR_INVALID_EXTERNAL_HANDLE`, and KWin subsequently exited with status 134.
The attempted 1920 sample failed the visible-motion check and was not measured.

`ahb_lifetime_guest_probe.c` reproduces the lifetime bug independently of KWin
and partial repainting, with separate Vulkan producer and consumer instances.
Keeping the producer alive passes; destroying it before importing the same
exported fd reproduces a fatal Venus ring error. The fix reference-counts registry
ownership and retains AHB references in both the server's context resource and
the global exported resource. The object-table lock covers the handoff from
Vulkan memory to the server resource; each resource releases its registration
and AHB before closing its fd, including resource-table insertion failures.
No GPU fences are removed.

The registry lifetime test fails before the repair and passes afterward with
AddressSanitizer/UndefinedBehaviorSanitizer, including last-owner removal,
identity mismatch, slot reuse and reference-count overflow. On the installed
runtime, the producer-alive control and producer-exited regression both pass.
Two additional batches of 16 producer-exited runs all pass; sampled runtime fd
counts remain in the 926–935 range rather than growing with each run. All four
existing guest image-layout checks also pass. Android unit tests, native build,
APK build and packaged artifact verification pass. The runtime is 14,116,096
bytes, SHA-256
`0302b363b9e9cfa434cf86f5cfda7a57b84fa69ea8f7b20af6a647def252470a`.

The production X11 desktop still uses hardware OpenGL, with Xorg/KWin on large
cores 4–7 and Plasma unrestricted. Partial repainting remains experimental and
is not retained as a production optimization on the strength of its average fps.

## Complete Wayland session and App mode-control trial (2026-10-19)

With the AHB lifetime repair, the temporary session now runs the normal
`startplasma-wayland`/`kwin_wayland_wrapper` startup, including ksmserver, kded5
and plasmashell. KWin and Xwayland remain on large cores 4–7; Plasma remains
unrestricted. The same visible X11 motion workload gives these own-Surface
presentation results, each preceded by RGB/motion/corner checks:

| Mode | Repaint | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | --- | ---: | ---: | --- | ---: |
| 3408×2272@59.998 | Full | 59.35 | 77.19 | 16.68 / 16.71 | 18 |
| 1920×1200@120 | Full | 59.72 | 89.31 | 16.68 / 16.70 | 7 |
| 3408×2272@59.998 | Partial | 59.61 | 91.20 | 16.68 / 16.70 | 24 |
| 1920×1200@120 | Partial | 59.54 | 90.34 | 16.68 / 16.70 | 7 |

The 1920 partial-repaint difference is small; native partial repainting improves
average presentation rate without establishing a sustained-animation acceptance
result. Surface presentation counts can exceed the guest mode rate and do not
prove that every frame contains a distinct application update. Default production
startup remains X11 while session lifecycle, repaint correctness and workload
coverage are being validated.

The fixed-user display helper now has a Wayland backend using KScreen's actual
mode IDs and JSON rates. It preserves the existing protocol and eight-megapixel/
refresh budgets, verifies the selected mode and restores the previous mode on
failure. A private 0600 session file carries only the required graphics/session
environment; its owner, KWin PID/start time and Wayland socket are checked.
Missing metadata uses the unchanged X11 backend; corrupt/stale metadata fails.
The normal X11 launcher does not publish this metadata or enable Wayland.

A device trial exposed a separate KScreen 5.27 JSON-query exit failure:
`malloc_consolidate(): unaligned fastbin chunk detected`. A bounded, process-local
abort trace placed the failure in the static QTextStream destructor. Thirty help
and thirty human-readable output queries passed; JSON queries failed three times
in nine attempts. The [paired KScreen source](https://raw.githubusercontent.com/KDE/libkscreen/v5.27.10/src/doctor/doctor.cpp)
leaves `showJson()` buffered, whereas `showOutputs()` flushes with `Qt::endl`.
Requesting `-j -o` flushes before Qt teardown; 150 consecutive queries then passed
in 11.59 seconds. The controller parses only the first JSON object, preserving
precise rates instead of the rounded human-readable listing. Software QtQuick
did not fix the failure; the separate KScreen backend experiment timed out and
was removed. No crash is ignored or retried as success.

With the helper temporarily mounted over the guest's control entry point, App
mode requests passed this sequence with actual pixels and the same VM PID 12729:
1920×1200@120 → 3408×2272@59.998 → 1920×1280@119.998 →
1920×1200@59.885 → 1920×1200@120. A fullscreen native Wayland input probe then
verified five points at each of native, aspect-fit and 1920×1200 modes; all were
within two guest pixels. Each mode also received Qt Escape (`16777216`) while
Android remained in the app. The probe's six flat colour bands intentionally do
not meet the generic desktop screenshot diversity threshold; they were visually
checked, and input acceptance uses actual guest event coordinates from journald.
The guest Python suite passes 32 tests, covering both backends, rollback, rate
precision, invalid mode IDs, stale sessions, symlinks and private file/socket
checks. Production Wayland startup and packaging remain a separate next step.

## Native Wayland animation clock and rejected pacing prototype

A separate native Wayland QtQuick window animates RGB bars using the threaded
OpenGL renderer (Zink/Venus/Mali confirmed). It is a different workload from the
moving X11 window, so its results are not an A/B client-backend speedup claim.
At 1920×1200 it measures 90.92 own-Surface presentations/s over 59.32 seconds,
p95/p99 16.68/16.70 ms, with 16 intervals over 20 ms. At native resolution it
measures 86.44 over 59.92 seconds, but p95/p99 regress to 24.99/25.01 ms, with
319 intervals over 20 ms. Both pass visible RGB/motion/corner checks.

Counting the application's `frameSwapped` signal and comparing a QML 5000 ms
timer with `Date.now()` exposes an acceptance problem: native mode is advertised
as 59.998 Hz, but steady samples contain 300 swaps in about 2.77–3.11 real seconds.
The [paired Qt animation driver](https://raw.githubusercontent.com/qt/qtdeclarative/v5.15.12-lts-lgpl/src/quick/scenegraph/qsgcontext.cpp)
advances animation time by the advertised vsync interval while in vsync mode.
Thus the excess frame callbacks also speed up animation time; a high presentation
count must not be called a successful native-60 optimization. The observations
are consistent with virtual display completion feedback running ahead of the
declared refresh rate, rather than repeated identical client frames alone.

Two **temporary, unshipped** preload probes intercept the paired KWin 5.27.10
`RenderLoopPrivate::notifyFrameCompleted` and pace only rates at or below 60.25 Hz
against the monotonic clock. They are loaded only into the private cap-free KWin
copy; child applications do not inherit the preload. The first waits from each
actual wakeup; the second preserves timer phase instead of accumulating wakeup
error. The second restores 300 swaps and the QML 5-second timer to approximately
5.00–5.05 seconds. However, neither solves actual presentation cadence:

| Native GPU workload | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| Completion delay, wakeup-relative | 59.25 | 59.36 | 25.02 / 25.08 | 1257 |
| Completion delay, fixed phase | 59.33 | 59.93 | 25.02 / 25.04 | 1321 |
| Fixed phase + full repaint effect | 59.35 | 59.76 | 25.01 / 25.03 | 889 |

The fixed-phase sample's frame-ready intervals have p95/p99 21.41/24.69 ms,
so work arriving at the Android display layer is already irregular. These probes
are rejected as production performance changes: they block the compositor thread,
depend on a private ABI, and still fail the frame-interval criterion. Default X11
startup is retained. The next investigation is the timing of application-owned
Surface submissions and how producer completion relates to actual presentation,
without dropping producer/consumer synchronization or modifying Android globally.
