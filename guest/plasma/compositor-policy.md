# Desktop presentation policy

The default Qt software renderer can produce about 125 updates per second with
the software animation timing plugin. At 1920×1200, the additional KWin OpenGL
composition pass still limits actual Android presentation to about 115–116 fps.
Suspending that pass reaches about 120 fps in the measured animated-window
workload. Native resolution retains OpenGL composition because its presentation
timing is better with composition enabled.

`compositor-policy` observes Qt screen notifications in the fixed droid desktop
session. It uses the existing KWin session DBus interface to suspend composition
only for one 1920×1200 or 1920×1280 screen within 1 Hz of 120 Hz, including portrait
orientation. Other modes restore any suspension it owns. This disables desktop
effects such as shadows in the two high-refresh presets; Xorg, the window manager,
GPU acceleration, input handling and GPU synchronization remain in place.

The observer does not resume a compositor that was already disabled when it
started. It watches KWin's bus owner so a compositor replacement can apply the
current mode again. A lock prevents two observers from owning the policy.
SIGTERM/SIGINT use a pipe to request a normal Qt event-loop exit and restoration.
The `desktop-compositor` supervisor also restores an owned suspension if its child
crashes. No persistent KWin compositing preference, CPU affinity, Android setting,
driver or host policy is changed by the observer.

`plasma-session` starts the supervisor only after its existing OpenGL-on-Mali and
full-repaint readiness checks pass, and terminates it when the session ends.
`scripts/build-compositor-policy.sh` cross-compiles against the paired guest Qt
public APIs; it does not register a host binfmt handler or run a guest chroot.

Validation uses actual SurfaceFlinger presentation timestamps with complete
collector coverage and separate screenshot/motion checks. Run:

```sh
node scripts/assert-desktop-cadence.cjs report.json 120 180
```

The cadence gate requires at least 179 seconds from a requested 180-second
capture, a mean within 0.5% of the requested refresh rate, and a p99 interval no
greater than 1.2 frame periods. It prints maximum intervals and counts over 20 ms
separately. Passing this gate does not establish zero long frames or guarantee
the same result for every application. Native measurements still contain a small
number of roughly 33 ms intervals. See the performance validation document for
the paired control, restoration, lifecycle and device evidence.
