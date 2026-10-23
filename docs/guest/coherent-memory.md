# Coherent memory investigation

Status on 2026-10-23: the Android renderer maintains host cache lines for
uncached DMA-BUF allocations at Vulkan submission and completion boundaries,
scoped to the owning Venus context. The unmodified distribution vkmark and
the coherent and cached guest compute probes pass on the tablet. With snapshot
cache maintenance and real WSI completion-fd handoff, the complete stock benchmark
scores XCB 1838 / Wayland 2764. The release build uses the validated renderer
binary as a pinned input.

## Device and source audit

The attached tablet reports `yingtian`, model M367FC, and firmware
`OS4.0.15.0.XBMCNXM` / Android 17. The user confirmed that its host kernel is
from the Xiaomi ROM:

```text
6.18.21-android17-5-g284297031c53-abogki553682993-4k
```

This is the Android **host** kernel, not the pinned September Ferrochrome
kernel used inside the Linux guest.

The source audit enumerated all 267 branches of
[MiCode/Xiaomi_Kernel_OpenSource](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/branches),
the official organization's 80 public repositories, and the branches of
`kernel_build`, `kernel_devicetree`, and `device_xiaomi`. No matching `yingtian`,
Android 17, or 6.18 source was found in those repositories. This is a dated
search result, not a claim that no copy could exist elsewhere.

The official [device index](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/README/README.md)
lists these older XRing branches:

| Branch | Examined commit | Kernel Makefile |
| --- | --- | --- |
| `dijun-v-oss` | `0b426219a9a24b56c1593ebc84edb8a3f3b897b0` | 6.6.30 |
| `violin-v-oss` | `99a9aff00a2856f065954672660bd0fe0ad0a064` | 6.6.30 |

Both contain `common-gki` / `common-ogki` trees for the older Android 15
platform. Their KVM implementation must not be presented as the source for the
running 6.18.21 kernel. The exact `284297031c53` revision was also unavailable
from the public Android common kernel repository when checked.

## Unmodified benchmark reproduction

`command -v vkmark` resolves to `/usr/bin/vkmark`. The installed distribution
package is `vkmark 20220318-1`; `pacman -Qkk vkmark` reports 57 files and zero
altered files. Its executable SHA-256 is:

```text
cb47795f6c3c8204626ce1b89001de3d3a450d0df2129a62fd3ff293ced46b84
```

Before the driver repair, `/usr/bin/vkmark -b cube:duration=2` reported
`Couldn't find matching memory type`, score zero, and **process exit code
zero**. A zero exit code alone is not acceptance. The former private benchmark
build, patch, and default wrapper were removed from the image build.
Historical modified benchmark scores are labeled in [vkmark.md](vkmark.md).

## Native and guest memory results

Native Mali exposes coherent memory for ordinary buffers. The AHB/DMA-BUF
import query, however, only accepts cached noncoherent native types: mask
`0x7e02`. Native coherent allocations could not be exported using the tested
external-buffer handle paths.

The compute diagnostic uses the same shader with three seeds and 256 words
per seed. A fence must complete and every word must match, not just the first.

| Path | Cache maintenance | Result |
| --- | --- | --- |
| Native cached DMA-BUF CPU mapping | None | Stale readback |
| Native `system-uncached` DMA-BUF CPU mapping | None | 3 × 256 words correct |
| Initial guest coherent alias of uncached DMA-BUF | None | First pass: 256 stale words |
| Repaired guest coherent alias | Host `dc cvac` on submit, `dc civac` on completion | 3 × 256 words correct |
| Paired guest cached type 14 | Flush and invalidate | 3 × 256 words correct |
| Restored pre-repair runtime, coherent allocation requested | None | No compatible memory type |

The initial prototype allocated from the fixed `system-uncached` heap through
the existing VM helper, authenticated the exact crosvm PID/UID, retained leases,
bounded allocation sizes and quotas, and advertised extra guest memory types
only after querying the real allocation. Protocol tests passed, but that did
not establish guest memory coherence. The first prototype also failed desktop
pixel readback. The repaired renderer adds host cache maintenance to the same
allocation path. The host KVM probe below identified why this is required.

## Isolated KVM reproduction

`native/venus-kvm-probe/` isolates the mapping without crosvm, Venus, Vulkan, or
GPU execution. It creates one disposable vCPU, 64 KiB of ordinary code/page-table
memory, and one 64 KiB allocation from `system-uncached`. It has an eight-second
process deadline, no disk or network device, and no persistent installation.

