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
