# Guest Xorg Present clock

The paired virtio DRM device does not implement the CRTC sequence query.
Unmodified Xorg 21.1.11 completes synchronous Present copies with zero UST/MSC
and no vblank wait. QtQuick then advances animation time faster than real time.

`present-clock.patch` selects Xorg's existing software-vblank path only with
`VIRUAISOR_X11_SOFTWARE_CLOCK=1`, exactly one active output, a valid 10–250 Hz
mode and a failed driver clock query. `present-copy-clock.patch` also covers
windowed copies into the screen pixmap while composition is suspended. Without
that case, MAILBOX completes every copy immediately with zero UST/MSC and cannot
retire superseded frames before doing the GPU copy. The fullscreen screen
pixmap, active flip and pending flip pixmaps keep the driver's CRTC. Selecting
the software path would otherwise disable Present flipping
for the compositor and add a full-screen copy. Its monotonic clock follows that mode's
actual dot clock and scan totals. MSC stays continuous across 60/120 Hz changes;
timer callbacks recheck the target after rounding waits upward. Wait-fence
re-execution reads the same clock. GPU fences and pixmap ownership are unchanged.
This is a pacing clock, not a measurement of physical scanout.

Build with `bash scripts/build-guest-xorg.sh` in the Linux build environment.
It verifies the official 21.1.11 tarball SHA-256, applies the patch, runs the C
clock tests and builds against the paired Manjaro root (xorg-server 21.1.11-1).
That root needs its target development libraries and the header-only xtrans
development files. `MANJARO_ROOT` and `VIRUAISOR_XORG_WORK_DIR` override the build
directories; changed compiler inputs require a fresh work directory. Outputs,
source/binary hashes and the upstream license go to the ignored
`.pi/device-debug/manjaro-plasma/xorg/` directory.

The build tests clock continuity, timer bounds and the actual patched CRTC
selection function. The scope harness uses small driver/pixmap stand-ins to
verify that redirected and non-fullscreen screen-pixmap windows get a clock
while fullscreen/current/pending flip paths and the original enablement gates
are preserved. Runtime
Present probes must additionally confirm KWin flip completions and advancing
client UST/MSC; the harness alone cannot prove physical performance.

The bundle installs the server at `/opt/viruaisor/xorg/Xorg`. `desktop-xorg`
executes that fixed path with the opt-in and the existing large-core policy.
The distro Xorg is preserved; an older bundle without the private executable
continues using `/usr/bin/Xorg`. KWin and ordinary clients do not receive the
clock opt-in. Removing the private binary and restarting the guest desktop
returns to the distro server. No Android host component or global setting changes.

The runtime regression uses a QtQuick GPU animation with Present completion
timestamps and compares its animation timer to wall time, separately from
untraced SurfaceFlinger presentation captures. The clock arithmetic test alone
does not prove pacing, visual correctness or 120 fps. See
`docs/touch-cursor-password-performance-validation.md` for the device results
and outstanding limitations, including Qt 5's cached startup refresh rate.

For that regression, copy the build output `present-clock-probe.so` and
`gpu-motion.qml` into a temporary directory in the guest, then run from its
ordinary graphical session:

```sh
timeout 25 env -u VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID \
    QT_QPA_PLATFORM=xcb QT_XCB_GL_INTEGRATION=xcb_glx QSG_RENDER_LOOP=threaded \
    QSG_INFO=1 LD_PRELOAD="$PWD/present-clock-probe.so" \
    qmlscene "$PWD/gpu-motion.qml" 2>clock.log
```

The timeout exit is expected. Retrieve the log and run
`node guest/xorg/check-client-clock.cjs clock.log`. The original server fails
on zero Present timestamps; the patched server must return advancing timestamps
and five animation seconds in 4.5–5.5 real seconds, excluding startup. Use a
fresh client at each refresh rate for this clock test. Test an already open
client across refresh changes separately. Remove `LD_PRELOAD` for all FPS tests.

For the non-composited MAILBOX regression, run the following from a graphical
session with composition already suspended, then retrieve the log:

```sh
LD_PRELOAD="$PWD/present-clock-probe.so" timeout -k 2s 15s \
    vkmark -b clear:duration=5 >vkmark-clock.log 2>&1
node guest/xorg/check-vkmark-clock.cjs vkmark-clock.log
```

The checker rejects zero/backward clocks, a stuck clock, a missing final score,
and no observed superseded frame. It targets the fast windowed MAILBOX clear
workload on the paired device, not a slow workload or FIFO/fullscreen mode.
Pixel recordings and the full uninstrumented suite remain separate checks; see
`docs/guest/vkmark.md` for the before/after measurements.
