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

## Retained Surface property submission optimization (2026-10-19)

`RootOwnedSurface.present()` previously submitted geometry, crop, colour transform,
corner radius, trust, layer order and visibility on every buffer. The candidate
initializes fixed properties on the first frame and updates geometry/crop only
when the guest size changes. Every frame still submits its original AHB and real
release callback immediately; producer waits and consumer release fences are
unchanged. The submitted-size cache advances only after `apply()` succeeds.
Reattachment creates a new layer and initializes its complete state again.

The same X11 moving-colour-window workload was tested in fullscreen, with a
nominal 60-second unique own-Surface presentation capture and real RGB/motion/
corner checks. Candidate A1, baseline B1, candidate A2 and baseline B2 each
received a fresh VM start and the same initial native-mode sequence. The first
pair then also tested 1920×1200@120. Xorg/KWin remained on large cores 4–7;
SELinux and host hidden-API settings were unchanged. The native runtime was the
same `0302b363b9e9cfa434cf86f5cfda7a57b84fa69ea8f7b20af6a647def252470a`
in every APK. The candidate APK SHA-256 was
`2e8ca3808a7e9474c87a78f95f7704a5b130dad72e04fd844f1365b6e0ed180d`.

| Mode / run | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| Native, baseline B1 | 59.28 | 62.78 | 24.99 / 25.01 | 203 |
| Native, candidate A1 | 59.94 | 63.46 | 16.70 / 25.01 | 134 |
| Native, baseline B2 | 59.70 | 62.28 | 24.98 / 25.01 | 192 |
| Native, candidate A2 | 59.38 | 62.70 | 16.70 / 25.01 | 149 |
| 1920×1200, baseline B1 | 59.84 | 73.28 | 16.69 / 25.00 | 65 |
| 1920×1200, candidate A1 | 60.10 | 72.86 | 16.69 / 24.99 | 58 |

Pooling the two native runs gives approximately 5.3% long intervals for the
baseline and 3.8% for the candidate, a roughly 29% relative reduction. This is
retained as a modest native-cadence improvement. It does not establish a 1920
throughput improvement, stable 120 fps, or stable native 60 fps: native p99
remains around 25 ms. An earlier long-running-VM baseline had 901 long intervals;
it is excluded from the paired comparison because only the candidate had been
restarted. The fresh-start controls prevent attributing that outlier to this
change.

Private captures use the `surface-static-state-` prefix under
`.pi/device-debug/perf-60-20261018/`; candidate and control repeat captures have
`repeat-native` and `control-repeat-native` names. The candidate passed Android
unit tests, debug APK build, six direct-display contract checks, five packaged
arm64 ELF checks and the native runtime pin. Mode changes to native and 1920
were acknowledged with actual guest pixels, without restarting the active VM.

The final APK (SHA-256
`f6729fea67c46d3980389111bb645b93247b6ec97a78e071402cd757bc624ba3`)
was rebuilt, passed the same checks and was installed. On VM PID 4067, the
native@60 → 1920×1200@120 → 1920×1280@120 sequence passed actual-pixel and
five-point guest-coordinate checks in every mode. The fit mode occupied the
full 3408×2272 viewport; the fixed 1920×1200 mode retained its expected
letterbox bounds. Android focus remained in the App after injected Escape.

A separate desired-presentation-time idea was deferred before implementation:
extending SurfaceFlinger retention requires checking the complete producer reuse
ordering, not just keeping an AHB allocation alive. No delayed submission,
fabricated completion timestamp or new buffering scheme is included here.

## Retained scoped Venus sync-fd export (2026-10-19)

The paired guest Venus creates a real completion sync-fd in
`vn_GetSemaphoreFdKHR`, then waits on the CPU when the renderer lacks implicit
fencing. In the desktop trace, creating the fd took about 25–45 microseconds,
whereas the following wait took roughly 1.6–2.2 milliseconds in KWin and
3.2–3.6 milliseconds in native-resolution Xorg. Zink already transfers this
dependency into the shared DMA-BUF reservation and imports it for the next
consumer. Returning the pending fd allows more producer/consumer overlap.
The consumer wait, renderer GPU completion checks, real fences and Surface
release callbacks remain intact; `has_implicit_fencing` is not changed.

The retained patch is restricted to a Zink instance whose current PID exactly
matches `VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID`. The existing Xorg/KWin launcher
sets that value immediately before exec, preserving the PID. An ordinary
application or child inheriting the variable retains the compatibility CPU
wait. No global environment toggle is enabled. The Manjaro Venus build script
applies the patch against the pinned Mesa source. CPU selection is unchanged.

Four complete desktop sessions were run in order: instrumented control B1,
instrumented asynchronous candidate A1, control B2, and the final scoped
candidate A2 without timing/logging. Each restarted the guest desktop, selected
1920×1200@120, captured the visible moving colour window, then selected
3408×2272@60 and repeated. These are desktop-session restarts within the same
VM, not the fresh-VM/native-first samples in the preceding section. Every
capture used unique actual presentation timestamps on the App's own layer and
passed RGB, motion and corner checks. Xorg/KWin stayed on cores 4–7.

| Mode / run | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| 1920×1200, B1 | 59.46 | 70.60 | 16.69 / 25.00 | 105 |
| 1920×1200, A1 | 59.57 | 93.38 | 16.67 / 16.69 | 3 |
| 1920×1200, B2 | 59.78 | 70.59 | 16.69 / 25.00 | 76 |
| 1920×1200, A2 | 59.57 | 92.84 | 16.67 / 16.70 | 5 |
| Native, B1 | 59.24 | 61.66 | 25.01 / 33.33 | 858 |
| Native, A1 | 59.82 | 83.04 | 16.70 / 25.01 | 208 |
| Native, B2 | 59.24 | 61.19 | 25.02 / 33.33 | 894 |
| Native, A2 | 60.09 | 81.56 | 24.99 / 25.01 | 275 |

1920 throughput improves about 32%, with substantially fewer long intervals.
Native also improves in this paired sequence, but is not stable or capped at
60: its p99 remains 25 ms and A2 p95 is also about 25 ms. This change does not
meet either the stable-120 or stable-native-60 goal. A preliminary KWin-only
replacement improved average throughput but worsened long intervals; it is
not the shipped configuration. A repeat KWin-only control overlapping a
desktop restart is explicitly excluded from the comparison.

`native/venus-crosvm/venus_sync_fd_guest_probe.c` provides an actual guest-GPU
regression. A timeline gate delays a 1024-word GPU fill by 200 ms. Its semaphore
sync-fd is imported into a DMA-BUF reservation, exported again, then waited on
by another Vulkan instance. Async export takes tens of microseconds and both
original and DMA-BUF-exported fds are initially pending. The consumer completes
only after the delayed producer, and all 1024 words are checked. Baseline,
wrong-PID and non-Zink cases still wait about 200 ms before export. The async
expectation failed before the scoped patch and all four modes passed afterward.
`bash scripts/build-venus-sync-fd-probe.sh` builds the probe with
`-O2 -Wall -Wextra -Werror`, linked with Vulkan/pthreads against the paired
AArch64 guest sysroot. Invoke each mode with the paired ICD
and the existing four disabled-feedback `VN_PERF` flags.

The 33 guest Python tests pass, including the display launcher's PID opt-in and
existing no-prime affinity cases. The full pinned Mesa cross-build passes and
produces a 1,677,064-byte ICD with SHA-256
`dcb191f5a2437ecd63b3538d3fd08b03fd6cdee08fae6fc5d12f510218fcbd9a`.
It and the launcher were atomically installed only inside the existing guest,
after stopping its desktop. Original files are backed up under
`/var/lib/viruaisor/desktop-backups/syncfd-20261019`; restoring both files while
the desktop is stopped reverses the update. The temporary guest service
namespace overrides and rollback timer were removed/stopped. The droid account,
Android APK and host renderer were not changed by this optimization.

Private evidence uses the `syncfd-` prefix in `.pi/device-debug/` and is excluded
from version control. Production source contains no diagnostic timing/logging.

After a full VM stop/start, VM PID 16488 loaded the installed ICD and each display
process's matching PID opt-in without a service drop-in. Native-first final
validation measured 88.67 presentations/s over 59.46 seconds, p95/p99
16.68/16.70 ms and 9 intervals over 20 ms. The following 1920 run measured
90.77 presentations/s over 59.29 seconds, p95/p99 16.67/16.70 ms and 4 long
intervals. These confirm normal-startup operation; they are not added to the
paired comparison above. Native output still exceeds its nominal 60-Hz mode,
so remaining work includes cadence/rate control, not merely more throughput.

Native → 1920×1200 → 1920×1280 mode changes and all five guest-coordinate
points passed in each mode on that same VM. The fit mode filled the full
3408×2272 viewport. Injected Escape produced one guest key-down/key-up pair and
kept Android focus in the App. Xorg/KWin remained on cores 4–7, SELinux remained
Enforcing, host hidden-API values matched the prior state, and droid remained
locked. No guest account or user data was replaced.

## Rejected software-vblank timing trials after sync-fd optimization (2026-10-19)

With the retained sync-fd ICD, KWin's built-in software-vblank fallback was
retested using `KWIN_X11_FORCE_SOFTWARE_VSYNC=1` in a temporary guest service
override. KWin reported 59997 mHz at native resolution; it was not mistaking
the mode for 120 Hz. The paired KWin 5.27.10 implementation aligns a monotonic
timestamp to the refresh period, then truncates the timer delay to whole
milliseconds. Diagnostic callbacks confirmed frequent early wakeups.

Two private ABI probes tested the timing hypothesis without changing GPU
completion, consumer waits, or Surface release fences. The first used
`Qt::PreciseTimer` and rounded the delay upward. The second used a nonblocking
absolute `CLOCK_MONOTONIC` timerfd with a Qt socket notifier. It did not sleep
on the compositor thread. Each probe was loaded only into KWin from guest
`/tmp`, with a runtime namespace bind and bounded recovery timer.

All samples below used fullscreen native resolution on VM PID 16488, the
installed sync-fd ICD, large cores 4–7, and the moving X11 colour window.
Each nominal 60-second capture passed RGB, motion and corner checks. Desktop
sessions were restarted between the control shim and each candidate.

