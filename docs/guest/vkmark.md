# vkmark in the Manjaro Venus guest

## Acceptance target

Use the unmodified distribution package at `/usr/bin/vkmark`. Image builds no
longer compile a private vkmark or install a `/usr/local/bin/vkmark` override;
they remove those old artifacts when reusing a build root. The memory-type
workaround formerly applied to vkmark is not a driver compatibility fix.

On 2026-10-22 the unmodified `vkmark 20220318-1` package (57 files, zero altered)
reproduced `Couldn't find matching memory type` in the cube scene. The program
still returned exit code zero and score zero. Acceptance checks every requested
scene in addition to process status. On 2026-10-23 the repaired renderer passed
coherent and cached compute checks after a fresh VM restart, displayed live
frames, and completed all 13 unmodified vkmark scenes: XCB score 1027 and
nested Weston/Wayland score 1092. A separate two-second cube test scored 1197.
The 298-point global cache-maintenance regression is fixed by scoping cache
operations to the allocation's Venus context. See [the KVM and renderer
investigation](coherent-memory.md) for the measured limits.

The older results below used the modified benchmark. They document the earlier
investigations and must not be cited as unmodified vkmark compatibility results.

## Historical modified benchmark investigations

The paired Android renderer can import BGRA8 UNORM `AHardwareBuffer` images for
presentation. When vkmark chose BGRA8 sRGB, its swapchain image fell back to a
BLOB allocation. Xorg then failed to import that BLOB as an image and the
desktop stopped updating. The guest Mesa WSI patch advertises BGRA8 UNORM for
X11 and Wayland when `VIRUAISOR_AHB_UNORM_WSI` is set. The Manjaro session sets
this flag in `/etc/viruaisor/venus.env`.

The former AHB import path exposed no usable `HOST_COHERENT` memory. Native
Mali does provide coherent memory for ordinary buffers; the limitation is in
the virtualized sharing path. The stock vkmark scenes require coherent memory
and skip benchmarks when none matches. The former private build removed this
requirement and explicitly flushed mapped writes before GPU use. Its binary
and X11/Wayland plugins were installed under `/opt/viruaisor/vkmark`.

The desktop session is X11. The former `/usr/local/bin/vkmark` wrapper started a
nested Weston X11 compositor for `--winsys wayland`, waited for its socket, and
stopped it when vkmark exited. This wrapper is no longer shipped. Wayland tests
must start their compositor explicitly and invoke `/usr/bin/vkmark`.

During the initial crash fix, `vkmark` completed all 13 default scenes with exit code 0
and score 185 using the first patched vkmark build. The final build then passed
an X11 vertex scene and completed all 13 default scenes under
`vkmark --winsys wayland` with exit code 0 and score 277. The tablet locked
before a final full X11 rerun. Scores vary with load and display timing.

## Flicker and presentation latency (2026-10-22)

The live device identifies itself as M367FC, XRing O3, with a
`Mali-G2-Ultra-NX MC16` GPU. The guest uses Mesa 25.0.7 at
`742a20f48c59e8649533c84c4d49dd95b403f5da` and vkmark at
`cf45f2faee236fd1118be2fcd27e4f2a91fc2e40` (which prints `2017.08`).
The guest desktop is 1920x1280; vkmark's default window is 800x600.

### Root cause of the black frames

Weston's GL renderer supplies client vertex arrays. Mesa GLthread copies these
arrays into an upload buffer that stays mapped while draws execute, without
flushing each written range. Zink advertised
`allow_mapped_buffers_during_execution = true` even though its buffer memory
requirements probe found **no compatible coherent host-visible memory type**.
The GPU therefore read stale vertex data. A failed compositor draw could clear
the output to black without drawing the client image back over it.

`zink-noncoherent-glthread.patch` ties this capability to the already-probed
`buffer_map_persistent_coherent` capability. This disables GLthread's unsuitable
uploader on this device. The ordinary Gallium uploader explicitly flushes its
writes. Coherent devices retain GLthread. Vulkan rendering remains enabled.

`guest/venus-probe/gles-client-arrays.c` isolates this independently of vkmark,
Wayland, or exported images. It draws changing colors through clear, client
vertices, client indices, and VBO paths and checks actual GPU pixel readback.
Before the fix, client vertices failed 236/240 frames and client indices failed
239/240; clear and VBO both passed. There were no GL errors on the bad frames.

The fixed-color vkmark test is also necessary: the default `clear` scene
intentionally cycles colors, so brightness changes in that scene alone are
not evidence of this bug. Disabling EGL buffer age was investigated and did
not resolve the problem; `MESA_EXTENSION_OVERRIDE` does not disable those EGL
extensions and is not part of the fix.

