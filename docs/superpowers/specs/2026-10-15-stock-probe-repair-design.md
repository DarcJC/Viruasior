# Stock AVF probe baseline, console and repair

2026-10-15. The user explicitly requested this continuation: verify unmodified virtmgr
capabilities, capture guest console, repair the probe, validate it, then advance Venus.
All operations remain app-private and temporary; no system/APEX/policy/permission changes.

## Design and implementation order

1. Add a fixed `stock-baseline` path to the existing unexported disposable app service.
   It does not bind the root overlay service or mount anything. It runs the same fixed
   diskless/GPU-off VM with the original panic parameter. Enable AVF output capture
   and explicit serial console only; drain console and log concurrently before run,
   retain at most 256 KiB per stream in two fixed diagnostic files, discard overflow.
   Keep both files for inspection, never attach guest/user disks. A read-only external
   observer records original executable inode/hash, UID/capabilities/domain/namespace.
2. Compare the unchanged stock baseline with the existing overlay observation. Only
   after evidence agrees, accept the exact existing virtmgr capability mask (not an
   arbitrary nonzero mask), retaining pinned stock inode/hash, app UID, namespace,
   start time, parent chain and expected SELinux domain. Worker and crosvm still require
   zero effective capabilities. This validates existing privileges; it grants none.
3. Use console evidence to repair the minimal VM fixture. If it demonstrates an
   intentional no-rootfs panic followed by immediate reset, change only the fixed
   diskless fixture's panic behavior to stay halted, then require it to remain running
   until ordinary AVF stop/delete. Such a fixture proves executable/KVM operation,
   not userspace boot or guest GPU. Do not infer reset cause from the exit code alone.
4. On both success and failure, ordinary AVF stop/delete precedes native release;
   retain the control file until the native result/rollback arrives, then unlink only
   that session's artifacts. Bound waits; do not replace owner-death/lease/watchdog
   recovery. Add regression tests before changing identity or cleanup behavior.
5. Run native policy/copy/exec/mount tests, sanitizers, Android unit tests and Debug/
   Release builds. Install only the same-signature non-debuggable main Release APK.
   Verify actual copied executable inode, ordinary UID, zero crosvm capabilities,
   expected domain/parent/namespace, normal stop/delete and explicit rollback. Audit
   namespace/thread/FD/mount/process/artifact cleanup and unchanged originals/Enforcing.
6. Only after these gates pass, resume local Venus candidate build work. Candidate
   provenance/runtime dependency/AVF compatibility and guest memory/readback remain
   separate gates; no arbitrary binary overlay API or system-wide deployment is implied.

## Alternatives and scope

Capturing bounded app-owned output and checking the exact stock behavior is preferred
to weakening identity checks or attaching the full user's VM. A full userspace boot
would add disks/initrd and unrelated failure modes; it is not needed for this gate.
No root VM lifecycle, policy allow rules, global overlay, filecap alteration, persistent
module or system setting changes are permitted. No extra user choices are needed for
the explicitly requested baseline/console/probe repair; new privilege requirements
or an unproven candidate deployment stop that stage for review.

## Feedback and rollback

Existing failing loop: fixed stock-exec service reports status0 and
STOCK_EXEC_NOT_OBSERVED despite externally observed copied crosvm. Preserve those logs
as the red device fixture. New tests cover exact capability masks/domain boundaries,
bounded output drain including overflow, and cleanup ordering even on VM failure.
The no-overlay baseline requires no mount rollback. Overlay rollback uses only the
existing authenticated lease; normal APK reinstall can restore the previous signed
build without changing app data. Retained console files are diagnostic artifacts in
this app only, not persistent execution hooks.

## Completed evidence, 14:07

The no-overlay baseline confirms stock virtmgr AND stock crosvm carry CAP_SYS_NICE;
the overlay copy deliberately lacks filecaps and remains crosvm caps0. Console proves
the no-rootfs panic/reset cause. Exact role-based capability/domain checks, bounded
output drain, panic0 fixture, native-first cleanup and synchronous disposable exit
are tested. The prior delayed exit was observed with cgroup.freeze=1; no freeze policy
was changed. App-only fixed failure injection preserves the root API and confirms
native rollback before control deletion. Final normal execution plus external audit
passes; see the experiment report. 82 Android unit tests and native regressions pass.

The local Cargo/NDK build baseline now links a real AArch64 crosvm containing Venus
renderer code. It has not been deployed and is not yet a qualified AVF/Venus candidate.
Stock descendant death/timeout, candidate provenance/loader/control and guest GPU
mapping/readback remain distinct downstream gates.
