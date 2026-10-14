# Experimental display-only root bridge

## Why this is separate from permission setup

One-shot grants can authorize ordinary AVF VM lifecycle APIs. They cannot change the caller's
SELinux identity for ServiceManager lookup, display Binder calls, or Surface/FD transport. The
unrooted baseline on the current device reproduces `{ find }` denial for
`android.system.virtualizationservice` from `untrusted_app`, with SELinux globally Enforcing.
See [the original investigation](direct-surface-selinux-research.md).

The opt-in **实验 Root Direct Surface（仅显示桥接）** mode uses a separate, normal non-daemon
libsu RootService. It moves internal service lookup, `waitDisplayService`, `setSurface`,
`removeSurface`, and hosting the display BufferQueue into the already-authorized root environment.
The application delegates its SurfaceView parent layer and bounded buffer dimensions. The helper
creates one BLAST child beneath that parent and a local queue; only that root-created queue's Surface
is sent to crosvm. VM creation, lifecycle, vsock, bundle import, guest commands and input stay in the
ordinary app. The queue-ownership change has a device-verified helper endpoint, but remains an experimental,
unproven display until actual guest frames pass.

## Resource-isolation limitation

`waitDisplayService()` has **no VM identifier or ownership token**. Authenticating the calling app
is not proof that the returned global display belongs to its VM. This mode is experimental and
single-VM-only: the helper checks that exactly one AVF crosvm process exists, and the test refuses
to start when a competing crosvm is already running. Process identity must come from the kernel's
`/proc/<pid>/exe` link to `/apex/com.android.virt[@version]/bin/crosvm`, not an exact `comm` name.
The tested device names its VM `crosvm_default`; matching only `crosvm` incorrectly reports zero.
Deleted-but-still-running AVF binaries also count; unreadable executable metadata aborts the check
rather than silently treating a process as absent. Kernel threads and vanished processes have no
executable and do not count. Multiple crosvm processes (including sandbox workers) fail closed;
this is a conservative lab check, not a general VM-counting API.

The helper starts after this app reports its own VM running. These checks reduce accidental
interference but do not eliminate process races or provide enforceable per-VM ownership. Do not
use this mode alongside Terminal or another AVF VM;
a production multi-VM design needs a platform interface carrying verified VM ownership.

## Security and lifetime

- The helper checks that it is UID 0 and that its package Context is `pro.darc.viruaisor`.
- Each incoming RPC checks `Binder.getCallingUid()` against the Context's full application UID,
  including Android user ID, **before** deserializing data or clearing calling identity.
- The versioned protocol allows only parent-layer display attach, detach, and interface-descriptor
  retrieval. It rejects unknown operations, extra parcel data, invalid parent handles and one-way
  calls. Requested buffer dimensions are limited to 1..4096 per axis and 4,194,304 total pixels using
  overflow-safe arithmetic; format, child name, opacity and Z-order are fixed by the helper. Queue
  defaults alone do not constrain every allocation an external crosvm producer might request.
  There are no arbitrary
  shell, filesystem, permission-grant, input or VM-lifecycle RPC capabilities. Internal fixed
  `/proc` reads serve only identity diagnostics and the single-crosvm safety check.
- Each UI instance delegates a client-owned Binder death token. Stale-session detach cannot remove
  a newer instance's Surface; client token death also releases the helper's current attachment.
- Both platform AVF Binders remain private to the helper; no privileged Binder handle is returned
  to the application. The app passes only its parent-layer capability and dimensions, not its
  Surface producer, a path or platform service name. `createSurface()` excludes the extra compositor
  handle carried by `createSurfaceWithHandle()`.
- The bridge starts only for an active VM with a valid UI Surface and parent. Releasing the Surface,
  stopping the VM, disposing the UI, or client death releases the normal bound service. Cleanup
  attempts producer removal, child hide/unparent, Surface release, queue destruction, child release
  and local parent-handle release, even after an earlier step fails. It never mutates/removes the
  delegated parent or its siblings. The queue's hidden reflection is restricted to the helper and
  fails closed on unavailable API-37 signatures; there are no added hidden-API exemptions. It is not
  a daemon.
- Bootstrap uses a dedicated verified libsu shell, which is closed after the launch task. No cached
  general-purpose root shell is retained. Startup has a 30-second binding deadline. A helper-side
  watchdog kills only that disposable helper if a platform operation exceeds 15 seconds.
- libsu's bootstrap receiver is unexported and permission-protected against ordinary apps, but
  v6.0.0 does not explicitly authenticate sender UID 0 or a nonce. This design does not defend
  against an already-compromised root/system sender. See [the source audit](root-display-broker-research.md).

There is **no policy injection, global permissive toggle, device-wide hidden-API relaxation,
system installation, or persistent module**. KernelSU itself already installs broad policy for
its `ksu` root domain, including domain-level permissiveness; global `getenforce=Enforcing` must
not be described as proof that the root child is least-privileged. This project adds no further
rules and does not widen shared `untrusted_app` or `shell` domains.

## Build and device checks