### Presentation overhead

In the original Wayland clear test, exporting the presentation semaphore and
waiting for its guest sync fd took about 1.9 ms per frame, compared with about
44 microseconds spent creating the fd. At 800x600 this limited clear to about
441 FPS. Reducing the pixel area 16-fold only raised it to 527 FPS. In X11 the
same wait took about 4.4 ms, with clear and cube around 120 FPS.

`guest-wsi-ahb-completed-present.patch` selects Venus' existing completed-present
fallback for the explicitly enabled `VIRUAISOR_AHB_UNORM_WSI` transport. Instead
of exporting and round-tripping a guest fence on every present, it waits for
the rendering queue before handing the image to the window system. GPU work
must still complete; this is not an early-return or frame-dropping optimization.
Application external-semaphore support is unchanged. Other Venus deployments
are unaffected. The two overlapping WSI patches are normalized by the build
scripts so rebuilding an already-patched tree works.

With correct compositor drawing, an A/B fixed-gray test increased Wayland
clear from 511 to 1895 FPS. X11 still has appreciable per-frame server and
presentation overhead: the candidate measured clear/cube at 201/216 FPS at
800x600 and 235/241 FPS at 200x150. These are window-system benchmark results,
not measurements of the native GPU's peak shading or fill rate.

### Public comparison points

There was no directly comparable public O3/G2-Ultra-NX native vkmark result in
the sources checked. Published mobile flagship results provide context only:

| GPU / device | Published vkmark score | Environment |
| --- | ---: | --- |
| Adreno 830 / REDMI K80 Pro | 1153 | vkmark 2025.01, Mesa 26.2 development build, Debian 13 PRoot |
| Xclipse 940 / Galaxy S24+ | 2295 | vkmark 2017.08, Android Vulkan WSI wrapper, Mesa 24.2.5 |

