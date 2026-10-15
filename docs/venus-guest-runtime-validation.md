# Venus guest compute in an app-private AVF runtime — 2026-10-16

## Scope and outcome

The baseline requested before integration was committed as `fbb9086`.
The candidate now carries a real Android Venus renderer and a paired private
guest ICD. Live AVF tests reach the physical Mali GPU, share mapped DMA-BUF
storage, flush/invalidate caches through the protocol, and compute all 256
input-dependent words correctly for three seeds. Disabling synchronization
produces 256 stale words even though the actual GPU fence completes.

This is a **diskless, headless qualification fixture in the Release main APK**,
not an automatic upgrade of `default`, a production-compatible Vulkan stack,
or proof of a working desktop. The original stock AVF backend remains unchanged.
No Surface is attached and no guest frames have been displayed by this fixture.
Final uninstrumented repeat and rollback/audit results are recorded below.

## Final Release evidence

Artifact: `.pi/device-debug/venus-overlay/Viruaisor-venus-guest-verified-release.apk`.
APK SHA-256: `aba2312077913ac5af5635447095ae361a3e16b4e3bfeb15635918825c0f0101`.
Native candidate bytes in the APK match the pinned hash below; its
`pthread_key_create@LIBC` symbol is undefined/imported, not interposed.

| Run (SGT) | Internal nonce | Ordinary worker / virtmgr / crosvm | Result |
| --- | --- | --- | --- |
| 01:30:05 | d30e3ec297da48258f706834d340767a | 31584 / 31769 / 31805 | 3 x 256 correct; teardown survives; native 0; rollback PASS |
| 01:30:12 | f8082b8ac04d4e84a1433670ba13957b | 31986 / 32157 / 32185 | 3 x 256 correct; teardown survives; native 0; rollback PASS |
| 01:30:18 | 9fc812dad4794660bbab0a4d0702a072 | 32375 / 32509 / 32536 | 3 x 256 correct; teardown survives; native 0; rollback PASS |
| 01:32:58 negative | c17528006f3e47a8aea6e6fa58eb3179 | 631 / 835 / 951 | GPU fence completes; 256/256 stale; correctly rejected; native 0; rollback PASS |

Nonces and FD/inode/UID ancestry, not a remembered PID, authorize the observer.

Positive captures are `guest-verified-positive1.log` through `positive3.log`;
host logs are `guest-verified-positive.android.log`. Each host run records three
successful FLUSH and three INVALIDATE commands, status=1 after the teardown
settle, definitionDeleted=true, native 0 and rollback PASS. No Fatal signal or
TLS trace key appears for those final candidates. Negative capture is
`guest-verified-negative.log` plus its `.android.log`. All are ignored under
`.pi/device-debug/venus-overlay/`, not committed large captures.

Android unit tests: 94 passed. Debug and Release builds passed. Ten native C
test programs passed with ASan/UBSan; the standalone Rust policy passed three
tests, and the existing Venus capture classifier passed three JavaScript tests.
Generated APK includes Binder shim, host worker, overlay helper, candidate and
the separate guest fixture. No instrumentation APK was installed.

The final APK also passed stock owner-death (01:33:29, nonce
`996b5cc21cc543358df6861accd655c6`, worker 2211 / virtmgr 2699 / crosvm 2749)
and lease-timeout (01:34:13, nonce `c53acd31498748a0a8d36dced2c5d1a6`,
3149 / 3431 / 3506). Both recorded all_exited=1, normal-unmount retries=0,
ROLLBACK=PASS and one-shot root destruction. Ordinary-app recovery subsequently
removed the proven fixed definition/nonce files, not any user VM.

Final external audit at 01:35:53:

- Stock crosvm SHA `e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d`,
  dev/inode 1896/831872, size 13593816, mode 0755, crosvm_exec unchanged.
- Stock virtmgr SHA `081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c`,
  dev/inode 1896/832512, size 3922160, mode 0755, virtualizationmanager_exec unchanged.
- AVF APEX remains EROFS read-only. SELinux Enforcing. Init/zygote mount
  namespaces remain 4026535574/4026536794.
- No probe crosvm/virtmgr/worker/root service, snapshot/nonce files, ownership
  marker, experiment mount or experiment-namespace FD reference remained.
- Only `pro.darc.viruaisor` is installed under the project package prefix;
  `pro.darc.viruaisor.test` is absent. Main package has no DEBUGGABLE flag,
  and both existing AVF grants remain true; no grants/settings/policy were added.

Evidence: `stock-verified-owner-death.android.log`,
`stock-verified-timeout.android.log`, `guest-verified-final-audit.log` in the
same ignored capture directory. Root authorization, existing Android grants,
AVF availability, diskless userspace boot and real compute all passed separately;
Surface attachment and guest-frame presentation were **not** performed.

## Implementation and authority

- One executable bind in the existing disposable `pro.darc.viruaisor:venus_vm`
  mount namespace. Root only establishes/revokes that pinned lease and observes
  authenticated executable ancestry. VM create/run/stop/delete remain UID 10278.