| Software-vblank variant | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| Built-in timer, no shim | 59.65 | 59.92 | 16.69 / 25.00 | 50 |
| Control shim, original timer behavior | 59.57 | 59.88 | 16.69 / 25.00 | 56 |
| Precise Qt timer, upward rounding | 59.79 | 59.71 | 25.01 / 25.03 | 615 |
| Absolute timerfd | 59.27 | 59.93 | 25.01 / 25.03 | 664 |

The control recorded 19–35 early callbacks per second. Upward rounding removed
early callbacks but typically woke about 1.0–1.3 ms late. Timerfd also removed
early callbacks and reduced steady-state mean lateness to about 80–115 us,
yet actual presentation intervals remained poor. More accurate wakeups alone
therefore did not establish smoother output. The samples do not separate the
effect of guest/Android clock phase from the remaining rendering and queueing
delays; average throughput near 60 is not a stability pass.
Timerfd's frame-ready interval p99 was still 22.62 ms, despite the much smaller
callback error, so timer accuracy did not remove the irregular buffer arrivals.

Neither timer candidate nor the software-vblank override is retained. The
override was removed, recovery timers stopped, and the normal desktop was
verified without either preload before the next full VM restart. Raw evidence
uses `vsync-after-syncfd-`, `vsync-timer-control-`, `vsync-precise-`, and
`vsync-timerfd-` prefixes in the private test directories. No Android settings,
host kernel, guest kernel, or production KWin library was modified.

## Queue-completion cache retest after sync-fd optimization (2026-10-19)

The earlier rejected queue cache was rebuilt against the current renderer,
including the retained AHB lifetime repairs, and tested with the installed
asynchronous desktop sync-fd ICD. Removing the producer CPU wait had changed
the bottleneck, so the earlier negative result alone was insufficient to judge
this combination. The cache algorithm was unchanged: successful guest queue
submissions advance an epoch, and display synchronization reuses completed
work only after draining the rings, with no pending fence or newer submission.

The test order was baseline B1, candidate A1, baseline B2, candidate A2. Every
run received a complete VM restart and the same native-first, then 1920×1200
sequence. All eight captures used fullscreen motion, unique actual own-layer
presentation timestamps, and passed RGB/movement/corner checks. The same
scoped guest ICD and launcher were verified after each boot; Xorg/KWin stayed
on guest large cores 4–7, with no service overrides.

| Mode / run | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| Native, B1 | 59.78 | 88.59 | 16.68 / 16.70 | 7 |
| Native, A1 | 59.97 | 90.43 | 16.67 / 16.70 | 8 |
| Native, B2 | 59.27 | 91.64 | 16.67 / 16.70 | 11 |
| Native, A2 | 59.38 | 89.55 | 16.67 / 16.70 | 7 |
| 1920×1200, B1 | 59.37 | 92.80 | 16.67 / 16.70 | 4 |
| 1920×1200, A1 | 59.77 | 96.29 | 16.67 / 16.69 | 3 |
| 1920×1200, B2 | 59.91 | 92.27 | 16.67 / 16.70 | 2 |
| 1920×1200, A2 | 59.27 | 94.19 | 16.67 / 16.69 | 3 |

Pooling elapsed time gives 92.53 versus 95.25 presentations/s at 1920, a 2.93%
average increase. Both configurations had six intervals over 20 ms, and the
p95/p99 distribution was effectively unchanged. Native pooled throughput was
90.11 versus 90.00, with 18 versus 15 long intervals. Neither native throughput
nor cadence shows a useful gain. The candidate's second 1920 run also reduced
the initially observed advantage. There is a small measured average-throughput
gain at 1920; it is insufficient for the user's "keep only a clear benefit"
criterion, and does not improve the stable-120/stable-native-60 result. The cache
is again rejected, rather than described as having literally zero effect.

The candidate passed fence-lifetime tests with AddressSanitizer and
UndefinedBehaviorSanitizer, the full native build, Android unit tests, debug
build, and five-ELF packaging/pin verification. Its native runtime was
14,116,888 bytes, SHA-256
`78ec9332a75f83d5ccf7eb488624014862f5ae073a2525445a343a6d14366222`;
the APK was
`5d691d382fc18178499cb218f10f56dc464094120dd7bb05982ddfa00fe07ea4`.
The running executable was hashed on-device. The actual GPU dependency probe
also passed baseline, async, wrong-PID and non-Zink modes; all 1024 GPU-written
words were correct after consumption by a separate Vulkan instance. Async
export returned a pending fd in 21 us, while compatibility cases waited about
203 ms. These checks establish that correctness was preserved in this trial;
they do not turn its small performance gain into a stability improvement.

Private evidence uses `queue-syncfd-` capture names and the
`touch-perf-20261018/queue-after-syncfd/` directory. The candidate's source diff
and binaries are preserved there for reproducibility, outside version control.

The cache patch, header/test changes, build integration and artifact pin were
removed again. Rebuilding the restored renderer reproduced the exact
`0302b363b9e9cfa434cf86f5cfda7a57b84fa69ea8f7b20af6a647def252470a`
runtime. Android unit tests, debug build and packaged ELF/pin checks passed
after restoration. The rebuilt APK's 157 ZIP entry payloads matched the saved
baseline; the original baseline APK (`f6729fea...`) was reinstalled. A full
restart produced VM PID 2640 with the correct running executable, unchanged
scoped guest ICD/launcher, no service overrides, and large-core masks 4–7.
The 1920×1280 fit mode and five guest-coordinate points passed on the full
3408×2272 viewport. Injected Escape produced one guest down/up pair and kept
Android focus in the App. SELinux remained Enforcing, the two checked host hidden-API
settings were unchanged, and the droid account remained locked. Only this
experiment record is retained in Git; the earlier sync-fd optimization remains
installed and the overall frame-rate/cadence goals remain open.

## Bounded scanout reference retention (2026-10-19)

A 12-second Perfetto capture exposed a separate application-side cost:
`RootDisplayService` closed each received HardwareBuffer after submitting it.
The Android 17 SurfaceComposerClient cache keys entries by GraphicBuffer ID,
but registers a death callback on the particular GraphicBuffer used for the
first submission. Destroying that reference sends an uncache transaction.
Consequently, rotating the same scanout allocations still rebuilt the
SurfaceFlinger texture import on practically every frame. See the
[Android 17 BufferCache implementation](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android17-release/libs/gui/SurfaceComposerClient.cpp).

The candidate retains at most four references by the system-wide
HardwareBuffer ID, reusing the same retained object for repeated submissions.
Parcel duplication acquires a reference to the existing allocation; it does
not copy pixels or allocate a new image. A size change releases all old-size
entries, LRU eviction bounds replacements at the same size, and session cleanup
releases every entry even when stopping the producer fails. Incoming parcel
wrappers are still closed. Real GPU dependencies, per-frame release callbacks,
submission timing and the existing colour/CLIENT-composition workaround are
unchanged. The previously rejected queue-completion cache remains absent.

Six unit tests cover reference reuse, bounded eviction, resize, failed import,
leaving the retained profile and failure-tolerant final cleanup. The reuse/eviction tests first failed with
per-frame replacement and then passed with reuse. Two device tests verify that
Parcel duplication preserves the native ID, incoming and retained references
close independently, repeated IDs reuse the owned reference, and equal-size
allocations remain distinct. Android unit tests, debug/test APK builds and the
five-ELF packaging/source-pin check passed.

In equal-duration 1920×1200 traces of the all-resolution prototype, baseline SurfaceFlinger created 1,348
`GraphiteBackendTexture` imports; the warmed candidate created none and showed
no map/unmap/import/free activity in that process. RenderEngine CPU time fell
from 2,218.02 to 789.81 ms over the 12-second windows. SurfaceFlinger presented
1,079 versus 1,149 compositions during those traces, so the CPU saving was not
caused by doing fewer compositions. Both traces had no error stats. These
tracing runs identify the mechanism; acceptance throughput is measured
separately without active tracing.

Four complete VM boots ran in A1/B1/A2/B2 order (A is the all-resolution
prototype, B the installed baseline), native first and then 1920×1200 on
each boot. All eight fullscreen captures passed motion, RGB and corner checks.
The guest ICD and launcher hashes, large-core masks 4–7, and absence of service
overrides were verified after every boot.

| Mode / run | Seconds | Presentations/s | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | --- | ---: |
| Native, A1 | 59.37 | 105.45 | 16.668 / 16.686 | 4 |
| Native, B1 | 59.27 | 90.04 | 16.675 / 16.702 | 9 |
| Native, A2 | 59.68 | 101.65 | 16.670 / 16.683 | 2 |
| Native, B2 | 59.67 | 90.75 | 16.674 / 16.698 | 7 |
| 1920×1200, A1 | 59.33 | 100.94 | 16.671 / 16.692 | 2 |
| 1920×1200, B1 | 60.10 | 103.73 | 16.669 / 16.683 | 1 |
| 1920×1200, A2 | 59.23 | 95.91 | 16.672 / 16.696 | 3 |
| 1920×1200, B2 | 59.28 | 97.85 | 16.671 / 16.688 | 2 |

Pooled native throughput rose from 90.40 to 103.55 presentations/s (+14.55%),
with 16 versus 6 long intervals. This is a repeatable throughput improvement,
but the nominal native 60-Hz mode still runs too fast and does not meet the
requested cadence. Pooled 1920 throughput decreased from 100.81 to 98.43
(-2.36%), with 3 versus 5 long intervals. The absolute 1920 results vary across
boots, and both paired differences favour the baseline. Saving import work
therefore does not establish a frame-rate benefit in that mode.

Only the high-resolution profile is retained: buffers over 4,194,304 pixels,
matching the existing display protocol's <=60-Hz pixel budget. Smaller buffers
use the original direct submission path, and switching down clears all retained
references. This keeps the repeatable native benefit without applying the
unhelpful 1920 change. The 1920 Perfetto CPU saving above describes the discarded
all-resolution prototype, not the final smaller-buffer path. No general
120-fps improvement is claimed.

