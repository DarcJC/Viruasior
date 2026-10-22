# vkmark in the Manjaro Venus guest

The paired Android renderer can import BGRA8 UNORM `AHardwareBuffer` images for
presentation. When vkmark chose BGRA8 sRGB, its swapchain image fell back to a
BLOB allocation. Xorg then failed to import that BLOB as an image and the
desktop stopped updating. The guest Mesa WSI patch advertises BGRA8 UNORM for
X11 and Wayland when `VIRUAISOR_AHB_UNORM_WSI` is set. The Manjaro session sets
this flag in `/etc/viruaisor/venus.env`.

The Mali device exposes host-visible memory without `HOST_COHERENT`. The stock
vkmark scenes require coherent memory and silently skip most benchmarks when
none matches. `scripts/build-manjaro-vkmark.sh` builds the pinned upstream
snapshot with `vkmark-noncoherent-memory.patch`: mapped writes are explicitly
flushed before GPU use. The binary and its X11/Wayland plugins are built
together under `/opt/viruaisor/vkmark`.

The desktop session is X11. `/usr/local/bin/vkmark` starts a nested Weston X11
compositor for an explicit `--winsys wayland` run, waits for its socket, and
stops it when vkmark exits. Other vkmark invocations use the X11 plugin.

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
