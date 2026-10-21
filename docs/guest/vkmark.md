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

On the M367FC tablet, `vkmark` completed all 13 default scenes with exit code 0
and score 185 using the first patched vkmark build. The final build then passed
an X11 vertex scene and completed all 13 default scenes under
`vkmark --winsys wayland` with exit code 0 and score 277. The tablet locked
before a final full X11 rerun. Scores vary with load and display timing.