The all-resolution APK was
`5b3e385581c3f018d381595ae51d8ae2764a59707fdb14f4d9aa215a5c7a5c50`;
the baseline was `f6729fea...`. Its source snapshot, APK, traces and build logs
remain private under `touch-perf-20261018/pipeline-trace/`, with paired captures
named `buffer-cache-{a1,b1,a2,b2}-{native,wide}`. The final scoped APK is
`78d3a099d8462905ffddf53d246e235d620f94eadca4cd55f0ce4b5d88da6bcd`.
The crosvm/renderer pin and guest ICD are unchanged.

A fresh VM boot of the final scoped APK (PID 14911) passed another fullscreen
motion/RGB/corner run: native delivered 101.91 presentations/s over 59.48 s,
with p95/p99 16.669/16.682 ms and one interval over 20 ms; 1920×1200 delivered
97.32 over 59.82 s, with p95/p99 16.671/16.692 ms and one long interval.
This final smoke sample agrees with the retained native benefit and the
recent baseline 1920 range. It is not an additional paired improvement claim.

Final 12-second traces confirm the installed scope: warmed native had no
SurfaceFlinger texture import/map/unmap calls, while 1920×1200 again created
1,365 GraphiteBackendTexture imports. Neither trace reported error stats.
Native → 1920×1200 → 1920×1280 mode changes and five-point guest-coordinate
checks passed without restarting VM 14911. The fit mode filled the full
3408×2272 viewport. Injected Escape produced exactly one guest down/up pair;
MainActivity remained the focused and resumed Android activity.

The final running renderer hash is still `0302b363...`, the guest ICD/launcher
hashes match the baseline, Xorg/KWin remain on cores 4–7 with matching PID
opt-in, no service overrides exist, and droid remains locked. SELinux is
Enforcing; host hidden-API policy remains null and the existing exemption
remains `Landroid/system/virtualmachine/VirtualMachine`. No host setting or
prime-core binding was added. The App remains open in fit mode for the ongoing
performance investigation. Stable 1920/120 and native/60 cadence remain open.

## Native presentation cadence after buffer retention (2026-10-19)

The built-in KWin software-vblank option was retested with the retained native
buffer cache. A temporary guest `/run` override, independent bounded diagnostic
service and recovery timer were used; no timer preload or production guest
library was changed. KWin reported 59997 mHz and used cores 4–7 with the normal
scoped sync-fd ICD. The 59.69-second native sample delivered 59.91
presentations/s, p95/p99 16.690/24.998 ms, 74 intervals over 20 ms and a
33.335-ms maximum. Frame-ready p99 was still 21.635 ms. The override was removed,
the normal service/environment was verified, and the VM was fully restarted
before the application experiments. Software-vblank alone is not retained.

