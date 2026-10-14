# gfxstream GPU virtualization validation

GPU virtualization is the target, not merely a non-black desktop. The `2d` profile and
Pixman/Lavapipe/llvmpipe are diagnostic baselines, not substitutes for GPU acceleration.

## Separate acceptance checks

1. **Host backend**: the actual crosvm arguments select `backend=gfxstream`, and the renderer
   initializes without errors. A manifest value or AVF capability bitmask is insufficient.
2. **Guest GPU access**: confirm the intended virtio GPU protocol/driver, available render node,
   and Vulkan/OpenGL renderer. Detect software-only devices and forced software overrides.
3. **GPU execution**: run a real graphics workload; enumeration of a physical device alone is
   not proof that its commands executed. Correlate guest and host renderer evidence.
4. **Presentation**: verify workload output in the app's delegated root-owned display queue.
   `setSurface` success and a running Weston process are insufficient.
5. **Cleanup and policy**: stop through the ordinary app; require crosvm/helper/display child
   removal and unchanged SELinux/hidden-API snapshots.

## Current result (2026-10-15, not GPU acceleration success)

| Gate | Evidence / verdict |
| --- | --- |
| Effective host flags | **Configuration pass only**: gfxstream explicitly enables EGL/GLES, surfaceless operation, Vulkan, and `gfxstream-gles:gfxstream-vulkan:gfxstream-composer`. The compatible GL compositor avoids the immediate BGRA8 abort seen with the Vulkan-only compositor. |
| Guest driver/access | **Passed**: `droid` is persistently in `render`; non-root render-node access and the gfxstream ICD selection are verified. The software-forcing Lavapipe override is absent from the gfxstream profile. |
| Workload | **Not passed**: a cold direct Vulkan resource probe reached submit/wait but returned the unchanged sentinel buffer; Zink/Weston still stalls at `eglInitialize`. No shader or verified GPU write completed. |
| Visible frames | **Not passed**: the Android Surface attaches, but no non-black guest frame has been demonstrated. |
| Stability | **Fail-closed only**: a five-second guest unit deadline now terminates the stuck compositor before its prior spontaneous host crash. The VM remains alive after that rejection. Orderly VM poweroff still makes the host `v_gpu` thread SIGSEGV after `vcpu requested shutdown`; this is outside the app/guest repair boundary. |

The deployed guest changes are limited to the project agent, desktop helper/template, render-group
membership, and Weston profile environment. SELinux remains enforcing; hidden-API settings are
unchanged. No package or Android policy change was made. The stage-two rollback directory is
`/data/local/tmp/viruaisor-gpu-stage2-backup-20261015-011000/`.

### Stage-two host boundary

The explicit three-context configuration follows the capset split used by AOSP's crosvm/gfxstream
integration while retaining GLES for this device's host compositor. AOSP's AVF Terminal reference
also enables gfxstream Vulkan and surfaceless operation, but its enabling change explicitly notes
that the path still failed after roughly ten seconds. On this device, using only
`gfxstream-vulkan:gfxstream-composer` selects `GuestVulkanOnly` and aborts because the Mali host
cannot create the BGRA8 display color buffer. Keeping the GLES context avoids that boot-time abort,
but does not repair guest Zink initialization or host Vulkan teardown.

The guest agent now waits for the real `systemctl` exit result and the app consumes the initial
`READY` greeting before accepting `OK`. `avf-desktop@.service` has a five-second start timeout, so
a compositor that never becomes ready returns `ERROR systemctl` and is killed rather than being
reported as started. The final device run remained alive beyond the previous seven-second crash
window after this rejection. A later orderly poweroff completed guest systemd shutdown and then
reproduced the same host `v_gpu` `SIGSEGV` in `pthread_key_clean_all`; resources disappeared and
policy stayed unchanged, but the stop is correctly retained as a failure rather than reclassified.