- Keep stock virtmgr and its existing CAP_SYS_NICE. The temporary crosvm copy
  has no file capabilities and executes with CapEff=0 in the existing crosvm MAC
  domain. Source is installer-owned, fixed SHA/size, read-only FD anchored;
  callers cannot supply paths, commands, PIDs, hashes or mount targets.
- Pinned Android rutabaga chooses its embedded thread render server and requires
  Venus capset 4; initialization failure cannot fall back to a 2D device.
- Android graphics allocator HAL allocates an AHardwareBuffer BLOB with GPU-data
  and appropriate CPU usage. Only the unique bounded DMA-BUF FD is selected;
  the accompanying metadata memfd is excluded. The AHB and duplicate FD remain
  owned until Vulkan allocation release. Native Vulkan import memoryTypeBits
  must include the exact requested type; there is no silent type substitution.
- The actual crosvm domain cannot open `/dev/dma_heap/system`: EACCES plus a
  read-only kernel compute_av query returned no read/open/ioctl allow bits.
  Existing graphics-allocator HAL access works without a root allocator broker,
  permission grant, driver replacement or SELinux change.
- Guest type 15, flags 0xb maps to native Mali type 14, compatible with FD mask
  0x7e02. It is HOST_VISIBLE/HOST_CACHED but **not HOST_COHERENT**. Allocation is
  page-rounded (4096 bytes for the 1024-byte buffer), bounded to 64 MiB.
- Mesa 25.0.7 normally hides noncoherent cached mappings and its virtgpu BO cache
  methods are no-ops. The private ICD preserves native flags and forwards the
  existing Vulkan flush/invalidate commands to the paired host. Host validates
  allocation/ranges and performs whole-DMA-BUF START/END cache maintenance,
  retrying EINTR/EAGAIN and propagating errors. GPU barriers and finite native
  fence completion remain separate from cache maintenance.
- Disable optional fence/semaphore/event/query feedback polling, which requires
  incompatible coherent import memory. Actual protocol fence/status queries
  are used, not simulated completion. Other allocations/usage combinations are
  not broadly qualified; do not present this as general Vulkan conformance.

## Teardown and regression findings

The first owner-death test exposed EBUSY on rollback: polling two pidfds returned
when only one descendant exited. The fix waits for **all** captured descendants
and retries only transient EBUSY for bounded normal unmount; no lazy detach.
Both repaired owner-death and 20-second lease-timeout tests passed before
candidate execution. Ordinary-UID recovery removes only a fixed probe definition
whose internal nonce ownership marker is valid and whose native snapshot is gone.

A later repeated final-APK run caught another cleanup race: Kotlin writeText
truncated the one-byte release file before rewriting it, allowing native pread
to observe length zero. OverlayLeaseControl now validates the fixed control and
updates offset zero without resizing, followed by fd sync. Malformed controls
are preserved/rejected; regression tests and three final positive repetitions
pass. The raced run had rolled back correctly but is not counted as a pass.

Guest computation initially passed before an asynchronous renderer-thread
teardown crash. A 500 ms VM-status settle gate now rejects that false lifecycle
success. Opt-in pass-through TLS registration tracing identified two separately
loaded `/system/lib64/libcrypto.so` instances. The exact faulting callback was
the instance used from the existing `system` linker namespace, not the APEX
default-namespace copy first pinned. Retaining the system instance fixed the
diagnostic run. Both code instances are retained for this bounded process;
no destructor is suppressed and no TLS key is deleted.