The next application prototype uses the public
[desired presentation time API](https://developer.android.com/reference/android/view/SurfaceControl.Transaction#setDesiredPresentTimeNanos(long)).
Only the high-resolution profile is paced. The synchronous frame sink waits
until at most one nominal 60-Hz period before the target, then submits the
existing buffer and release callback. This bounds producer back-pressure and
future queue depth without changing GPU completion or physical refresh rate.
Past slots are skipped and mode changes reset the schedule; failed transaction
applications do not advance it. Android 17's
[System.nanoTime implementation](https://android.googlesource.com/platform/libcore/+/refs/heads/android17-release/ojluni/src/main/native/System.c)
uses the same CLOCK_MONOTONIC time base required by the presentation API.

The first prototype, with an arbitrary initial clock phase, delivered 59.98
presentations/s over 59.68 s, p95/p99 16.681/16.705 ms, but 17 intervals over
20 ms and a 41.666-ms maximum. Sixteen of those long intervals had regular
16.667-ms desired timestamps and timely frame-ready timestamps, followed by
an 8.33-ms catch-up interval. The remaining event skipped a desired slot.
This directed the next experiment toward Android presentation phase, rather
than another guest timer-precision patch.

The aligned prototype reads
[Choreographer frame timelines](https://developer.android.com/reference/android/view/Choreographer.FrameData)
on the root helper's main looper, copying only numeric values while the callback
is valid. It aligns the native cadence to an integer number of physical refresh
intervals near 60 Hz, with a 1-ms desired-time margin before the expected
physical presentation. The clock callback runs only in the high-resolution
profile and is removed on switching down or closing. An unavailable or
incompatible clock falls back to the fixed 60-Hz schedule. Seven unit tests
cover steady pacing, failed submissions, lateness, mode reset, phase alignment,
odd/even 120-Hz tick updates and invalid/incompatible clock intervals. Four
original scheduling tests and two phase tests were observed failing before
their respective implementations; all tests and the debug build now pass.

The first aligned native sample on fresh VM PID 20640 delivered 60.00
presentations/s over 59.98 s. Its p95/p99 were 16.682/16.698 ms, maximum
16.905 ms, with zero intervals over 20 ms. Median frame-ready-to-present
latency was 17.342 ms, versus 24.558 ms for the unaligned prototype. Root logs
confirmed receipt of roughly 8.333-ms physical refresh intervals. These results
use actual own-layer presentation timestamps and passed RGB/motion/corner checks.

Private evidence uses `vsync-cache-native`, `cadence-native` and
`cadence-phase-native` capture names. The unaligned APK is
`36d09f8dac084d3ce819364885cd6f8b07df696198601c9f13efccc0111bc630`;
the aligned APK is
`8f689fa7f350d3dbf5585f4640d87c0d769838b8f4b95ebd187de15d12224ca1`.
Prototype sources, build logs and test-failure evidence are in the private
`pipeline-trace/` directory. Native runtime and guest ICD pins are unchanged.

The longer native run (`cadence-phase-native-long`) delivered 59.972
presentations/s over 179.70 s, p95/p99 16.683/16.700 ms, with 9 intervals
over 20 ms out of 10,777 intervals (0.084%) and a 33.337-ms maximum. This is
not a zero-jank claim. Six of the long intervals cluster within 0.22 s around
CLOCK_MONOTONIC 222907.55–222907.79. The root process had logged 10,000 live
BinderProxies at 222904.610, then a GC at 222907.505. The correlation points
to a remaining lifecycle/allocation cost, rather than sustained phase drift;
it does not by itself prove which allocations dominate the pause.

Source inspection confirms that `makeReleaseBinder` currently creates a new
NDK Binder object for every frame, and the Java frame sink creates a proxy
when reading it. The actual AHB/release-fence lifetime is already bounded at
eight and each release is consumed once; replacing this with a persistent
callback endpoint must preserve those constraints and the real sync-file.
This is a separate follow-up, not part of the cadence patch. Also, Android 17
accounts each Java HardwareBuffer reference using its full estimated pixel
size, even for parcel imports, so per-frame wrapper allocation remains another
candidate to measure. Private source copies and the correlated root log are
saved beside the captures.

After switching down, the unchanged 1920×1200 submission path passed another
59.52-second motion sample: 91.49 presentations/s, p95/p99 16.673/16.700 ms,
four long intervals. This remains far below stable 120 and is not described
as a wide-mode performance improvement from the native-only scheduler.

A full VM stop/start produced PID 24756 with the same APK/runtime/guest policy
and no overrides. Its native sample (`cadence-phase-reboot-native`) delivered
59.983 presentations/s over 59.30 s, p95/p99 16.684/16.698 ms, and one interval
over 20 ms (maximum 33.343 ms). The normal, long and reboot runs all preserve
the 60-Hz cadence at p99; the rare longer intervals remain explicit follow-up
work. Neither average native throughput above 60 nor a 120-Hz mode declaration
is being substituted for the requested presentation cadence.

Final regression on VM 24756 passed native -> 1920x1200 -> 1920x1280 resize
and all three five-point guest touch checks. Escape produced exactly one
guest key-down/up pair; MainActivity remained focused and resumed. Two
12-second traces had no error stats: the native trace recorded 1,436 root
Choreographer callbacks and zero Graphite texture imports; the wide trace
recorded zero root callbacks and 1,310 imports. This verifies that both the
clock and retained-buffer path stop when switching to the 120-Hz profile.
The native root main thread used 384.96 ms CPU during that traced sample;
clock observation therefore has a measurable, profile-scoped cost.

The final renderer and guest pins match the preceding baseline, Xorg/KWin
remain on cores 4-7 with matching PID opt-in, and no service overrides remain.
The droid account is still locked; SELinux is Enforcing and both host
hidden-API settings are unchanged. No host-system change or prime-core
binding was made. The native cadence patch is retained; stable wide-mode
120 fps and rare native stalls remain under investigation.

## Paced 120-Hz submission and renewed buffer-cache comparison (2026-10-19)

The final wide trace from the native-only scheduler showed 1,325 buffer
arrivals in 12 seconds (110.64/s), 245 intervals below 4 ms and 245 pending
buffers replaced before display. Only 1,081 layer draws remained. The bursty
submission path was therefore a distinct loss from the rare GC clusters.
Extend the same scheduler to the <=4-Mpixel / 120-Hz budget, retaining the
native 60-Hz budget. The initial one-period wide prototype keeps the old
wide buffer-reference behavior; a second prototype adds bounded reference
retention. All use the same renderer, guest ICD, workload and cores 4-7.

Each table row follows a complete VM restart and a passing fullscreen
motion/RGB/corner check. These are untraced actual own-layer presentations.

| Configuration / capture | Duration | Actual fps | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| Native-only pacing, `wide-cadence-B1` | 59.583 s | 94.29 | 16.673 / 16.699 | 12 |
| Wide pacing only, `wide-cadence-A1` | 59.708 s | 99.97 | 16.671 / 16.693 | 6 |
| Wide pacing + retained references, `wide-cadence-cache-A1` | 60.008 s | 103.80 | 16.669 / 16.692 | 6 |
| Two-period lead + retained references, `wide-lead-cache-A1` | 59.883 s | 102.38 | 16.670 / 16.688 | 7 |
| Native-only pacing, `wide-cadence-B2` | 59.375 s | 92.45 | 16.673 / 16.699 | 8 |
| Wide pacing + retained references, `wide-cadence-cache-A2` | 59.967 s | 102.92 | 16.671 / 16.705 | 7 |

Pooling elapsed time for B1/B2 versus cache-A1/A2 gives 93.369 versus
103.363 presentations/s, a 10.70% increase. The candidate's two runs agree,
and the return to baseline reproduces the lower rate. Its 13 long intervals
versus 20 baseline intervals are not a proof of eliminating stalls: the second
candidate run still reached 41.664 ms. Stable 120 fps remains unmet.

The tradeoff is explicit: median ready-to-present latency rises from
12.135/12.231 ms in the baseline runs to 17.319/17.358 ms, while p99 falls
from 18.471/18.456 to 17.458/17.473 ms. This is buffer latency, not an
end-to-end input-latency measurement. The two-period lead provides no further
throughput benefit and is removed. Keep one-period submission and bounded
reference retention for both profiles on the strength of the paired result.

Separate 12-second traces confirm the mechanisms. Pacing alone has 1,155
arrivals and 1,154 draws, no sub-4-ms arrival intervals and no pending-buffer
replacement marker. Adding retained references removes all SurfaceFlinger
texture import/map/unmap calls (1,154 Graphite texture imports before, zero
after); RenderEngine CPU in these separate traces is 2,341.72 versus 922.67 ms.
The root still imports each incoming Java
HardwareBuffer, so that allocation is not eliminated. The two-period lead
returns to burstier arrivals (134 below 4 ms), consistent with its lack of
additional benefit. These traces report no error stats and are not mixed
into the untraced performance samples.

The final candidate APK is
`c1ae1341196155769a93c2251a5524b65a9d4778dcec7ae3e3021ab19e6e63fe`.
The rebuilt final source produces exactly those same APK bytes. The
pacing-only prototype was `3d3eae80dec7bc78bd91fd6dc6d707f5b73ea517bc49588d3aaa06ab7fc6f30b`;
the rejected two-period APK was `b41b69a6dc44bf1adbd425df11cbd90e7665d7bb6e6ac9c628c53ab6c4b92a3c`.
Private evidence, temporary patches, red/green unit logs and traces are under
`wide-cadence/`. Eight cadence tests, all Android unit tests, the debug APK
build and the five-ELF/pinned-runtime packaging check pass.

The pacing-only root log again correlates a long-frame cluster with GC:
5,000 live BinderProxies at monotonic time 223819.277, GC at 223819.539,
then long intervals at 223819.587-223819.712. This reinforces the separate
lifecycle investigation, without claiming an allocation-cause A/B result.
The two-period diagnostic trace also reveals 1,339 `allocateHelper` calls
on crosvm's `vkr-ring-8` thread, averaging 0.397 ms. The label matches the
traced allocation helper in
[AOSP GraphicBufferAllocator](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/ui/GraphicBufferAllocator.cpp).
This per-frame allocation is separate from the eliminated SurfaceFlinger imports; its
calling path has not yet been identified. No additional synchronization or
allocation-policy patch is included on the basis of these clues alone.

Final regression uses the exact candidate on VM PID 3317. Native
3408x2272@60000 delivers 59.950 presentations/s over 59.73 s, p95/p99
16.684/16.699 ms, three intervals over 20 ms and a 33.339-ms maximum.
The default fit mode 1920x1280@120000 delivers 98.065 presentations/s over
60.13 s, p95/p99 16.672/16.704 ms, zero intervals over 20 ms and a
16.806-ms maximum. Its verified viewport fills all 3408x2272 pixels. The
fit result is a smoke sample, not a paired speedup claim for that resolution.

Native -> wide -> fit transitions and five-point guest touch checks all pass
without restarting that VM. Escape yields exactly one guest down/up pair;
MainActivity remains focused and resumed. Runtime hash `0302b363...`, guest
ICD `dcb191f5...` and launcher `4e577f33...` match the baseline. Xorg 384 and
KWin 477 use cores 4-7 with matching PID opt-in; no service overrides exist.
The account remains locked, SELinux Enforcing, hidden-API policy null, and
the existing AVF exemption unchanged. Root display attachment, VM boot and
actual displayed guest motion are verified using the existing permissions;
no host-system change or prime-core binding was added. Keep the App open in
fit mode for the remaining performance investigation. The retained gain
does not close the 120-fps target or the rare native-stall work.

## Reusing private Zink upload allocations (2026-10-19)

A 10-second, 400-Hz user-CPU sample of the two active renderer rings recorded
568 samples without loss. Inclusive allocation samples resolve through
`vn_dispatch_vkAllocateMemory`, `vkr_android_import`,
`venus_gralloc_allocate_desc`, `AHardwareBuffer_allocate`, and the gralloc
allocator Binder call. The exact renderer call address `0x66984c` identifies
the BLOB branch, rather than the dedicated image branch. A second capture kept
raw stacks (394 samples, zero lost); all 28 matching descriptors had BLOB format,
height/layers 1, and usage `0x1000033`: 26 were 110,592 bytes and two 32,768 bytes.
These are sampled allocations, not total allocation counts.

The profiler ran inside the existing VM mount namespace so it read the exact
`0302b363...` renderer ELF. Early samples collected from the host namespace were
discarded: the same `/apex/com.android.virt/bin/crosvm` pathname there names the
different system ELF and produced invalid symbols/unwinding. No kernel perf
limit was relaxed. A profiler-only preload guard blocks writes to `/proc` and
`/sys` and prevents its property setter from reaching Android. Before/after
values remained `perf_event_paranoid=-1`, `perf_event_max_sample_rate=32250`,
`perf_cpu_time_max_percent=25`, `perf_event_mlock_kb=41000`, and
`security.perf_harden=1`. The guard is never loaded into the application or VM.

A temporary Zink probe, limited to 16 matching allocations per process,
confirmed KWin upload allocation with `original_pnext=0`, `flags=0`,
`bda=1`, `priority=0`, and `cache=0`. Its stack resolves through
`map_buffer_range`, `tc_buffer_map`, `tc_invalidate_buffer`, resource creation,
and `bo_create_internal`. Zink had appended its own buffer-device-address
allocation structure before checking `!pNext`, making an ordinary upload
appear ineligible for the existing BO cache. The probe was removed before
building either performance comparison library.

The fix captures eligibility before adding Zink's internal structures. It only
restores caching for the already opted-in Venus/Mali private non-coherent,
host-visible upload path, with no caller extension chain and no
`ZINK_ALLOC_NO_SUBALLOC` flag. Imported/exported/dedicated allocations, other
drivers, coherent memory and disabled opt-in retain their prior behavior.
Existing cache bounds, eviction, buffer references, completion checks and
DMA-BUF cache maintenance are unchanged. The ordinary queue-completion-cache
experiment remains absent.

`test-zink-upload-cache.py` compiles the actual allocation-function prefix with
small screen/Vulkan fixtures, including the order of internal extension
insertion. It failed on the original ordinary-upload/BDA case, then passed
after the fix. It also checks internal memory priority, external caller chains,
no-suballocation, non-host-visible/coherent memory, driver/vendor boundaries,
disabled/missing opt-in and unchanged no-extension behavior. Both existing
upload/DMA-BUF policy tests and the patch-integration test pass. The complete
`build-manjaro-zink.sh` build and install staging pass and reproduce the measured
candidate byte for byte.

The paired comparison replaces only KWin's library, restarts KWin and the
bounded motion fixture for every trial, and leaves Xorg on its installed
`a7aae0f8...` library. Control `d214dcab...` and candidate `8e2ce18c...` are built
from identical source except for this cache fix. RGB, motion and corner checks
pass before every accepted capture. One preliminary capture after compositor
replacement failed the motion check and was discarded without collecting fps.

| Capture | Duration | Actual fps | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `zink-upload-matched-B1` | 59.425 s | excluded: sampling gap | — | — |
| `zink-upload-cache-A1` | 59.358 s | 112.40 | 16.662 / 16.676 | 9 |
| `zink-upload-cache-A2` | 60.017 s | 113.44 | 16.659 / 16.675 | 2 |
| `zink-upload-matched-B2` | 59.400 s | 95.57 | 16.674 / 16.706 | 2 |

The later timestamp-coverage audit found a 183 ms hole between two snapshots
in the first control capture, so the initially reported pooled **17.39%**
estimate is withdrawn. Both candidate captures and the second control have
complete overlapping coverage. The remaining valid control is 95.57 fps,
versus 112.40 and 113.44 fps for the candidates, still supporting retention of
the upload-cache change. These are three valid trials, not two complete pairs.
Median ready-to-present latency stays approximately 17.36–17.39 ms. Both
candidate maxima are approximately 25 ms; stable 120 fps is still unmet.
A separate 12-second trace has no trace-error statistics and no
`allocateHelper` calls on the active KWin renderer ring; its ten remaining
calls are on Xorg's ring. Trace counts are diagnostic, not the acceptance fps.

Private evidence, exact binaries, bounded probe source, disassembly, raw
samples and logs are under
`.pi/device-debug/touch-perf-20261018/allocation-profile/`; presentation samples
use the table names under `.pi/device-debug/perf-60-20261018/`.

The measured library (19,276,056 bytes, SHA-256
`8e2ce18c5bc91f9c08c0641b530974663c99f2544f07f98c1659cf12700fdbb0`)
is installed atomically in the guest, with the original `a7aae0f8...` library
verified and saved at
`/var/lib/viruaisor/desktop-backups/upload-cache-20261019/`.
After a complete VM restart, both Xorg and KWin map the installed library.
The full-desktop checks are deployment regressions, separate from the
KWin-only paired comparison above:

| Capture / mode | Duration | Actual fps | p95 / p99 ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `zink-upload-final-wide`, 1920×1200 | 59.500 s | 111.95 | 16.663 / 16.678 | 5 |
| `zink-upload-final-native`, 3408×2272 | 59.683 s | 59.97 | 16.686 / 16.706 | 4 |
| `zink-upload-final-fit`, 1920×1280 | 59.908 s | 111.35 | 16.663 / 16.681 | 2 |

All three mode changes and five-point touch mappings pass, as do motion/RGB/
corner checks. Native and fit modes occupy the entire 3408×2272 viewport.
Escape produces one guest down/up pair while the App remains resumed and
focused. Native's maximum interval remains 33.333 ms, so the rare stalls are
not declared fixed. The new cache is retained for the paired throughput gain;
the stable-120 target remains open.

No temporary service drop-in or preload is active after the VM restart. Xorg
and KWin remain on CPUs 4–7 with their own-PID sync-fd opt-in. The Venus library,
CPU wrapper, installed APK (`c1ae1341...`) and native renderer (`0302b363...`)
are unchanged. The droid account is still locked; it was not modified. SELinux,
hidden-API settings and the profiler-related host values above are unchanged.
Validation output and individual input reports are in
`allocation-profile/validation.log` and `allocation-profile/validation/`.

## 2026-10-19: bounded release callback trial

The baseline renderer creates a new release Binder for every frame. The root
helper's BinderProxy map grew past 11,211 entries during the preceding session.
The trial keeps at most eight release endpoints per display connection, using
a positive 64-bit generation to reject duplicate and delayed callbacks. A
callback transfers the payload only once. Actual sync-file import into the
DMA-BUF, the release-fence worker, and the eight-buffer outstanding budget are
unchanged; budget is returned only when the real fence permits cleanup.
Disconnect drops the pool's references without invalidating references held by
consumer callbacks. Only failures before sending a frame can cancel its
payload locally. An ambiguous transaction failure retains ownership.

The new frame/release transactions use code 2; the root helper still accepts
the legacy code 1 frame format. Existing producer and release-caller UID
checks remain in place. Tests cover generation reuse, concurrent ownership,
stale/duplicate callbacks, failed allocation and unsent cancellation. Host
ASan/UBSan tests, the native display build and renderer link pass. A device NDK
test rejects the ordinary shell caller and, under the narrowly scoped root
invocation, checks bounded reuse, FD cleanup, ownership after disconnect, and
deferred cleanup using pipe readiness. That pipe test does not claim to test
DMA-BUF sync-file import. The existing four-mode real GPU ordering regression
also passed before the first capture. Five Android Parcel instrumentation
tests pass, including ticket-before-FD encoding and invalid-ticket rejection.
Gradle unit tests, debug APK/test APK assembly and arm64 packaging/pin checks
pass.

Candidate APK SHA-256 is
`43a000e94efd7f3dd7589f156daff5c34a9d37d82d3b3921bcca51cbe7e93c9c`;
its renderer is 14,118,808 bytes with SHA-256
`d8f16c579ead776979eacfed8eab138aef776780bcc95bef155f0d61547bb9c7`.
The baseline remains APK `c1ae1341...` / renderer `0302b363...`. Both use the
retained guest upload-cache library `8e2ce18c...` and Venus `dcb191f5...`.
Each APK change includes an ordinary App-driven full VM stop/start. The
candidate stopped normally before the baseline installation. RGB, moving
content and corner checks passed before each accepted capture.

The following captures preceded a device disconnection. They are preliminary
evidence, not the final comparison with verified matching CPU masks:

| Capture | Duration | Actual fps | p95 / p99 / max ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `release-pool-A1-native` | 179.367 s | 60.000 | 16.681 / 16.696 / 18.299 | 0 |
| `release-pool-A1-wide` | 59.800 s | 115.702 | 8.376 / 16.670 / 16.736 | 0 |
| `release-pool-B1-native` | 179.400 s | 59.955 | 16.685 / 16.706 / 33.339 | 13 |

Candidate root PID 16993 recorded 23–31 Binder proxies from frame 1 through
frame 24,900, with 30 at the end and no release/submission errors. Baseline
root PID 20343 logged map growth to 3,555, 6,107 and 8,399 entries. Eleven of
its thirteen native-resolution long intervals clustered at monotonic
227326.179–227326.504 seconds, immediately after its GC message at 227326.148.
This correlation motivated the allocation-pressure hypothesis; it does not
replace the alternating trials with verified CPU masks. Candidate
wide mode still missed 257 physical refresh opportunities, so this is not
stable 120 fps. Its median buffer-ready-to-present latency is 17.416 ms.

Live affinity inspection found a separate limitation: this guest reports
`cpu_capacity=1024` for every vCPU, so the current wrapper preserves the
default 0–9 allowed set for Xorg and KWin. The baseline restart explicitly
confirmed this. Earlier reports of 4–7 must not be assumed to persist across
VM restarts. No prime-core pinning or host policy change was introduced in
this experiment. Subsequent comparison runs must record actual masks and
keep the same policy on both sides; any CPU-selection correction needs its
own comparison.

Wireless ADB disconnected after the complete baseline native capture and log
collection, before its wide-mode resize. The discovered endpoint
`192.168.1.107:40507` became unreachable and a reconnect timed out. No baseline
wide result was accepted. At that interruption the tablet had the baseline
APK installed and the candidate was uncommitted. Evidence is under
`.pi/device-debug/touch-perf-20261018/post-upload/`; raw frame timestamps are
under `.pi/device-debug/perf-60-20261018/` using the capture names above.

After the user restored connectivity, the installed baseline APK and renderer
were rechecked. The device had restarted. New B2 and A2 VM starts both expose
capacity tiers 663 / 975 / 1024 in their guest CPU sysfs and device tree, and
both Xorg and KWin select 4–7 without an additional taskset operation. The
same guest graphics libraries remain installed. These new trials are kept
separate from the preliminary captures above.

The first resumed wide baseline capture (`release-pool-B2-wide`) is rejected:
several adjacent SurfaceFlinger snapshots have no shared timestamps, showing
that wireless polling failed to cover the finite timestamp history. The
apparent 333 ms gap and 112.46 fps must not be reported as performance results.
Native B2 has complete coverage and remains valid. Subsequent 120 Hz captures
reduce the polling sleep from 750 to 350 ms; all accepted captures must retain
overlap between every adjacent snapshot. Native captures keep the same 750 ms
sleep. A2 wide has complete coverage with 118 snapshots, versus the invalid
B2 wide capture's 64. This is measurement instrumentation only, not a display
or scheduler change.

The accepted resumed captures run in B2 → A2 → B3 → A3 order, with a full VM
restart for every APK change and an explicit Xorg/KWin 4–7 mask check at each
start. No host scheduler, frequency, power, permission or system-image setting
was changed. All four starts use the same guest library hashes and no service
drop-ins. The individual accepted windows are:

| Capture | Duration | Actual fps | p95 / p99 / max ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `release-pool-B2-native` | 179.883 s | 59.989 | 16.686 / 16.708 / 33.354 | 14 |
| `release-pool-A2-native` | 179.467 s | 59.989 | 16.682 / 16.699 / 33.336 | 6 |
| `release-pool-B3-native` | 179.467 s | 59.972 | 16.682 / 16.702 / 33.338 | 11 |
| `release-pool-A3-native` | 179.850 s | 59.994 | 16.696 / 16.724 / 25.012 | 6 |
| `release-pool-A2-wide` | 59.717 s | 113.737 | 16.656 / 16.674 / 25.000 | 2 |
| `release-pool-B3-wide` | 59.958 s | 112.245 | 16.662 / 16.678 / 25.004 | 2 |
| `release-pool-A3-wide` | 60.083 s | 113.675 | 16.654 / 16.676 / 25.008 | 2 |

The two native baselines contain 25 long intervals over 359.350 seconds; the
two candidates contain 12 over 359.317 seconds. Normalizing by time gives
4.174 versus 2.004 long intervals per minute, approximately **52% fewer**.
Mean native throughput is essentially unchanged: 59.981 versus 59.992 fps.
Native p95/p99 are not uniformly better and an A2 interval still reaches
33.336 ms, so this is a reduction in stall frequency, not a claim that all
stalls are fixed.

The two valid wide candidate captures pool to 113.706 fps against the single
valid wide baseline's 112.245 fps, approximately 1.3% higher. With only one
valid control window, this is a modest result and is not the principal reason
to retain the change. Their long-interval frequency remains about two per
minute and their median ready-to-present latency is 17.394–17.398 ms versus
17.378 ms for the baseline. Stable 120 fps remains unmet.

Both candidates keep the helper's proxy count at 23–31 (A2) or 24–31 (A3)
through more than 20,000 posted frames per session. Both baselines log growth
past 8,000 proxies during the native window and a subsequent sticky GC;
neither candidate logs another GC during its measured native window. No
submission or release-delivery errors are recorded. The bounded object
population and repeated reduction in native long intervals justify retaining
the release pool. This does not remove the remaining frame-pacing work.

Final validation keeps candidate VM PID 28571 alive while leaving the App for
Home, returning, and changing through wide, native and aspect-matched fit
modes. All mode acknowledgements, visible-pixel checks and five-point guest
touch mappings pass without a VM restart. Native and fit use the complete
3408×2272 viewport. The fit-mode motion capture has complete timestamp
coverage: 112.260 fps over 59.950 seconds, p95/p99 16.661/16.681 ms, maximum
25.005 ms and three intervals over 20 ms. The helper reaches at most 36
proxies across the additional connections and ends at 30, with no recorded
submission/release error.

All four GPU ordering probe modes pass after these mode changes, validating
DMA-BUF dependencies and all 1,024 GPU-written words on a separate device.
The scoped async export returns a real initially unsignaled fence; baseline,
foreign-PID and non-Zink modes retain the wait. Escape produces exactly one
guest down/up pair and leaves MainActivity resumed and focused. Existing
root and AVF access were sufficient; no new grant was needed. A successful
boot and Surface attachment are accompanied by changed guest pixels, not just
successful API returns. The droid account remains locked and was not changed.

Post-reboot host observations are SELinux Enforcing, global hidden-API policy
null, the same specific VirtualMachine exemption, `perf_event_paranoid=-1`,
`perf_event_max_sample_rate=32250`, `perf_cpu_time_max_percent=25`,
`perf_event_mlock_kb=516`, and `security.perf_harden=1`. The 516 KiB value is
from this new device boot, distinct from the earlier boot's 41000 KiB record;
this comparison did not run a profiler or write these settings. Final logs,
mode/touch reports and raw samples are in `post-upload/validation.log`,
`post-upload/validation/` and `release-pool-final-fit`. The measured candidate
APK remains installed for continued optimization.

## High-refresh presentation margin (2026-10-19)

A new 12-second Perfetto capture on the retained release-pool build shows
1,284 matched root buffer imports and closes. The gap from the previous
handler completion to the next buffer import has a 13.800 ms p95, while the
root handler itself has a 4.343 ms p95. Several 13–15 ms producer gaps are
followed by another 1–3 ms in the handler, making a full 16.7 ms departure
gap. This suggests that the half-period minimum presentation lead discards
some still-future high-refresh slots. These traced timings are diagnostic;
the trace's 108.07 layer updates per second are not mixed with untraced
performance measurements.

The candidate changes only the aligned high-refresh minimum lead to 1 ms.
It keeps the existing phase anchor, skips expired targets, bounds sink
back-pressure to one period, and advances the schedule only after successful
submission. Native 60 Hz, missing clocks and incompatible clocks retain the
half-period margin. GPU dependencies, release fences, the buffer budget and
CPU selection are unchanged. Unit regressions cover a late but usable slot,
the exact 1 ms boundary, an expired margin, aligned and unaligned native
operation, and high-refresh fallback. The new usable-slot assertions fail
against the old implementation; the final unit suite and APK build pass.

The baseline APK is the retained release-pool build
`43a000e94efd7f3dd7589f156daff5c34a9d37d82d3b3921bcca51cbe7e93c9c`.
The candidate APK is
`e1f20f862f3315d2e0f8d027bff053eedc7ee80749bf89acc0fa27edfca763bc`.
Both contain renderer
`d8f16c579ead776979eacfed8eab138aef776780bcc95bef155f0d61547bb9c7`.
Each comparison restarts the VM and verifies Xorg/KWin masks 4–7 plus the
same guest Gallium, Venus and CPU-wrapper hashes. Captures use the moving
RGB fixture, actual SurfaceFlinger presentation timestamps, 350 ms polling,
and overlap checks between every adjacent timestamp window. No profiler
runs during these comparisons.

The matched 60-second sequence is candidate A1 → baseline B1 → candidate A2
→ baseline B2. All four captures have complete timestamp coverage:

| Capture | Duration | Actual fps | p95 / p99 / max ms | Intervals >12 / >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `deadline-A1-wide` | 60.108 s | 117.754 | 8.376 / 16.666 / 25.010 | 133 / 2 |
| `deadline-B1-wide` | 59.858 s | 113.902 | 16.653 / 16.672 / 25.001 | 364 / 1 |
| `deadline-A2-wide` | 59.992 s | 117.700 | 8.384 / 16.667 / 24.997 | 137 / 1 |
| `deadline-B2-wide` | 59.767 s | 112.588 | 16.662 / 16.676 / 25.000 | 440 / 3 |

Pooling interval counts and elapsed time gives **113.246 → 117.727 fps**,
approximately **4.0% higher**. Missing 120 Hz refresh opportunities, estimated
from each presentation gap rounded to physical refresh periods, fall from
405.27 to 136.39 per minute, approximately **66% fewer**. There are no
presentation intervals under 4 ms on either side, so the gain is not a burst
of redundant presentations. The candidate p95 drops to one refresh interval;
the p99 and occasional 25 ms gaps remain. Median buffer-ready-to-present time
stays near 17.39 ms. This metric is not an input-latency measurement.

The four root logs contain no recorded submission/release error. Candidate
Binder proxy counts remain at 23–31; baseline counts remain at 23–31 or
23–32, consistent with both builds containing the retained release pool.

A fresh candidate VM then runs `deadline-A3-wide` for 180.125 seconds:
117.935 fps, p95/p99 8.375/16.665 ms, maximum 25.004 ms and two intervals
over 20 ms. All 348 snapshots overlap. Proxy count stays at 23–31 through
23,760 posted frames with no recorded submission/release error. This longer
window supports retaining the improvement but still does not meet a stable
120 fps target.

A separate candidate trace has 1,399 matched root buffer lifetimes. Among
frames with producer gaps over 12 ms, handler time falls from baseline
median/p95 2.665/3.650 ms to candidate 0.601/1.375 ms. This is consistent
with avoiding the unnecessary wait after a late arrival. The candidate trace
has 1,403 SurfaceFlinger buffer arrivals and 1,395 layer draws, with eight
sub-4-ms arrivals and eight pending-buffer replacement markers. There is
therefore a small amount of replacement, not a completely burst-free
submission stream; the untraced measurements above establish the actual
presentation benefit. Neither trace reports a nonzero error counter. Remaining
producer gaps and occasional long presentations are unresolved.

Final validation keeps VM PID 13181 alive across Home → App reattachment and
wide → native → aspect-matched fit mode changes. All three mode checks and
five-point guest touch mappings pass. Native and fit use the whole
3408×2272 viewport; the moving RGB fixture has intact corners. The additional
captures also have complete timestamp coverage:

| Capture | Duration | Actual fps | p95 / p99 / max ms | Intervals >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `deadline-final-native` | 180.100 s | 59.989 | 16.705 / 16.739 / 33.337 | 8 |
| `deadline-final-fit` | 59.708 s | 117.756 | 8.384 / 16.667 / 16.764 | 0 |

The native algorithm is unchanged, and this validation still has occasional
long frames. Six of its eight long presentation intervals retain roughly
16.7 ms desired/ready intervals, consistent with an extra display refresh
being missed after the buffer was ready; the other two also have roughly
33.3 ms desired/ready intervals. Neither average native throughput nor this
single validation window proves that all native stalls are resolved.

The four real GPU ordering modes pass again, including an initially
unsignaled async sync-file and all 1,024 GPU-written words consumed by a
separate device. Escape sends exactly one guest down/up pair and leaves the
App focused. Root proxies remain bounded at 36 across reattachment/modes
and end at 30, without recorded submission/release errors. Xorg and KWin
remain on 4–7; all guest library hashes and the locked droid account are
unchanged. Root/AVF access, VM boot, Surface attachment and actual moving
guest frames were confirmed using existing grants. Host observations remain
SELinux Enforcing, global hidden-API policy null, the existing specific
VirtualMachine exemption and the same post-reboot perf settings recorded
above. No host setting or prime-core affinity was changed.

Retain the 1 ms high-refresh margin on the repeated presentation improvement.
All 146 unit tests and the debug build pass, and the final source rebuild
produces the same candidate APK bytes. Evidence is in
`.pi/device-debug/touch-perf-20261018/deadline-profile/`, with presentation
captures under `.pi/device-debug/perf-60-20261018/`. The candidate stays
installed for continued work toward stable 120/60 fps.

## Application clocks and renewed Wayland comparison, 2026-10-19

The next comparison keeps the retained `9d9e0c6` APK, D8 renderer, current
Gallium/Venus libraries and desktop CPU mask 4–7. A temporary guest-only
Wayland session uses the current display-control modules and per-process
async sync-file opt-in for KWin/Xwayland. Its service override, helper bind
mount and session metadata are removed afterward. No host settings change.

Moving-window Surface counts alone overstate application progress. The
existing Xlib fixture redraws/moves a window and calls XSync, but does not
produce a distinct animation frame on every loop. Under Wayland its loop
runs at roughly 85–94 Hz while the compositor presents about 119.45 fps.
Those are useful pipeline measurements, not proof of 120 unique application
frames or correct animation timing.

A second fixture uses the same 900×300 QtQuick OpenGL window in both
sessions, with moving RGB bars, `frameSwapped` counts and a 5-second QML
timer compared against `Date.now()`. The GPU is Zink/Venus/Mali; the client
does not receive the desktop-only async sync-file opt-in. All valid captures
below have visible RGB motion, the requested duration and overlapping
SurfaceFlinger timestamp windows. Probe interposition and profilers are
absent from these captures.

| Capture | Workload / mode | Actual fps | p95 / p99 / max ms | >20 ms |
| --- | --- | ---: | ---: | ---: |
| `wayland-current-wide1` | Xlib, 1920×1200 / 120 Hz | 119.447 | 8.375 / 8.415 / 33.394 | 1 |
| `wayland-current-native2` | Xlib, native / 59.998 Hz | 60.000 | 16.684 / 16.708 / 24.994 | 9 |
| `wayland-current-gpu-native1` | QtQuick, fresh native | 60.000 | 16.690 / 16.713 / 25.008 | 6 |
| `wayland-current-gpu-wide1` | QtQuick, same window after native → 120 | 114.350 | 8.436 / 16.677 / 33.328 | 4 |
| `wayland-current-gpu-wide2` | QtQuick, restarted at 120 | 113.522 | 16.658 / 16.683 / 33.334 | 3 |
| `x11-current-gpu-wide2` | Same QtQuick, fresh 120 | 115.045 | 8.385 / 16.671 / 25.004 | 2 |
| `x11-current-gpu-native2` | Same QtQuick, fresh native | 60.000 | 16.682 / 16.698 / 25.002 | 3 |

Fresh native Wayland is now correctly timed: 300 swaps take 4,999–5,001 ms.
However, switching that open window to 120 Hz retains Qt's initial 16.67 ms
animation step: 300 swaps then take about 2.5–2.67 seconds. Restarting at
120 Hz selects 8.33 ms and gives 600 swaps per approximately 5.08–5.28
seconds. This is consistent with the startup refresh-rate cache in the
[Qt 5.15.12 animation driver](https://raw.githubusercontent.com/qt/qtdeclarative/v5.15.12-lts-lgpl/src/quick/scenegraph/qsgcontext.cpp).
Wayland is therefore not promoted to the default session.

X11 exposes a different, reproducible pacing failure. At 120 Hz the client
advances 600 swaps / five animation seconds in about 3.6–4.0 real seconds,
while actual presentation is only 115 fps. At native 60 Hz, the visible
client advances 300 swaps in approximately 2.7–3.0 seconds during the valid
capture, while the Surface still presents 60 fps. `frameSwapped` is a
submission-side observation; it is not a displayed-frame counter.

A separate, process-local GLX/XCB probe confirms:

- The queried swap interval is already 1. Neither `vblank_mode=3` nor
  reapplying interval 1 after the first swap restores pacing.
- Synchronous XCB Present requests have `options=8` (suboptimal reporting,
  without the asynchronous bit), but their target MSC stays at 1.
- Completed pixmap events repeatedly return `UST=0, MSC=0` in copy mode.
  The GLX OML synchronization query also fails.
- Ordinary-user, read-only DRM queries return `EOPNOTSUPP` (errno 95) for
  every virtual CRTC. The guest is using Xorg 21.1.11 and its existing
  6.12.92 Android guest kernel.

This local evidence identifies an absent guest presentation clock. The
[Xorg Present implementation](https://raw.githubusercontent.com/mirror/xserver/master/present/present_scmd.c)
also shows the relevant failure path: a failed CRTC clock query leaves
zero counters, and failed vblank scheduling falls through to immediate
execution. A guest display-server clock fallback is the next investigation;
changing the host kernel or globally disabling synchronization is not needed
to test it. The probe is removed, and no new pacing workaround is retained.

Excluded attempts are `wayland-current-native1` (requested 60,000 mHz when
the session advertised 59,998), `x11-current-gpu-wide1` (stale Wayland
Xauthority on a diagnostic connection), and `x11-current-gpu-native1`
(guest lock screen). None has a valid FPS result. The normal X11 diagnostic
connection is recreated, and the native retry uses a bounded guest DBus
screensaver inhibitor after unlocking the current droid session. Account
credentials are not changed.

Evidence is under `.pi/device-debug/touch-perf-20261018/wayland-current/`,
including `all-client-clocks.log`, `drm-clock.log`,
`native-complete-probe2.log`, and the exact QML/probe sources. Presentation
captures are under `.pi/device-debug/perf-60-20261018/`. These results do
not establish stable 120 fps or stall-free native 60 fps; the goal remains
open with the previously validated queue and presentation changes retained.

Restoration keeps VM PID 13181 alive, recreates the ordinary X11 probe and
diagnostic connection, and passes wide → native → aspect-matched fit mode
switches plus five-point touch mapping in all three modes. The App remains
focused with actual guest pixels visible. Xorg/KWin remain on 4–7 with
their own-PID async scope, the three guest hashes are unchanged, and droid
remains locked (`L`). The restoration root log has 29–30 Binder proxies
through 219,660 posted frames and no submission/release error. No Wayland
drop-in, display-helper mount or session metadata remains. The client GLX
probe, old inhibitor and temporary port-4054 diagnostic are stopped. Host
SELinux remains Enforcing and the global hidden-API policy remains null;
the existing specific VirtualMachine exemption is unchanged.

## Unretained guest Xorg software presentation clock, 2026-10-19

A private, explicitly enabled fallback addresses the zero-UST/MSC failure in the paired
Xorg 21.1.11. It activates only for one active output with a valid mode when
the driver clock query fails. It derives a period from the actual mode clock
and scan totals, preserves MSC across rate changes, and uses Xorg's existing
software-vblank queue. Millisecond waits round upward and callbacks recheck
the target. Wait-fence re-execution reads the same clock; real GPU fences,
pixmap ownership, Android scheduling and CPU affinity are unchanged. This
software pacing clock does not measure physical scanout.

The initial trial binds a private build over the guest server and compares
the same binary with the environment opt-in on/off/on. The APK (`e1f20f86…`),
D8 renderer, guest Gallium/Venus and VM PID 13181 stay fixed. Xorg and KWin
remain on cores 4–7. The workload is the same QtQuick GPU animation as above,
with fresh clients after mode changes. Clock interposition and CPU sampling
are separate from FPS capture; every valid capture has visible RGB motion,
the requested duration and overlapping timestamp windows.

| Capture | Clock / mode | Seconds | Actual fps | p95 / p99 / max ms | >20 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| `x11-clock-a2-wide` | on / 1920×1200 | 59.692 | 118.308 | 8.377 / 16.664 / 16.736 | 0 |
| `x11-clock-b1-wide` | off / 1920×1200 | 59.883 | 114.139 | 16.647 / 16.673 / 16.757 | 0 |
| `x11-clock-a3-wide` | on / 1920×1200 | 179.717 | 116.967 | 8.365 / 16.670 / 25.040 | 4 |
| `x11-clock-a2-native` | on / 3408×2272 | 59.367 | 60.000 | 16.683 / 16.700 / 16.783 | 0 |
| `x11-clock-b1-native` | off / 3408×2272 | 59.350 | 60.000 | 16.680 / 16.699 / 25.005 | 4 |
| `x11-clock-a3-native` | on / 3408×2272 | 179.700 | 59.989 | 16.685 / 16.708 / 33.330 | 3 |
| `x11-clock-final-wide` | final private build on / 1920×1200 | 60.133 | 112.334 | 16.664 / 16.675 / 16.804 | 0 |
| `x11-clock-final-native` | final private build on / 3408×2272 | 59.983 | 58.966 | 16.692 / 25.006 / 33.352 | 105 |
| `x11-clock-b2-wide` | same private build off / 1920×1200 | 59.692 | 116.331 | 8.371 / 16.669 / 25.010 | 2 |
| `x11-clock-b2-native` | same private build off / 3408×2272 | 59.333 | 59.983 | 16.681 / 16.698 / 33.342 | 4 |

The first two candidate wide captures pool to 117.302 fps, 2.77% above the
same-binary control. Both candidate p95 intervals are near one refresh;
the first control p95 is near two. However, the private-path repeat regresses
in both modes, particularly native long frames, and disabling the clock in
that same final binary restores cadence. The initial gain is therefore not
a reliable retention result. The clock correctness fix remains experimental
until its interaction with compositor scheduling is resolved. The proposed
production integration was withdrawn after the additional trials below.

The correctness change is larger: without the fallback, 600 swaps / five
animation seconds take roughly 3.6–4.2 real seconds at 120 Hz, and 300 swaps
often take 2.45–3.6 seconds at native 60 Hz. With the fallback, Present
completions contain positive, advancing UST/MSC; the same animation takes
approximately five real seconds. The native long run stays around
4,999–5,001 ms per timer; late wide samples are about 5,016–5,128 ms. The GLX
OML synchronization query still fails and is not claimed fixed. Qt 5's cached
startup animation step across refresh changes is a separate remaining issue.

Separate 10-second guest CPU observations are not a power measurement. At
wide resolution, summed QML/Xorg/KWin CPU is about 64.0% and 58.2% of one
core in the candidate samples, versus 58.7% in the control. No CPU or power
saving is established. No prime-core binding, frequency or host policy changes
are made.

The clock tests first fail on a counter reset during rate changes and then
on an oversized timer delay. The final helper preserves the counter and caps
long waits at INT32_MAX because Xorg compares deadlines as signed 32-bit
differences. The initial A/B binary is `a557f445…`; the final build includes
that distant-target correction and is
`d22867954773d5071727e65d69ad908a882271bbf027689d663381f2a4b60e69`.
The official source archive SHA-256 is
`1d3dadbd57fb86b16a018e9f5f957aeeadf744f56c0553f55737628d06d326ef`.
Repeated builds produce the same final binary. The normal tested deadlines
are one refresh away, so the long-delay cap does not alter the A/B workload.

The experimental `build-guest-xorg.sh` verifies that archive, applies the patch, tests
the clock arithmetic and builds against the paired guest libraries. The
proposed bundle installed `/opt/viruaisor/xorg/Xorg`, its license and source/binary
hashes. Its `desktop-xorg` selected that fixed executable and enabled its clock;
KWin/clients do not inherit the opt-in. Older bundles without the executable
retain the distro path. All 34 guest Python tests pass, including private
server selection, missing/non-executable fallback and opt-in isolation. The
small QML fixture, typed XCB completion probe, log checker and the withdrawn
integration are preserved under the private `x11-clock/unretained-proposal/`
evidence directory for reproduction. They are not installed as desktop preload
hooks or shipped as a default server.

The first private-path installation removed the experimental Xorg bind mount
and service override. Xorg ran directly from its private path as uid 1000; the distro
server remains `381e81b8…`. Both display processes still use 4–7, each with
its own-PID async sync-file scope. droid remains locked (`L`). The first
unmount attempt returned busy immediately after the desktop stop timed out;
after verifying that no desktop processes remained, a normal retry succeeded.
No forced/lazy unmount or wider permission was used.

The cancelled `x11-clock-A1-native` attempt is excluded: a rejected restart
name had left a probe attached. Its case-insensitive name collision retry was
also rejected before capture. All reported runs use fresh names and confirm
the client has no preload/profiler. Raw evidence is under
`.pi/device-debug/touch-perf-20261018/x11-clock/`, with presentation timestamps
under `.pi/device-debug/perf-60-20261018/`.

### Compositor clock and wait experiments

The paired KWin 5.27.10 GLX backend uses a software vblank monitor when buffer
age is disabled, in addition to its default GLX swap interval of one. Its
software monitor aligns to the monotonic epoch and an integer milliHz rate;
the native mode is 59,997 milliHz. The first Xorg fallback instead chose the
first request as its epoch and used the exact mode period. A private variant
aligns Xorg to KWin's phase and period, preserving the MSC across changes.
These are software scheduling clocks, not physical presentation feedback.
The relevant upstream implementations are the paired
[GLX backend](https://github.com/KDE/kwin/blob/v5.27.10/src/backends/x11/standalone/x11_standalone_glx_backend.cpp)
and its
[software vblank monitor](https://github.com/KDE/kwin/blob/v5.27.10/src/platformsupport/vsyncconvenience/softwarevsyncmonitor.cpp).

| Capture | Configuration | Actual fps | p95 / p99 / max ms | >20 ms |
| --- | --- | ---: | ---: | ---: |
| `x11-clock-events1-native` | Initial clock, buffer age and Intel swap events enabled | 52.801 | 16.668 / 33.333 / 50.002 | 583 |
| `x11-clock-phase1-native` | Aligned phase, normal KWin swap interval 1 | 60.000 | 16.683 / 16.699 / 25.024 | 1 |
| `x11-clock-phase1-wide` | Same, 1920×1200 | 112.920 | 16.661 / 16.676 / 25.000 | 2 |
| `x11-clock-nosync1-native` | Aligned phase, KWin-only swap interval 0 | 59.832 | 16.679 / 16.702 / 33.350 | 17 |
| `x11-clock-nosync1-wide` | Same, 1920×1200 | 117.056 | 8.357 / 16.669 / 16.733 | 0 |

These are complete 60-second captures with visible GPU motion, fresh clients,
and overlapping timestamp windows. The swap-event variant is rejected for
the native regression; its interrupted wide capture is excluded. The full
repaint effect remains loaded in every trial. Buffer age is restored to zero
after that rejected trial.

Removing the compositor's second wait helps the wide trial, but worsens
native long frames. It is not enabled globally or committed. Application
GPU fences and their swap interval are untouched. A mode-dependent private
effect experiment is being evaluated separately. Both processes remain on
cores 4–7; no Android policy, CPU frequency or prime-core binding changes.

The aligned clock's mode-change test also detects a backward current-MSC UST
when the new phase precedes the previous tick. A second experimental helper
preserves that tick's UST while keeping future ticks phase-aligned. Its test
fails before the correction and passes afterward. This private variant is
not yet the proposed production source or a retained performance result.

The adaptive experiment keeps the existing full repaint and buffer age zero.
It changes only KWin's GLX swap interval: one below 90 Hz and zero at 90–250 Hz.
The opt-in is confined to a temporary compositor wrapper, and the effect is
bound inside the guest. KWin's process journal confirms native `59997 / 1 / 1`
and wide `120000 / 0 / 0` for rate / requested interval / queried interval.
Filtering solely by the desktop systemd unit missed these messages; filtering
by the actual KWin PID finds them. The effect mapping and SHA-256 were also
verified from that process. No interval changes are inferred from fps alone.

| Capture | Seconds | Actual fps | p95 / p99 / max ms | >20 ms |
| --- | ---: | ---: | ---: | ---: |
| `x11-clock-adaptive1-native` | 59.917 | 59.967 | 16.685 / 16.721 / 33.335 | 3 |
| `x11-clock-adaptive1-wide` | 59.992 | 117.716 | 8.357 / 16.666 / 25.012 | 2 |
| `x11-clock-adaptive2-native` | 180.050 | 59.989 | 16.683 / 16.710 / 33.331 | 5 |
| `x11-clock-adaptive2-wide` | 180.017 | 116.206 | 8.377 / 16.671 / 25.010 | 7 |

The second adaptive trial uses the UST-continuity correction, Xorg SHA-256
`1a71ee579ccf4272b94fd4a4b01caa7f4e218e20b2a054051b7fbe98385a9b60`.
The expanded arithmetic test covers 5,000 varied rate changes, checking
nondecreasing MSC/UST, phase-aligned future ticks and no early timer deadline.
The wide repeat loses the initial apparent throughput gain. Stable 120 fps is
not established and no performance retention claim is made.

The next same-session comparison changes only KWin's `LatencyPolicy` from its
default to `Low`, then restores the exact prior configuration. The paired
[render loop](https://github.com/KDE/kwin/blob/v5.27.10/src/core/renderloop.cpp)
uses that policy together with the measured rendering budget and a safety
margin. The experiment tests whether starting composition later helps it see
the newly completed client frame. Hardware composition and full repaint stay
active; each configuration reload also confirms swap interval zero at 120 Hz.

| Capture | Setting | Seconds | Actual fps | p95 / p99 / max ms | >20 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| `x11-clock-low1-wide` | Low | 59.825 | 116.072 | 8.381 / 16.671 / 25.005 | 2 |
| `x11-clock-default3-wide` | Restored default | 59.800 | 116.839 | 8.370 / 16.669 / 16.746 | 0 |

This does not establish a benefit. The default is restored. KWin's support
dump prints empty values for these enum properties, so the report does not
claim an independent numeric runtime readback of the latency policy; the
configuration edit, reload and restoration are recorded in the raw evidence.

The software clock improves animation timing, but its initial fps gain does
not reproduce consistently. Neither the clock, adaptive swap effect nor latency
setting is retained in the default runtime or production build. Their private
sources, tests, binary hashes and complete captures remain available for a later
investigation. The production integration files are restored to HEAD. The
private-server and launcher staging changes in the Manjaro build root are
undone too; no root bundle is regenerated.

Final rollback verifies distro Xorg `381e81b8…`, desktop launcher `4e577f33…`,
and original full-repaint effect `27968dcd…`. The private server executable,
service override, effect bind and software-clock/adaptive environment flags
are absent. A normal unmount retry succeeds after the first attempt reports
busy; no forced/lazy unmount is used. The recovery timer is stopped. Xorg and
KWin run as droid on cores 4–7 with their existing own-PID sync-fd scope, hardware
Zink/Venus/Mali composition is active, and droid remains locked. The VM PID
is unchanged at 13181. The stable-120/native-60 goal remains open.

After restoration, the five native-resolution touch targets pass within one
guest pixel. Escape reaches the guest as both key-down and key-up while Android
focus remains in the fullscreen App. A real Android screenshot shows the
complete native desktop, one cursor and no black tiles. All 33 baseline guest
Python tests pass; `git diff --check` is clean. The independent diagnostic
service is stopped, SELinux remains Enforcing, and the APK/renderer and Android
permissions are unchanged. The VM stays available for the ongoing goal.

## Producer synchronization timing and static archive rebuilds (2026-10-19)

A fresh untraced 1920×1200 baseline (`producer-timing-baseline-wide`) records
117.569 presentations/s over 59.658 s, p95 8.379 ms, p99 16.666 ms and no
intervals over 20 ms. RGB, motion, corner checks and timestamp coverage pass.
The retained APK remains `e1f20f86…`, with renderer `d8f16c57…`; this is still
below stable 120 fps.

A 12-second Binder/scheduler trace records 1,398 frame calls. The gap from the
previous call's return to the next call has p95 11.652 ms; synchronous frame
handling averages 1.437 ms (p95 3.331 ms). These gaps include waiting for the
next guest frame, so they alone cannot identify a GPU bottleneck. Thread-state
totals must be restricted to the actual frame-calling utid and clipped to the
capture interval: grouping every thread named `v_gpu` incorrectly sums several
threads. The corrected frame-calling thread spends 869.518 ms running and
10,708.311 ms sleeping in an 11,961.357 ms interval.

Temporary native timing exposed a build defect before providing usable stage
measurements. Updating the static renderer and changing only crosvm's final
crate metadata leaves Cargo's cached rutabaga rlib holding the old archive.
The first two profiling binaries contain zero timing markers even though the
rebuilt native archive contains them. Their traces are invalid for stage
attribution. An initial suspicion about Android user-build tracing restrictions
was not established; it must not be treated as the reason for these omissions.

The retained build fix makes the local rutabaga build script declare each
existing native `.a` file reported by pkg-config as a Cargo dependency. The
existing final-crate metadata is still used for directly linked display code.
`scripts/test-renderer-archive-rebuild.sh` exercises the actual rutabaga build
script in a small two-crate Cargo fixture: a native-only edit from return value
1 to 2 leaves the old executable returning 1 before the fix, then correctly
returns 2 with the dependency declarations. The test runs offline with the
cached pkg-config crate. This fixes build correctness; no fps gain is claimed.
The earlier queue-cache retest logs show rutabaga recompilation for both
candidate and restoration, so this discovery does not invalidate that retest.

The corrected profiling binary (`8a32ba8a…`, 14,118,480 bytes) contains eleven
timing marker strings and emits them on-device. Ten scoped timing locations
cover proxy requests/replies, ring snapshots/draining and fence operations.
They use the existing graphics trace category in the test process; no system
properties, permissions, SELinux policy or CPU affinity are changed. No fence,
timeout, queue order or buffer reuse rule is removed. The exact temporary patch
and baseline source hashes are archived privately under `producer-timing/`.

The first instrumented capture has an initial long gap, so the steady repeat is
used for attribution. The repeat contains 1,390 frame calls, 9,732 completed
GPU fence waits and 13,906 bounded ring waits, with no recorded trace loss or
error statistics. It is a diagnostic capture, not an fps acceptance run.

| Stage | Mean ms | p95 ms | Maximum ms |
| --- | ---: | ---: | ---: |
| Bounded ring drain, all contexts | 0.006 | 0.003 | 0.613 |
| GPU fence wait, all contexts | 0.431 | 2.339 | 7.977 |
| GPU fence wait, renderer context 7 | 1.665 | 4.696 | 7.977 |
| GPU fence wait, renderer context 4 | 0.673 | 3.858 | 5.655 |

For the 78 producer gaps exceeding 12 ms, the mean gap is 13.752 ms and summed
proxy reply waiting on the single frame-calling thread averages 5.035 ms.
For 1,311 shorter gaps those values are 6.747 and 2.024 ms. This supports
investigating GPU completion on the submission path, while the remaining
8.716 ms mean time outside proxy waits in long gaps prevents attributing every
missed refresh to GPU waiting. Context numbers are renderer IDs; they have not
yet been independently mapped to individual guest applications. The timing
alone does not justify skipping synchronization or claiming asynchronous
acquire-fence submission will meet the target.

The instrumented runtime passes RGB/motion/corner checks, and a real Android
screenshot shows the complete desktop and one guest cursor. Profiling source
edits are restored byte-for-byte before rebuilding the normal renderer. The
packaged native binary and its strict installer pin are restored from the
verified baseline. Only the build dependency fix, its regression test and this
evidence are intended for retention; the temporary tracing is not shipped.

Restoration is complete: the rebuilt normal renderer is byte-identical to
`d8f16c57…` (14,118,808 bytes), contains no profiling markers, and the installed
APK is again `e1f20f86…`. Both Android unit tests and debug assembly pass.
The existing privileged bridge remains available, no new Android grants are
requested, AVF boots VM PID 5703, and its attached fullscreen Surface displays
actual animated guest frames. SELinux is Enforcing. Xorg PID 384 and KWin PID
490 run as uid 1000 on cores 4–7 with their own-PID sync-fd opt-in, original
Xorg/effect hashes and no service overrides. droid is still locked.

The restored untraced run (`producer-timing-restored-wide`) records 117.930
presentations/s over 59.908 s, p95 8.375 ms, p99 16.666 ms and three intervals
over 20 ms (maximum 25.005 ms). All 114 timestamp snapshots have complete
coverage; RGB/motion/corner checks pass and the restored screenshot again
shows one guest cursor. These baseline repeats are not evidence of an
optimization gain. The stable-120/native-60 goal remains open; GPU completion
handoff and the remaining time outside proxy waiting require further work.
The VM and bounded guest screen inhibitor remain active for that investigation.
