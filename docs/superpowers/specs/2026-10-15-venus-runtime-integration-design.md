# App-private Venus runtime integration

This extends the approved overlay and Venus host designs under the user's
2026-10-15 instruction to integrate a candidate and prove guest GPU compute.
It does not authorize system/APEX/policy changes or a privileged renderer.

## Boundary and alternatives

Use the already verified one-file crosvm bind in the disposable ordinary-UID
`:venus_vm` mount namespace. Keep stock virtmgr, app VM lifecycle, original APEX
files, imported user disks, permissions and SELinux Enforcing unchanged.
A global replacement is excluded. A host-only renderer test cannot establish
guest acceleration, so it remains a diagnostic, not the acceptance result.

The only new root capability selects one APK-packaged candidate with a fixed
SHA-256/size. It accepts no path, command, PID, mount target, hash or policy from
the caller. Root verifies the original artifacts and immutable candidate input;
native code anchors source/target FDs, reuses the original executable label only
on the temporary copy, strips file capabilities and observes the existing UID,
MAC domain, namespace, executable inode and authenticated process ancestry.

## Ordered gates

1. Preserve the existing source baseline in a commit (done: `fbb9086`).
2. Use pinned Android rutabaga rather than registry alpha; retain a controlled
   Cargo lock. Explicit Venus initialization must fail closed, never fall back
   to a 2D device. Use the statically built embedded thread render server only.
3. Exercise descendant cleanup for stock owner death and timeout before the
   candidate. Recover only the fixed probe VM definition from ordinary app UID;
   never delete an unproven/user VM. Audit namespace/mount/process disposal.
4. Package the candidate in the same-signature non-debuggable Release main APK.
   First run the fixed diskless GPU-off execution fixture, then the Venus GPU
   gate. Capture console and native errors. If the existing MAC domain cannot
   access a required driver/allocator operation, collect the exact denial and
   stop that path without permission/policy expansion.
5. Once runtime initialization works, implement the bounded DMA-heap import
   allocator and retained export FD in the actual renderer. Connect real guest
   Venus blob mappings and mapped-range cache synchronization; host-only
   DMA_BUF_IOCTL_SYNC is insufficient evidence of guest correctness.
6. Use a separate disposable test guest, not the user's writable root disk.
   Require Venus capset 4, non-software physical GPU identity, input-dependent
   compute with varied seeds, explicit completion and all expected readback
   words. Missing synchronization must fail the negative control.

## Failure and rollback

Use failing tests for each behavior change, then local and device checks.
On any gate failure stop further integration, ordinary app stops/deletes only
its fixed probe, root releases the lease, native unmounts and compares the
original inode/bytes, and the disposable worker exits. Independently audit no
owned descendants, namespace references, experiment mounts or test packages.
Recheck original hashes, init/zygote namespaces, existing grants and Enforcing.
Device suspension/OEM freezing can delay execution; do not claim a strict
wall-clock lease guarantee while the host is suspended.

## Implementation plan

Implement in the gate order above, keeping build/source patches and regression
tests in the repository, generated artifacts/evidence under ignored `.pi/`.
The writing-plans skill is not available in this session; this explicit ordered
plan is the fallback. Report the highest live-proven gate, including an honest
blocker if completion requires changes the user has prohibited.

## Existing-authorization allocator alternative (2026-10-16)

The direct system DMA-heap path fails `open(O_RDONLY)` with EACCES in the actual
ordinary-UID crosvm domain. A read-only kernel compute_av query confirms an empty
allow mask for this domain against dmabuf_system_heap_device chr_file
read/open/ioctl. No policy or capability change is permitted or made.

The device's existing crosvm domain is already a graphics allocator HAL client.
Qualify Android AHardwareBuffer BLOB allocation using that existing authority,
not a root allocation broker or a passed heap-device FD. Retain the AHB lifetime,
identify a unique bounded DMA-BUF from its native handle, duplicate only that FD,
and validate native Vulkan import memoryTypeBits before binding. Unknown handles,
incompatible types, missing symbols and denied HAL calls fail closed. CPU usage
hints should match cached vs uncached requested memory; they do not replace
the driver compatibility check or real guest readback.

## Paired guest cache contract

Use a Mesa 25.0.7 ICD only inside the disposable initramfs. Preserve actual
HOST_VISIBLE/HOST_CACHED noncoherent memory flags, and forward Vulkan mapped-range
flush/invalidate through the existing Venus protocol to retained DMA-BUF cache
maintenance in the host renderer. Never add HOST_COHERENT to incompatible memory.
Disable optional fence/event/semaphore/query feedback polling that requires
coherent imported storage; use real protocol GPU fence/status queries instead.
This paired ICD must not be installed into the user guest or used with stock
renderers whose mapped-range handlers are absent.

AVF APEX and system loader namespaces can contain two libcrypto instances at
different addresses. Trace proved the unloaded destructor belonged to the system
instance. Retain it through the already-visible system namespace, with no linker
configuration changes, new namespace, policy/grant changes, TLS-key deletion or
destructor suppression. TLS tracing is diagnostic-only, excluded from the final
candidate. Require the VM to survive an additional 500 ms after the guest's
asynchronous Vulkan teardown, not just print a compute PASS before crashing.