The pinned `com.github.topjohnwu.libsu:service:6.0.0` dependency includes its core module. JitPack
resolution is limited to that group. The helper is reflectively instantiated by libsu; it is not
an Android manifest service. The existing generated arm64 JNI library is still required.

Run the unit tests and debug APK build. After installing, obtain root and explicitly grant the
missing AVF permissions. Import a VM bundle, enable experimental root display mode, then start the
VM. Use `ViruaisorRootDisplay` logs to distinguish bootstrap, authenticated RPC, Surface attachment,
and helper cleanup. A failed bridge must report its failure, not fall back to broader privileges.

The debug-only `pro.darc.viruaisor.action.DEBUG_ROOT_DISPLAY` action selects the same opt-in mode.
It accepts no resource, command or UID extras. `scripts/test-root-direct-surface.sh` starts the
prepared VM in this mode, checks attachment and desktop-request milestones, and saves a screenshot
for frame inspection. A successful attach or desktop request is **not** proof of visible frames;
inspect the capture and guest compositor/GPU logs separately.

### Forwarding prototype: named-process regression and device verification

The device reproduced `Root display experiment requires exactly one crosvm; found 0` while its
running VM's `comm` was `crosvm_default` and `exe` was `/apex/com.android.virt/bin/crosvm`. A root
read of that PID ruled out process invisibility. Ten guard tests replay these identities and
cover zero/multiple VMs, renamed processes, impostor names, versioned/deleted APEX binaries and
unreadable metadata. The original name-based implementation failed six of those tests; the
executable-based fix passes all 43 app unit tests.

After rebuilding and reinstalling, the same device test passed the single-VM check and logged
`Root Direct Surface attached`, followed by `Guest agent ready; desktop requested`. SELinux and
hidden-API setting snapshots were unchanged. Captures immediately after attachment and several
minutes later still show a black viewport: this establishes attachment, **not a working desktop**.
The host also logged `Failed to get surface config from BQ. Error: -2147483646`. That transport
error is a separate investigation; do not infer a particular SELinux rule from the status code
alone or widen policy to conceal it. Device evidence is kept in ignored
`.pi/device-debug/root-direct-20261013-131458/`.

A viewport-only frame probe subsequently measured zero non-black samples out of 400 and returned
failure. Read-only Binder diagnostics found these matching kernel records:

```text
binder: 24257:24225 transaction credentials failed
binder: 24225:24257 transaction call to 10589:0 failed .../-1, code 41 size 88-0 line 3477
```

PID 24225 is this VM's crosvm, PID 10589 is the ordinary app, and the successful attach RPC ran in
root helper PID 24251. Forwarding an app-owned Surface therefore leaves a blocked crosvm-to-app
BufferQueue callback: a root caller of `setSurface` does not relocate the producer Binder. This is
an observed credential-gate failure, not a captured AVC proving one particular missing TE rule.
A possible next experiment is a root-owned display queue parented under the caller's SurfaceView;
it must preserve full-UID authentication, resource bounds, session cleanup and private AVF handles.
Guest compositor health and actual frames remain separate, unproven milestones.

### Root-owned queue experiment: endpoint verified, rendering pending

Protocol V2 replaces the forwarded app Surface with a parent `SurfaceControl` and bounded fixed-buffer
size. The helper uses the API-37 two-step hidden BLAST constructor/update and public SurfaceControl
builder/transaction methods. For the current 1280x720 holder, child geometry remains 1280x720 with
identity scale: SurfaceView's container supplies viewport scaling/clipping. Only a helper-owned child
is made visible at sibling Z=1. Source evidence, version differences and cleanup limitations are in
[root-owned-surface-research.md](root-owned-surface-research.md).

The added JVM tests check request bounds, ordered one-time teardown, and failures at every cleanup
stage. They cannot prove native queue ownership or guest rendering. Device validation must inspect
the crosvm's producer references, query/FD failures, and actual viewport frames, then exercise stop,
SurfaceView replacement and process/session death. Do not call V2 a working display before those
checks pass.

The first V2 device run installed successfully, attached the 1280x720 root-owned queue and requested
the guest desktop, with the policy snapshots unchanged. The helper was PID 8978 in `u:r:ksu:s0`,
crosvm was PID 8959, and the ordinary app remained PID 22428 in `untrusted_app`. Read-only Binder
snapshots map crosvm's handle 2 to node 3909063, hosted by helper PID 8978 and referenced by crosvm
PID 8959. This verifies the endpoint relocation rather than assuming it from an attach result.
The initial log capture has no repeat of the previous `Failed to get surface config from BQ`
error. Neither that absence nor the reference proves that all subsequent buffer/FD operations pass.
The viewport probe returned exit code 1 with `non-black samples=0/400` at bounds
`[40,999][1640,1899]`. SurfaceFlinger lists `ViruaisorRootDisplay#2782` (PID 8978, UID 0)
as a child of this app's SurfaceView container #2779, alongside its app-owned BLAST child #2780;
the new child is at Z=1. A per-layer latency query returned the refresh period only, without any
presentation timestamp rows. These snapshots do not establish whether the queue received buffers
or which guest component failed. Guest desktop-service health, actual frames and V2 lifecycle
cleanup remain separate checks. Evidence is kept in ignored
`.pi/device-debug/root-direct-20261013-215137/`.