Sources: [Adreno benchmark log](https://github.com/lfdevs/mesa-for-android-container/blob/adreno-main/.github/docs/common/benchmark-result.md#adreno-830)
and [Xclipse firsthand report](https://github.com/xMeM/vulkan-wsi-layer/issues/25).
The latter also reports a flickering clear scene. Different benchmark versions,
drivers, display servers, synchronization, and virtualization make these
unsuitable as an exact expected score or a GPU ranking. The same-device A/B
measurements and pixel checks establish the effect of this fix.

### Final device verification

Both production libraries were replaced by atomic rename while the VM and
desktop remained running. No Android reinstall, VM stop, or desktop restart
was needed. New test processes loaded the installed libraries, without the
experimental ICDs, preload shims, or `mesa_glthread=false` workaround.

| Check | Result |
| --- | --- |
| `vkmark` with no arguments | All 13 default scenes, exit 0, score **240** |
| `vkmark --winsys wayland` | All 13 default scenes, exit 0, score **1645** |
| GLES clear / client arrays / client indices / VBO | **0 incorrect pixels in 240 frames per path** |
| X11 fixed gray, 20-second run | Exit 0; 1558 captured frames checked over an interior 15-second interval; zero black/incorrect frames |
| Wayland fixed gray, 20-second run | Exit 0; 899 captured frames checked over an interior 15-second interval; zero black/incorrect frames |

The live pre-fix Wayland run scored 480, so the complete suite improved about
3.4 times. Full-run video samples show all 13 scenes rendered in both modes.
The gray capture's mean sampled brightness stayed at 126.00 for X11 and
126.00–126.04 for Wayland (8-bit video), instead of alternating gray/black.
The fixed-gray command was
`vkmark [--winsys wayland] -b clear:color=0.5,0.5,0.5,1:duration=20`.
KWin compositing was already suspended at the start and remained so throughout.

Builds: `scripts/build-manjaro-venus-icd.sh` and
`scripts/build-manjaro-zink.sh` passed. The latter also runs the upload-cache,
non-coherent-upload, DMA-BUF policy, and upload-allocation policy regressions.
Repeated preparation of the overlapping WSI patches preserved the exact source
hash. Both installed libraries require at most GLIBC 2.34, compatible with the
guest's GLIBC 2.35.

Installed SHA-256:

- `libvulkan_virtio.so`: `9d1de21b77df91000be56a7ab48a0114bb5df8a7169fbccd40a581fd56260321`
- `libgallium-25.0.7.so`: `337ef1c8f77d915e7dff5c88be6f95b98266e5bc437872ea333a896c7c10dc50`

Logs, full-run videos, per-scene contact sheets, fixed-gray analysis, and build
outputs are retained locally in `.pi/device-debug/vkmark/` (not committed).
Original installed libraries were retained beside them with the suffix
`.before-vkmark-20261022`. To roll back, install a backup to a new temporary
filename and rename it over the current library; never overwrite a library
that an active desktop process has mapped.

## Windowed X11 MAILBOX performance (2026-10-22)

The remaining default-mode slowdown came from a missing Present clock in the
non-composited windowed copy path. At 1920x1280, 120 Hz, KWin composition is
suspended by the desktop policy. Such a window shares the screen pixmap but
cannot page flip. The earlier Xorg clock patch excluded every screen pixmap,
so the failed virtio CRTC query produced zero UST/MSC. Xorg executed each
MAILBOX copy immediately instead of replacing pending frames at the next MSC.

Evidence from the unchanged installed Venus/Zink libraries:

- Two X11 clear/cube runs scored 194 and 202, versus 1637 for Wayland.
- The default X11 Present probe reported only COPY completions with
  `ust=0 msc=0`. Temporarily enabling KWin composition produced advancing
  timestamps and SKIP completions, and raised the short score to 657.
- Xorg stack samples caught its main thread in `present_execute_copy` /
  `glFlush`, waiting for the Zink submit thread, which was polling an imported
  sync FD in Venus. Another sample caught a synchronous buffer invalidation.
  These operations were repeated for frames that MAILBOX could have superseded.

`guest/xorg/present-copy-clock.patch` extends the existing opt-in fallback to
non-fullscreen copies into the screen pixmap. Fullscreen screen pixmaps,
current flips, and pending flips retain their CRTC. The single-output,
valid-mode and failed-hardware-clock checks still apply. No GPU fence is
removed or signaled early, and no library, default present mode, compositor
policy or Wayland selection changes.

The actual patched CRTC-selection harness first failed on the new windowed
screen-pixmap case, then passed with the fix. The runtime checker
`node guest/xorg/check-vkmark-clock.cjs LOG` rejects the captured old zero-clock
log and accepts the new advancing clock with superseded MAILBOX completions.

| Profiled clear/cube pair | Before | After |
| --- | ---: | ---: |
| Score | 194 / 202 | 1109 |
| Average AcquireNextImage | 1652–1900 us | 63 us |
| Average QueuePresent | 2095–2166 us | 793 us |
| Average application WaitForFences | 1284–1508 us | 61 us |

These are CPU call durations, not isolated GPU execution times. Profiling was
removed for the full suite below. MAILBOX FPS includes rendered frames that
are superseded before display; it is not the tablet's physical refresh rate.

### Device verification

The VM stayed running throughout. The private Xorg executable was installed
by atomic rename and only `avf-plasma.service` was restarted to load it. The
display was restored to 1920x1280 at 120 Hz; KWin composition remained suspended
during the measurements.

| Check | Result |
| --- | --- |
| `vkmark`, all 13 default scenes | Exit 0, **1109** (previous full suite: **240**) |
| `vkmark --winsys wayland`, all 13 scenes | Exit 0, **1654** (previous: **1645**) |
| X11 fixed gray, 20 seconds | Exit 0; **1798** captured frames in the interior 15 seconds; **0** black/incorrect frames |
| Wayland fixed gray, 20 seconds | Exit 0; **900** captured frames in the interior 15 seconds; **0** black/incorrect frames |
| Final 4-second FIFO / MAILBOX clear checks | Exit 0 for both; **120 / 691 FPS**; desktop service and terminal remained responsive |

The gray ROI stayed at 126.00 for X11 and 125.9988 for Wayland in 8-bit video.
Full-suite recordings and per-scene samples show all scenes rendered. The
remaining X11/Wayland score difference includes their different presentation
and copy paths; this fix does not establish equal rendering throughput.
The shorter runs also vary; 1109 is the measured complete-suite score, not a
guaranteed frame rate for every scene or run.

`scripts/build-guest-xorg.sh` passed its clock and scope tests and built the
ARM64 server. A repeated build produced the same binary hash. Applying both
patches to the clean pinned source with zero fuzz produced the same CRTC
selection source as the incremental build. No Android code changed.

Installed Xorg SHA-256:
`f3695acc247dc55495bdfb1e64850558750b155fa61531d88dccc93aebdf0bdd`.
Venus and Zink hashes remain the ones recorded above. The old server is retained
at `/opt/viruaisor/xorg/Xorg.before-copy-clock-20261022`; rollback uses an atomic
rename of a copy followed by a guest desktop restart. Logs, videos, pixel
analysis and build outputs are in `.pi/device-debug/vkmark-x11/` locally.