Relevant upstream references: [AOSP AVF Terminal gfxstream configuration](https://android.googlesource.com/platform/packages/modules/Virtualization/%2B/caea82495188da85509997a549edd83faa76450e%5E%21/),
[Cuttlefish crosvm context selection](https://android.googlesource.com/device/google/cuttlefish/+/refs/heads/master/host/libs/vm_manager/crosvm_manager.cpp), and
[gfxstream renderer feature parsing](https://android.googlesource.com/platform/hardware/google/gfxstream/+/refs/heads/main/host/virtio-gpu-gfxstream-renderer.cpp).

## Device baseline: backend-only switch

The app-owned `files/vms/default/vm.json` was changed only to add `gpuProfile: gfxstream`.
The previous manifest is backed up as `vm.json.viruaisor-gfxstream-backup` in that same directory.
The update was performed as the debug app UID (`run-as`), with an atomic replacement and hash
readback, while no VM or display helper was running. Rootfs and Android policy were not changed.
The Pixman renderer experiment was not installed. The existing guest startup-preflight repair
remains in place.

`scripts/test-root-direct-surface.sh 120` passed VM/Surface/desktop-request checks and verified
unchanged policy snapshots. Actual crosvm arguments included:

```text
--gpu=backend=gfxstream,egl=true,gles=true,surfaceless=true,displays=[[mode=windowed[1280,720],dpi=[240,240],refresh-rate=60]]
```

The host logged `Gfxstream initialized successfully!`, but also:

```text
Vulkan: disabled (rendererFlags & STREAM_RENDERER_FLAGS_USE_VK_BIT)
ExternalBlob: disabled
SystemBlob: disabled
```

Therefore selecting the backend does not automatically enable its Vulkan path. No conclusion
about guest hardware acceleration or working presentation follows from this baseline.
The guest helper still forces Zink, `LIBGL_ALWAYS_SOFTWARE=1` and the Lavapipe ICD; its actual
runtime environment must be inspected separately from a CLI tool's environment.
Evidence is local-only in `.pi/device-debug/root-direct-20261014-100335/`. The baseline viewport
probe returned exit code 1, `non-black samples=0/400` at `[40,809][1640,1709]`.

The host-only configuration check replays this capture and fails as expected:

```sh
node scripts/assert-gfxstream-vulkan.cjs .pi/device-debug/root-direct-20261014-100335/logcat.txt
# RED: The actual crosvm arguments do not request vulkan=true
```

This check requires both `vulkan=true` in the actual crosvm arguments and the native
`Vulkan: enabled` feature log. It rejects missing/ambiguous launches and guest-console spoofing
of host diagnostics.
A future pass will prove the host Vulkan feature gate only, not a guest workload or presentation.
The installed API 37.0 source confirms `GpuConfig.Builder.setRendererUseVulkan(Boolean)` defaults
to false. See [the source audit](gfxstream-guest-research.md) for the matching ICD and version limits.

### Guest baseline result

The authenticated diagnostic found `/dev/dri/card0` and `/dev/dri/renderD128` and Debian arm64
Mesa `25.0.7-2+deb13u1`. The installed ICD directory contains **`gfxstream_vk_icd.json`**, as well
as `virtio_icd.json` and `lvp_icd.json`; Vulkan tools are installed. No guest package install or
image replacement is justified merely to obtain the gfxstream ICD.

Weston's actual process environment still selects `zink`, `LIBGL_ALWAYS_SOFTWARE=1`, and
`VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json`. Its journal confirms:

```text
GL renderer: zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 128 bits) (MESA_LLVMPIPE))
zink: failed drmPrimeFDToHandle Invalid argument
failed to create kms fb: Invalid argument
```

This is explicit software-rendering evidence, not accelerated gfxstream. The unfiltered Vulkan
CLI query produced no summary in the diagnostic's bounded interval; its exit status was not
recorded, so do not infer a particular loader/kernel failure from that absence. Next tests must
select only the gfxstream ICD and capture the exit status. Installed files/render-node existence
alone do not prove that their driver loads, enumerates hardware or executes commands.
The guest baseline result is `guest-gpu-info.txt` in the same capture directory.

The backend-only run was then stopped through `DEBUG_STOP` and checked with
`scripts/test-root-display-cleanup.sh`: the app reported Stopped, crosvm/root helper/display child
were all absent, and the policy snapshot was unchanged. This cleanup pass is independent of the
failed frame probe and must not be counted as successful rendering.

The bounded guest diagnostic uses `systemctl --no-pager --full`, scopes console input/output to
the captured guest CID, and uses the existing authenticated test account. Credentials are not
printed or embedded in adb arguments.

## Explicit Vulkan application build

GPU configuration is isolated at the existing reflective builder boundary in
`VmGpuConfiguration`. gfxstream now explicitly requests `setRendererUseVulkan(true)`;
2D explicitly keeps it false. Missing/failed API setters propagate to the engine's configuration
error instead of silently selecting a software path. No extra context/blob/renderer-feature
flags, Android permissions or policy changes were added.

The backend-only implementation failed all three new configuration assertions (Vulkan request,
closed 2D baseline, and unsupported-setter error propagation). The initial launch with no Java
installation in PATH failed before tests and is **not** included as a test result. With Android
Studio's JDK 25 selected, the assertions ran and failed, then the fix passed **51/51** app unit
tests with zero skipped/errors. `assembleDebug` succeeded and the packaged APK contains the
arm64 native Binder shim. The host-log checker has **7/7** separate Node regression tests.
These are build/configuration checks, not runtime hardware-acceleration evidence.

### Vulkan-enabled boot: feature gate passes, display precondition fails

The APK installed successfully. The ordinary application started the VM, and the guest agent
became ready. In `.pi/device-debug/root-direct-20261014-103747/`, the actual crosvm arguments
now include `backend=gfxstream` and `vulkan=true`. The native feature log says `Vulkan: enabled`
and selects **Mali-G2-Ultra-NX MC16, Vulkan 1.4.345**. The host-only checker passed. This proves
that the requested feature gate and host-device selection took effect, **not** that a guest
workload executed or that the renderer is error-free.

This boot also logged `Vulkan 1.0 APIs missing from instance`, a TextureDraw GL error `0x500`,
and `Failed to create ColorBufferVk:2`. Keep these unresolved resource/interop diagnostics
separate from the successful configuration check; `Gfxstream initialized successfully!` does
not clear later resource failures.

The display script timed out with no Surface-attach or desktop-request record. Its process
snapshot contains the ordinary app, virtmgr and crosvm, but no root display helper. Window/power
inspection found `showing=true`, `SCREEN_STATE_OFF`, `INTERACTIVE_STATE_SLEEP`, and
`mWakefulness=Asleep`: the locked sleeping activity supplied no display Surface. Unlock/foreground
is therefore a prerequisite for repeating the display test. Do not attribute this missing-Surface
failure to a Vulkan driver or SELinux rule merely because a GPU error also occurred. No Android
policy/settings changes or forced unlock are warranted. The guest-only GPU probe can proceed
independently, but actual presentation remains untested in this boot.

The display test now reads `KeyguardServiceDelegate` before starting a VM and requires a single,
unambiguous awake/on/unlocked state. Off/locked, conflicting, or unknown dump formats fail
immediately with `window-before.txt` for inspection rather than becoming a GPU timeout. This is
read-only and does not wake/unlock the device or change its timeout. The original entrypoint
failed all four initial precondition assertions; after the guard was added, all **6** mocked-ADB
shell-entrypoint cases pass. Together with the existing **7** host-log checks, **13/13** Node
tests pass, as do Bash syntax and diff-whitespace checks. Mocked readiness tests do not prove
that a physical Surface exists or that guest frames render.

### Guest render-node access gate

The independent probe ran as UID 1000 (`droid`) with groups `users`, `sudo`, `video`, and
`renderer`, **not** `render`. The node permissions were `crw-rw---- root render` for
`/dev/dri/renderD128`, and the ordinary-user check returned `RENDER_NODE_NO_ACCESS`.
With only `gfxstream_vk_icd.json` selected and software-forcing removed for this CLI process,
`vulkaninfo --summary` returned `ERROR_INITIALIZATION_FAILED` / no valid GPUs and
`GFXSTREAM_PROBE_EXIT=1`. The diagnostic harness's own successful exit means it collected that
result; it is not a successful GPU probe.

The first temporary-group invocation (`sudo -n -u droid -g render`) returned
`sudo: a password is required` / `TEMPORARY_GROUP_EXIT=1` **before** running Vulkan.
This is a probe-setup/authentication failure, not evidence about driver/capset compatibility.
`getent` also confirmed separate groups `render` (991, no members) and `renderer` (1000, droid).

The bounded replacement uses the guest's existing noninteractive root authorization to run
`/usr/sbin/runuser -u droid -g render -- ...`. The child must verify **UID 1000**, node ownership
and read-write access before running the same ICD query. No sudo policy/password handling is
changed, and Vulkan must not execute as root. This changes neither `/etc/group` nor Android
permissions. Only a successfully created child and its GPU query result can isolate the access
gate or justify a persistent change. Existing ICD files and host Mali selection alone do not prove
that this user's gfxstream driver works.

The replacement probe **passed**. Its child reported `uid=1000(droid) gid=991(render)
 groups=991(render)` and `RENDER_NODE_READ_WRITE`. With only the gfxstream ICD selected,
Vulkan enumerated one `PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU`: **Mali-G2-Ultra-NX MC16**,
vendor `0x13b5`, driver ID `ARM_PROPRIETARY`, driver version `56.0.0`, and Vulkan API 1.3.0.
Both `GFXSTREAM_PROBE_EXIT` and `TEMPORARY_GROUP_EXIT` were **0**. This establishes that
render-group access is sufficient for this non-root account to enumerate the gfxstream device,
not that a submitted GPU workload completed. No persistent guest group change has been made.

### Headless draw/readback experiment

`scripts/guest-gpu-draw-probe.py` exercises **EGL surfaceless + Zink + the selected gfxstream
ICD** as UID 1000 with temporary render-group access. The harness uploads it to a mode-0600
unprivileged temporary file in bounded console lines, verifies its SHA-256 and parses its Python
syntax before execution. It uses a 30-second timeout with a five-second kill grace and removes
the temporary file afterward. The program refuses root, missing node access, unpinned ICDs,
software forcing, or a GL renderer other than Zink/Mali.

The probe compiles vertex/fragment shaders, draws a full-screen triangle into a 32x32 RGBA8
pbuffer, waits for completion, and compares three pixel readbacks against a position-dependent
gradient. A constant clear or a software renderer cannot satisfy its acceptance checks. EGL
objects are released in a `finally` block on normal return/exceptions; timeout/kill terminates
the disposable process rather than guaranteeing Python cleanup. No compositor/KMS/Android
Surface is used. The first probe passed upload hashing and Python syntax, reported
`DRAW_UID=1000 DRAW_GID=991`, then **timed out (`GUEST_GPU_DRAW_EXIT=124`)** before reporting
an EGL version, renderer or pixel. No workload completion is proven. The harness itself exited
0 because it collected the timeout result. Finer EGL-stage markers and kernel/host diagnostics
are the next checks; even a future pass would establish headless GPU rendering, **not visible
Android frames**.
The stage-instrumented repeat also timed out at **`eglInitialize`**, after EGL/GLES library
loading and `eglGetPlatformDisplayEXT` completed. The logged missing `/etc/drirc` and
`~/.drirc` files do not establish the cause. The kernel diagnostic returned no matching errors
in its ten-minute window; `strace` was not available, and neither was installed.

The group-identity control also **failed**: same UID, script, ICD and GPU configuration, with
the account's original `users/sudo/video/renderer` groups retained and only `render` appended
in the disposable child. It verified `DRAW_UID=1000 DRAW_GID=100
DRAW_GROUPS=[27,44,100,991,1000]`, then reported **Segmentation fault / exit 139** at
`eglInitialize`, before context creation or pixels. This does not support missing video-group
access as a sufficient explanation, nor establish the segfault's native-library cause. No
persistent group change is made.

`scripts/guest-vulkan-resource-probe.py` was the next isolation probe: direct Vulkan
logical-device creation, buffer allocation/binding/mapping, command-buffer fill,
transfer-to-host barrier, queue completion and readback, with no EGL/WSI or optional device
extensions. It ran under the same UID/groups/ICD and timeout/upload/cleanup controls. Upload,
Python syntax and native structure-size checks passed, but **`vkCreateInstance` segfaulted
(exit 139)** before device creation, buffer allocation or readback. Structure-size checks do not
prove every FFI argument/offset is correct; a fault in this custom ctypes harness remains a
hypothesis. This result is not evidence of a specific allocation/mapping failure.

The bounded native `vulkaninfo --summary` comparison in the **same VM session** also
**segfaulted in both cases**: original groups plus render (`NATIVE_FULL_EXIT=139`) and
render-only (`NATIVE_RENDER_EXIT=139`), pinned to the same ICD with loader error/warning
diagnostics. This reproduces outside Python and does not support an FFI-only explanation.
The original render-only native query had passed earlier in this boot. The native-result gate
rejects a successful collector when either native child crashed or lacks the expected hardware
identity, and labels a pass as **enumeration only**:

```sh
node scripts/assert-guest-vulkan-enumeration.cjs .pi/device-debug/root-direct-20261014-103747/guest-native-vulkan-comparison.txt
# RED: NATIVE_FULL_EXIT=139; collector success is not GPU success
```

Neither compiler,
GDB nor coredumpctl was found by the diagnostic; no matching kernel lines were returned by
its bounded query. No additional packages were installed.

The stop/cleanup test **failed**: the app reported `Stopping` then `Crashed(reason=killed)`,
not `Stopped`. Host logs show `vcpu requested shutdown` followed by **crosvm PID 6493 exiting
with signal 11 (SIGSEGV)**; this is not a successful normal-stop result. Independent checks
confirmed crosvm, the root helper and its display child were absent, and the policy snapshot
was byte-for-byte unchanged. The ordinary app-owned virtmgr remained until app teardown.
This shutdown crash is retained as a separate failure, not reclassified to make cleanup green.
The Android crash buffer supplies a native backtrace: thread **`v_gpu`**, `SEGV_ACCERR`,
`Cause: trying to execute non-executable memory`, with `pthread_key_clean_all` /
`pthread_exit` below an anonymous PC. This localizes the observed shutdown fault to host
thread/TLS cleanup, **not** a diagnosed BufferQueue/SELinux denial. It does not identify the
responsible renderer/vendor library or prove why the earlier guest calls failed. The stack was
read from ordinary ADB logcat, not by broad root access to other apps' tombstones.

The next control is a **fresh VM with unchanged gfxstream/Vulkan parameters**, gated by
verified absence of the old VM/helper/display layer and unchanged policy—not a claimed
normal-stop pass. The disposable headless bootstrap resets only this
app's Activity/task state after shutdown to avoid restoring the display-experiment toggle;
it neither clears app data nor modifies the manifest/rootfs/groups/policy. It checks native host
Vulkan flags, a single fresh guest console, unchanged policy snapshots and absence of a desktop
request, then runs native `vulkaninfo` before any EGL or custom resource probe. Native results,
not the collector exit code, determine whether the failure survives a fresh session. The first
fresh boot passed VM/agent/host-flag/no-desktop/policy checks (CID 2052), but the diagnostic
**never ran Vulkan**: its end-of-stream prompt regex timed out when the hvc0 login line was
followed by the ttyS0 banner. This is a console setup failure, not a fresh-session GPU result.
The console cue regression now replays that interleaving; the replacement uses only newly
received cues, refuses ambiguous authentication stages, and verifies an authenticated UID 1000
with a fresh marker challenge before diagnostics. Native children also print their non-root
identity and node-access check, and their actual failure now fails the diagnostic command while
still saving its evidence. The fresh native control omits the earlier added
`VK_LOADER_DEBUG=error,warn`, matching the initially successful query's normal environment;
if enumeration recovers, a same-session debug-on/off comparison is still needed to separate
that environment difference from VM restart.

The next prompt attempt also failed **before Vulkan or credential submission**. Its newly
flushed stream contained `default login: [ ... ] systemd[...]` followed by guest
`systemd-hostnamed` / `systemd-udevd` watchdog timeouts. The phone was locked/asleep; the
ordinary app service was foreground, but the app has no CPU wake-lock implementation or
`WAKE_LOCK` manifest permission. This is a power/console prerequisite confound, not a
confirmed Vulkan driver failure. A bounded normal ADB `KEYCODE_WAKEUP` comparison is in
progress; it does not unlock the device or change sleep/battery policy. A subsequent snapshot
showed the device asleep again, so a brief wake alone does not establish a continuously awake
test interval.

That wake comparison eventually passed normal authentication and the UID 1000 challenge and
**did start native queries**. Partial evidence is saved as
`.pi/device-debug/gfxstream-fresh-20261014-041015/guest-native-vulkan-wake-partial.txt`.
The full-groups query reported Mali/ARM_PROPRIETARY and `NATIVE_FULL_EXIT=0`; the render-only
query reached node-access verification but no complete result/end marker was collected.
Terminal echo/redraw fragments contaminated some marker/identity/exit lines, so this is **not a
verified two-control pass**, and sleep/repeated-query effects remain confounded. Ordinary stop
then reported `Crashed(reason=killed)`; crosvm/helper/layer were absent on recheck, but clean
shutdown did not pass. That app label alone does not identify the underlying exit signal.
A subsequent capture of the **old** 12:44 stop interval, saved in this session directory as
`stop-crash-logcat.txt`, confirms another host crash: PID 9756 / thread 9768 (`v_gpu`),
SIGSEGV 11 / `SEGV_MAPERR` 1, fault PC `0x6f665b78e0` (`<unknown>`), again followed by
bionic `pthread_key_clean_all()+132` and `pthread_exit+76`. Thus this session's `killed` label
was also not evidence of an external SIGKILL. No guest EGL/Zink/Python resource/draw probe ran
in this fresh session; ordinary Vulkan queries suffice to precede the recurring host cleanup
fault. This still does not prove that query-free initialization/teardown would fail.

After the user unlocked the phone, the next fresh-session harness was gated on unambiguous
awake/unlocked fields before boot and around native testing. Existing foreground
`view.keepScreenOn = state.isActive` is used; no wake-lock permission, battery setting or timeout
change was added. Public native scripts now travel as encoded input into a non-interactive guest
shell, below the canonical TTY limit; terminal echo cannot contain their literal result markers.
The local wire-format regression failed 3/4 checks before this fix and passes 5/5 afterward,
including execution through the existing Git Bash shell (no skipped checks). UID/authentication
and result-parser tests also pass. Native collection saves post-authentication partial output on
timeout and still returns failure rather than treating collection as success.

### Unlocked native sequence (2026-10-14, fresh capture 045134)

`.pi/device-debug/gfxstream-fresh-20261014-045134` contains a complete, echo-isolated
native comparison. Boot and native-before/after snapshots show `showing=false`,
`SCREEN_STATE_ON`, `INTERACTIVE_STATE_AWAKE`; policy-before/after are identical. These are
boundary snapshots, not a continuous power trace. No guest desktop, EGL or resource/draw
probe ran before these native queries.

- First process, UID 1000 / original groups plus render: the intended Mali/ARM integrated GPU,
  `NATIVE_FULL_EXIT=0`.
- Second process, UID 1000 / only render: readable/writable node, no device output,
  `NATIVE_RENDER_EXIT=124` (20-second bounded native query).

The comparison correctly exits nonzero. Missing render-node access and sleep cannot alone
explain this sequence, but **group differences and process order/session lifetime remain
confounded**. The same-VM rerun under `native-recheck/` restored the complete groups first,
but both queries now segfaulted (`NATIVE_FULL_EXIT=139`, `NATIVE_RENDER_EXIT=139`), despite
verified UID 1000 and node access. Its wrapper's awake/unlocked boundary checks and policy
comparison returned no error. Thus restoring groups alone does not recover this session;
this still does not identify which earlier query/termination damaged it or establish the
faulting component.

The next cold-boot control uses `--vulkan-resource-first`. It keeps the same GPU/image/
policy and headless/no-desktop conditions but deliberately runs **no vulkaninfo beforehand**.
The first GPU process is the existing direct resource probe, which creates one Vulkan instance
and attempts enumeration/device/buffer/submit/readback before destroying that instance. This
separates a working first-process command path from damage after an earlier process exits.
Startup remains conditional on old crosvm/helper/layer absence; normal shutdown failures are
retained as failures, not reclassified merely because resources disappear.

The next resource probe is also gated strictly: UID/ABI/Mali identity, ordered create/map/
submit/wait stages, all 16 expected buffer words, cleanup evidence and one zero child exit.
Its parser rejects the captured `vkCreateInstance` segfault/exit 139 and rejects a pass marker
followed by timeout/crash. Six parser checks and six public-script transport checks pass;
those local tests are not GPU results. Public resource commands use the same echo-isolated
transport and correctly inherit/unset their temporary probe path. No persistent guest group,
GPU parameter, rootfs image or Android policy change has been made for these comparisons.

### Host shutdown crash evidence and a distinct control

A read-only reinspection of the old session's `crosvm-crash-logcat.txt`, `stop-all-logcat.txt`
and `cleanup-progress.txt` confirms that PID 6493 / thread 6515 (`v_gpu`) faulted at
2026-10-14 12:01:53.669: SIGSEGV 11, `SEGV_ACCERR` 2, PC/fault address `0x77a51908e0`,
LR `0x7b4cf3f86c`. The diagnostic explicitly says it tried to execute non-executable memory.
The stack is anonymous `77a4800000` + `0x9908e0`, then bionic
`pthread_key_clean_all()+132`, `pthread_exit+76`, `__pthread_start+188`, `__start_thread+68`.
`vcpu requested shutdown` occurred about 62 ms before the fault. Consequently, even an app
`Crashed(reason=killed)` label cannot by itself be interpreted as an external SIGKILL.

This supports a host GPU-thread exit/TLS-cleanup fault and is compatible with a bad or stale
cleanup callback. It does **not** establish the callback's owner, a Mali/gfxstream library's
fault, workload-induced corruption, or unconditional Vulkan teardown failure. The persisted
capture lacks a full owning mapping/permission range; no complete tombstone is linked there.
It also does not explain guest-side crashes or exclude indirect policy influences.

The distinct shutdown control is a fresh **probe-free** boot with unchanged gfxstream/Vulkan,
rootfs, host build and headless/no-Surface/no-desktop conditions, followed by ordinary shutdown
after renderer/agent readiness. No vulkaninfo, EGL, Weston or resource probe should run in that
control. Recurrence would show those user-space probes are unnecessary; a clean shutdown would
weigh against unconditional teardown failure, without yet proving workload-induced damage.
This comparison must wait for the current VM test and cleanup; do not run a second VM alongside it.

No shader, buffer-transfer, KMS scanout,
Weston acceleration or Android-presentation pass is claimed.

The local Windows environment has no usable Python command; syntax is checked with the
already-installed Python interpreter in the guest, before GPU execution. No package is installed.
The existing Android SDK does provide `emulator/lib64/vulkan/glslangValidator.exe` (14.3.0).
A disposable Vulkan 1.1 compute-shader candidate was compiled **offline only** under
`.pi/device-debug/vulkan-compute-readback.{comp,spv}`: it transforms 16 storage-buffer words
using invocation-dependent unsigned arithmetic; unchanged inputs cannot satisfy the intended
readback. Header/target checks passed. It has **not** been submitted to a GPU; neither compilation
nor a future transfer-only pass would establish shader execution. This prepares a direct-Vulkan
shader test without installing compilers in the guest, if native/resource initialization recovers.

## Rollback

Stop the VM/helper first. The local experiment harness restores only the original manifest,
refusing to overwrite unrelated changes:

```sh
node .pi/device-debug/switch-gpu-profile.cjs restore
```

Restart the app to reload its manifest. This does not undo the separately backed-up guest
preflight repair; see [root display device evidence](root-display-broker.md) for that rollback.