#### Guest startup blocker found after V2 attachment

An authenticated guest-console diagnostic found `avf-desktop.service` in `failed` state and
Weston inactive, with no Weston journal entries. The service stopped in `ExecStartPre`:

```text
modprobe: FATAL: Module virtio_gpu not found in directory /lib/modules/6.12.92-android16-6-g4e585dd7f3b7-ab16266940-4k
avf-desktop.service: Control process exited, code=exited, status=1/FAILURE
```

In the same guest, `/sys/module/virtio_gpu` and `/dev/dri/card0` already existed. Therefore a
missing module file was falsely preventing startup despite an available built-in/already-loaded
driver. The unit now checks that module directory first, invoking `modprobe` only when absent.
Actual load failures still stop startup. `START_DESKTOP` remains the only desktop trigger; no
shell-profile, boot target, or Android policy change was added. The protocol acknowledgement
still means a request was accepted, **not** that systemd or Weston completed successfully.

`bash scripts/test-guest-desktop-preflight.sh` executes the real preflight command against four
module/loader fixtures. Before the fix, two cases failed (including the device regression); after
the fix all four pass. These fixtures cannot prove Weston or actual display frames. Guest-console
evidence is in `.pi/device-debug/root-direct-20261013-215137/guest-desktop-diag.txt`.

The next device run reproduced the same guest failure after attaching the root queue. The guest's
`/etc/systemd/system/avf-desktop.service` was then updated using the existing authenticated test
account: the old unit's normalized SHA-256 had to match this repository's previous unit, its backup
had to be absent or identical, and the replacement was hash-verified before an atomic rename and
`systemctl daemon-reload`. No rootfs reimport or Android root/policy change was used. The diagnostic
confirmed Weston was still inactive after this update; updating the file is not a desktop trigger.
The rollback copy is `/etc/systemd/system/avf-desktop.service.viruaisor-preflight-backup`; restore it
and run `systemctl daemon-reload` inside the stopped-desktop guest to undo the service change.
Evidence is in `.pi/device-debug/root-direct-20261014-002246/guest-desktop-diag.txt`.
The fresh-run frame probe also failed with `non-black samples=0/400` at
`[40,809][1640,1709]`, before any retry of the failed desktop unit. This is the device baseline
for the guest-preflight fix, not a rendering result after that fix.

The subsequent stop/restart run passed the cleanup check: the ordinary app reported `Stopped`,
crosvm and the non-daemon helper exited, the root display child disappeared from SurfaceFlinger,
and policy snapshots remained unchanged. No detach error or watchdog expiry was captured. The
next startup again attached the root-owned queue and requested the desktop. Its immediate
screenshot is still black. The subsequent post-restart viewport probe also returned exit code 1
with `non-black samples=0/400`. Therefore the preflight repair alone has **not** established working
frames; check the new guest service result before attributing the remaining failure to transport.
Post-restart guest/frame diagnostics are tracked in `.pi/device-debug/root-direct-20261014-003931/`.

The post-restart guest diagnostic confirms the preflight fix reached the device:
`avf-desktop.service` now reports `Result=success`, `ActiveState=active`, `SubState=exited`;
Weston reports `ActiveState=active`, `SubState=running` with its keyboard/desktop-shell processes.
Its render path, however, logs the following errors with the existing Zink/Lavapipe environment:

```text
MESA: error: zink: failed drmPrimeFDToHandle Invalid argument
failed to create kms fb: Invalid argument
failed to get drm_fb for bo
```

Thus Weston being active is not evidence of a valid KMS framebuffer or presented guest frame.
The current VM uses the `2d` GPU backend. The next experiment is limited to the guest compositor's
renderer choice, keeping VM GPU configuration, the root queue, and Android policy unchanged.
Do not label this a host Binder/FD-policy failure without new transport evidence.

After the attachment smoke test, run the black-viewport check from the repository root on Windows:

```powershell
$frames = ".pi/device-debug/frame-$(Get-Date -Format yyyyMMdd-HHmmss)"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/assert-surface-frame.ps1 -OutDir $frames
```

The check captures the visible application SurfaceView and samples its central region; fewer than
four non-black samples out of 400 returns exit code 1. Missing/clipped/mismatched UI bounds fail
rather than sampling an unrelated screen. A green pixel check establishes non-black content only;
inspect the saved image to identify the guest desktop. It neither bypasses the lockscreen nor uses
root, changes policy or draws a synthetic success frame. Capture paths are restricted to ignored
`.pi/device-debug/` subdirectories.

After frame/guest diagnostics finish, stop the single-VM experiment through the ordinary app:

```sh
bash scripts/test-root-display-cleanup.sh .pi/device-debug/<experiment-capture>
```

The cleanup check requires the app's `Stopped` state, no crosvm/root helper, no
`ViruaisorRootDisplay` child, and unchanged policy snapshots. It fails on a detach error or watchdog
expiry even if the processes eventually exit. It sends no kill command and makes no policy change.