The guest enables its MMU and cache, maps the test buffer as Normal Non-cacheable,
and reports its stage-1 translation attributes. The host checks both MMU/cache
enable bits and the `PAR_EL1` attribute byte `0x44` before accepting observations.
The guest writes a value and exits to userspace through emulated MMIO. It then
cleans and invalidates that cache line and exits again as a positive control.

Observed on the running Xiaomi host kernel:

```text
PAR_EL1=4400000080000b00
KVM_UNCACHED_NO_FLUSH guest=12345678 host=00000007 equal=0
KVM_UNCACHED_AFTER_CIVAC guest=12345678 host=12345678 equal=1
KVM_MEMORY_RESULT=FAIL_NO_COHERENCE
```

This proves that the guest's uncached stage-1 mapping does not provide the
required visibility through this host KVM mapping. The failure is reproducible
without the graphics stack. It does not identify the exact line of the
unpublished host source.

The actual running `boot_b` partition was read without modifying it. Its
100,663,296-byte image has SHA-256
`7ebe0bc5e09981e4d9f21ba0d8509494d228adb628b23d161c932e9b8ee3a0bb`;
the extracted arm64 kernel has SHA-256
`1a971c836ff10b7f237adb01e2a0e30bfe72e43fb4fe7f460e7a9ad8067c267d`.
The read-only KVM fault trace records an access to guest IPA `0x80000000`, the
test DMA-BUF slot. `/proc/self/smaps` marks its host VMA `pf io` (PFN-mapped
I/O), and recovered symbols in the shipped kernel identify `user_mem_abort`,
`pfn_is_map_memory`, and `kvm_pgtable_stage2_map`. Disassembly of the shipped
`user_mem_abort` selects the special noncacheable path only after the RAM-PFN
test fails. The observed stale host read is consistent with the ordinary RAM
path for this DMA heap. The trace does not directly expose the final stage-2
page-table entry, so this branch assignment remains an inference.

