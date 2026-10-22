# Coherent memory investigation

Status on 2026-10-23: the Android renderer maintains host cache lines for
uncached DMA-BUF allocations at Vulkan submission and completion boundaries,
scoped to the owning Venus context. The unmodified distribution vkmark and
the coherent and cached guest compute probes pass on the tablet. The release
build uses the validated renderer binary as a pinned input.

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

## Validated renderer and performance

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
unmodified benchmark result. The current full-run scores remain below the
informal 1200+ target, although the severe 298 regression is fixed.

Temporary instrumentation measured approximately 4 microseconds per cache
operation for the benchmark's own 128 KiB allocation and approximately 200
microseconds for 8–10 MiB of desktop-owned buffers. Skipping all maintenance
is not a valid speedup because the desktop then fails. The production source
does not contain this profiling code. A future optimization should prove which
specific buffers are safe to exclude while preserving KVM, GPU, and display
visibility. SELinux stayed Enforcing; no host kernel or system image was
changed.