The existing APEX linker configuration already exports `system`. Lookup and
`android_dlopen_ext` use that existing authority only, like the platform's
[namespace-aware loading mechanism](https://android.googlesource.com/platform/system/core/+/android16-qpr2-release/libvndksupport/linker.cpp).
No namespace is created or reconfigured; linkerconfig and system libraries are
not modified. This uses platform-private APIs and is OEM-specific, not portable
NDK behavior. Diagnostic interposition is opt-in and excluded from the final VMM.

## Source/build pins

- crosvm: `c7dd8d6950912fa5e6fde7ec2d1616e0ddc090f8`.
- Android rutabaga: `1f181b0b3e4b821459f21fd9025cd4c408c4f9ec` (0.1.76).
- virglrenderer: `07a5bc398dfffdc36ad20a3476348753aa10a322`.
- Venus protocol: `ca19b6358d7cc491bc3e4de76f04c6700876a8fa` (1.1.3).
- Mesa guest: `742a20f48c59e8649533c84c4d49dd95b403f5da` (25.0.7).
- Candidate: 13,277,408 bytes,
  SHA-256 `a4a71002533174408d0acdbfe3d4980482087bc9a29d0428ac423579bce9029b`.
- Guest ICD (stripped fixture copy):
  `7534e4d7c03780daf129a55c02bb3f0b06fca61ea6d2f40ccf03a529d2f7ab30`.
- Fixture init: `f27f27496143594cc33f56c7d4d180eeb8f62b74a82d5264049b52dbd1e43978`;
  gzip cpio: `ea6058e372cc96965037a1a2a3c127a4eb105e4492da40c7843fcbc16a8a2c1f`.

Build inputs extracted from the local stopped guest-image copy are read with
debugfs, never mounted, chrooted, executed or written back. The fixture uses the
guest's real glibc 2.41 runtime. A checksum-pinned Debian glibc 2.41 development
SDK is extracted **on the build machine only** to avoid newer host-header ABI
conflicts; no guest or Android runtime is replaced. ICD symbol requirements top
out at GLIBC_2.38, init at GLIBC_2.34, both below guest 2.41.

With the pinned source checkouts and renderer/dependency workspace provisioned:

```sh
export ANDROID_NDK_HOME=/tmp/viruaisor-venus-host-20261015/android-ndk-r30
export VENUS_WORK_DIR=/tmp/viruaisor-venus-host-20261015
bash scripts/build-venus-crosvm-candidate.sh
export VENUS_GUEST_MESA_WORK_DIR=/tmp/viruaisor-venus-guest-mesa-241
bash scripts/build-venus-guest-mesa.sh
export VENUS_GUEST_ICD=$VENUS_GUEST_MESA_WORK_DIR/build/src/virtio/vulkan/libvulkan_virtio.so
bash scripts/build-venus-guest-probe.sh
bash scripts/test-venus-runtime.sh
```

The Mesa script expects the read-only extracted guest libraries under
`/tmp/viruaisor-venus-guest-build/root/lib`. Initial provisioning needs those
libraries before the paired ICD exists; existing engineering workspaces retain
them. These scripts are pinned engineering builds, not a clean-room downloader.
Before Gradle packaging, copy the exact reviewed candidate into generated
`jniLibs/arm64-v8a/libvenus_crosvm.so`, keep its SHA/size pin synchronized, and
build the native Binder/overlay/host workers separately. AAPT expands the `.gz`
fixture into `assets/venus/guest-probe.cpio` (6,702,592 bytes).

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Sign/install the same-signature non-debuggable Release main APK normally;
do not install a test APK, use root installation, or change installer/security
settings. Existing developer certificate SHA-256 is
`82c1caf021cab5f539ed3db1c9ce1e53d56e1866deed357670bb2b598e647634`;
this is not a new production/Play signing key.

## Device exercise and rollback

The unexported worker can be started for engineering validation using a root
ADB launch of its fixed action. The service itself and AVF Binder calls are
ordinary UID; the root bridge receives only its internally generated nonce and
fixed `venus-exec` capability, not arbitrary shell arguments.

```sh
adb shell su -c 'am start-foreground-service -n pro.darc.viruaisor/.gpu.VenusOverlayProbeService -a pro.darc.viruaisor.action.OVERLAY_SENTINEL_PROBE --es mode venus-exec --ez venus_gpu true'
# Negative control only: append --ez skip_cache_sync true
```

Require matching run nonce, capset4, actual Mali identity, noncoherent cached
mapping, all three seeds/256 correct words, no renderer crash, ordinary VM
definition deletion, native_exit=0 and ROLLBACK=PASS. Negative control requires
fence completion plus stale readback; it is never accepted as a positive compute
pass. A cached previous console cannot satisfy a new run: output files are
cleared synchronously before reader scheduling.

Normal completion stops/deletes only the fixed disposable VM, releases the
one-file bind, verifies original inode/bytes, removes nonce files, exits worker
and one-shot root service. Owner death or lease expiry terminates only captured
pidfds, waits for all, then unmounts. If freezing killed the worker first,
`--es mode stock-recover` performs nonce-proven recovery under ordinary UID.
Do not manually delete a user VM or force unmount an unidentified target.

No persistent deployment exists to undo. Ending the worker/lease restores the
stock executable view; the installed APK may retain inert candidate/fixture
bytes. Reinstalling the earlier same-signature main APK reverses the app changes
without changing system partitions. Xiaomi background freezing may delay app
execution; do not change power policies or claim a hard wall-clock bound during
host suspension. Normal same-APK reinstallation was used to obtain runnable
test windows; no screen-lock bypass or permission/power-policy change.

## Remaining boundary

There is no qualified Surface/scanout, image sharing, GL/desktop, performance or
Vulkan-conformance result. The paired ICD and candidate apply only to this fixed
test guest and device baseline, not arbitrary guest disks or stock renderers.
The default VM profile, its imported disk and guest-installed Mesa are untouched.

GBM was omitted from this candidate's statically linked allocator, not proven
impossible in hardware. Merely compiling it does not provide a matching Mali
GBM backend; this device exposed card0 but no DRM render node and no libgbm in
the checked system/vendor/APEX library directories. GBM is not inherently faster
than this DMA-BUF route; no comparative benchmark was run.