The public Android 17 / 6.18
[KVM implementation at `02bbed02`](https://android.googlesource.com/kernel/common/+/02bbed02f191e1601378758391eea94d72a5d5b8/arch/arm64/kvm/mmu.c)
provides a plausible explanation: `user_mem_abort` only selects the special
noncacheable mapping path for certain PFN mappings that are not ordinary RAM.
A DMA heap's ordinary RAM pages can retain a normal cacheable stage-2 mapping.
Its control flow matches the recovered shipped binary. This public source is
a comparison baseline, not the exact Xiaomi build.

In a second one-shot KVM run, the guest vCPU executed on CPU 0 and the host
process moved to CPU 9. After the guest's store but before any guest cache
clean, the host still read `0x00000007`. A host `dc cvac` on its alias followed
by `dsb sy` made `0x12345678` visible on CPU 9. The host then wrote
`0x89abcdef`; the guest first saw its cached `0x12345678`, then saw the new
value after host `dc civac` plus `dsb sy`. A direct host `dc ivac` trapped as
an illegal instruction at user privilege, so only the tested `civac` operation
is a candidate for this process. These tests establish visibility in both
directions for one line across cores. They do not alone establish full Vulkan
`HOST_COHERENT` semantics or GPU visibility; the guest compute and vkmark
checks below exercise those paths. Overlapping CPU/GPU writes and
foreign-context sharing remain areas for further testing.

An earlier capability interpretation was incorrect: number 239 in this kernel
family is `KVM_CAP_ARM_WRITABLE_IMP_ID_REGS`, not noncoherent DMA support. Old
crosvm bindings reuse that number for an out-of-tree capability. A positive
query for 239 must not enable noncoherent memory flags on this device.

## Reproduce the diagnostics

Build the KVM probe in Linux/WSL with the Android NDK:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r30 bash scripts/build-venus-kvm-probe.sh
adb push .pi/device-debug/venus-kvm-probe/venus-kvm-probe /data/local/tmp/viruaisor-kvm-probe
adb shell chmod 755 /data/local/tmp/viruaisor-kvm-probe
adb shell /data/local/tmp/viruaisor-kvm-probe
```

On the tested ROM, ordinary shell access to `/dev/kvm` is denied. With the
existing user-granted root authorization, run the same fixed diagnostic once:

```sh
adb shell su -c /data/local/tmp/viruaisor-kvm-probe
adb shell rm /data/local/tmp/viruaisor-kvm-probe
```

The probe requires a 4 KiB-page arm64 host. Exit zero requires both immediate
visibility and the cache-clean control to pass. The current host returns one
with `FAIL_NO_COHERENCE`. File descriptors and allocations close at process exit.
No SELinux policy, kernel parameters, modules, or system images are changed.

Build the guest compute probe with `scripts/build-venus-memory-test.sh`, setting
`ANDROID_NDK_HOME` and `VENUS_GUEST_LIBDIR` to the paired guest library directory.
Run it as the ordinary desktop user, with the installed paired Venus environment:

```sh
set -a
source /etc/viruaisor/venus.env
set +a
/path/to/venus-memory-test --cached
/path/to/venus-memory-test --coherent
```

`--cached-no-sync` is the negative control. Omitting the environment can select
the distribution's unrelated Venus ICD and invalidate the comparison. Do not
invoke this executable without an argument in a desktop session: the no-argument
mode is the existing disposable initramfs PID 1 diagnostic.

## Initial context-scoped renderer

The first host maintenance attempt swept every uncached allocation on every
Venus queue operation, including allocations owned by other applications.
The unmodified vkmark rendered but scored only 298 in its 13 default XCB
scenes. A no-op maintenance build produced a black desktop, confirming that
the cache operations are required. `DMA_BUF_IOCTL_SYNC` did not restore the
KVM probe's missing visibility. A context-scoped registry excludes unrelated
allocations and returns a two-second unmodified cube run from roughly 200–330
FPS to 1197 FPS. A fresh VM restart passed both compute paths, each with three
seeds and 256 checked words, and displayed live desktop frames.

The unmodified `vkmark 20220318-1` package completed all 13 default scenes
with score **1027** on XCB and **1092** inside nested Weston/Wayland. Both
returned status zero with nonzero per-scene FPS. The old modified binary was
run only as a diagnostic in the same Wayland setup and scored 1008; its older
1654 result was obtained under a different runtime state and is not an
unmodified benchmark result. Those initial full-run scores remained below the
required XCB 1200+ and Wayland 1600+ targets, although the severe 298 regression
is fixed.

Temporary instrumentation measured approximately 4 microseconds per cache
operation for the benchmark's own 128 KiB allocation and approximately 200
microseconds for 8–10 MiB of desktop-owned buffers. These measurements begin
after acquisition of the global registry mutex and exclude lock waiting.
Skipping all maintenance
is not a valid speedup because the desktop then fails. The production source
does not contain this profiling code. A future optimization should prove which
specific buffers are safe to exclude while preserving KVM, GPU, and display
visibility. SELinux stayed Enforcing; no host kernel or system image was
changed.

## Snapshot cache maintenance and coherent uploads

The renderer pins an owner's allocations under the registry mutex, then
performs cache maintenance outside that global mutex. A separate context mutex
preserves ordering between sweeps for the same application. Snapshot references
keep mappings and helper leases alive even if their original resource is freed.
Ownership uses the renderer's non-reused context ID rather than a potentially
reused context address.

The registry tests cover release during a pinned sweep, independent release of
another owner's allocation, reference saturation rollback, and lease cleanup.
They pass with AddressSanitizer/UBSan on Linux and as an ordinary Android shell
process; the Android run also executes both ARM64 cache maintenance operations.
The crosvm build succeeds. On the unlocked tablet, the installed executable is
`7232890999d06ab07784f9f3a68b06a2ea6a47ec1e8ae98e44fc011379296ff2`
(14,182,744 bytes). Both coherent and explicitly flushed/invalidated cached
compute probes pass all three 256-word seeds. With the preceding Zink build,
the complete stock benchmark scores 1202 on XCB and 1234 on Wayland.

The earlier Zink small-upload policy only selected noncoherent memory. Enabling
the coherent alias accidentally restored its 1 MiB uploaders and 2 MiB slabs,
although the emulated coherent allocations still require whole-buffer host
cache maintenance. The policy now also selects coherent host-visible buffers
on the opted-in Venus/Mali pair. The 64 KiB uploaders and independent allocation
reuse retain their external/dedicated and GPU-only exclusions. The actual
allocation-prefix regression fails before this correction and passes after it.
The resulting Zink library is
`8781524707e3ab2b6fa4c330ec9ef6153e75dd55580232e163ce9667581e46df`;
Xorg was verified to map the replacement after restarting the guest desktop.
The next complete stock Wayland run scores 1328, still below the 1600 target.

All these full runs use the unmodified `/usr/bin/vkmark` package 20220318-1,
SHA-256 `cb47795f6c3c8204626ce1b89001de3d3a450d0df2129a62fd3ff293ced46b84`,
all 13 default scenes, a 1920x1280 guest desktop, and disabled KWin composition.
Wayland uses nested Weston with the X11/GL backend at 1200x800. No recording or
API timing preload is active during full-score runs. Separate API timing shows
about 700 microseconds per frame in QueuePresentKHR on both window systems.

The completed-present fallback now waits for the fence already attached to its
WSI submission, avoiding a second empty queue submission and private idle fence.
Fence-less callers retain QueueWaitIdle, and completion/renderer errors propagate
to the caller. The extracted production-function test covers both paths and
errors. The resulting ICD is
`ffc5b7db51e1f90baf02469b76c91db1d52b06f6f838a6a2416615d454582ff5`;
both compute modes pass, and complete stock runs score XCB 1285 / Wayland 1363.
The Wayland target remains unmet.

Temporary internal profiling finds about 19 fence status queries per frame in
the Wayland cube case. A 50-microsecond bounded host-wait trial is rejected:
it reduces that polling but slows the short cube tests to XCB 965 / Wayland
1193. Its code is removed. Alternating the existing single-ring option also
does not consistently improve Wayland (baseline 1682/1540, single-ring
1656/1399 for eight-second cube runs). The production environment retains
the existing ring policy. These diagnostic short runs are not full scores.

## WSI completion-fd handoff

The paired Zink consumer explicitly exports the image's DMA-BUF reservation
fence when importing foreign queue ownership, imports that fd as a Vulkan
semaphore, and waits for it before submitting its image access. Venus' WSI
can therefore attach its real pending completion fd to the reservation instead
of first waiting for the same dependency on the producer CPU. This does not
enable the renderer's broken implicit-fencing capability.

`VIRUAISOR_WSI_DMA_BUF_SYNC=1` selects a separate WSI semaphore-export callback
only with the paired AHB transport. Common WSI probes both DMA-BUF sync-file
ioctls before choosing the route; unsupported ioctls retain completed-present.
The ordinary external semaphore API retains its previous scoped desktop/PID
policy. The new callback returns the real fd, including ownership transfer of
an imported fd, and propagates export errors. Production-function regressions
cover these cases and the exact environment opt-in.

The scoped candidate ICD is
`07b0bf01b3c2b832bcc4d04359fdb76c16cb76ea5431883362e920b4b1e89431`.
The real guest GPU dependency regression delays a write by 200 ms, passes its
sync fd through a DMA-BUF reservation to another Vulkan instance, then checks
all 1024 words. Async export takes 104 microseconds with both exported fds
initially pending; the consumer waits and all words pass. Baseline, wrong-PID,
and non-Zink public exports still wait about 207–209 ms. Both coherent and
explicit cached three-seed readbacks also pass. The installed library and
guest environment have backups with the `before-scoped-wsi-20261023` suffix.

A preceding diagnostic prototype of this route scores XCB 1518 / Wayland
2371 in twelve-second cube tests. Separate fixed-gray video checks pass 958
XCB frames and 480 Wayland frames, with no incorrect frames and maximum pixel
error 2 on the sampled interior. Those videos and short tests are diagnostics;
the final scoped driver is validated separately below.

### Final complete stock benchmark

After atomically installing the scoped ICD and enabling the paired WSI option,
the guest desktop was restarted. Xorg maps the installed ICD without deleted
library mappings. The 1920x1280@120 guest mode and disabled KWin composition
were checked again. With no recording or preload, `/usr/bin/vkmark` completes
all 13 default scenes with **XCB 1838** and **Wayland 2764**, both exit status
zero. Package verification reports 57 files and no alterations, and its SHA-256
matches the stock hash recorded above. Both performance targets are met on
this tested tablet/runtime combination; these are benchmark frame rates, not
the physical display's presentation rate.

The final scoped driver also passes a separate video check: sampled fixed-gray
intervals contain 939 XCB frames and 477 Wayland frames, zero incorrect frames,
and maximum pixel error 2. Nine cube samples show changing geometry and the
expected lit colours/dark unlit faces. All three visual runs exit successfully.
These recording runs are excluded from the benchmark scores.

The eight guest patches replay from clean pinned Mesa and all 12 touched files
match the compiled checkout. The WSI callback/ownership and completed-present
regressions pass, as do the actual Zink upload call-site tests. Android unit
tests and debug assembly pass; APK inspection confirms all eleven arm64 native
artifacts, both reviewed crosvm hashes/lengths and third-party notices. The
regenerated four-input release ZIP is unpacked with all member hashes verified.
No diagnostic preloads, trial environment flags, or profiling code are shipped.

The renderer patch stack is also replayed from its pinned upstream revision,
without reusing edits from the development checkout. This caught incorrect
patch ordering, two malformed hunk counts, and a teardown patch that did not
account for the base host fix. After repair, all 46 patches apply and the 30
touched source files match the candidate's compiled sources byte for byte:

```sh
python3 scripts/test-venus-renderer-patches.py "$VENUS_WORK_DIR/virglrenderer" \
    --compare-working-tree
```
