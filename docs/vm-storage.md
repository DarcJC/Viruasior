# VM 0 shared storage and offline rootfs resizing

Device acceptance passed on M367FC / Android 17. The exact firmware's
AVF API differs from the current AOSP API; the compatibility route below was
required to mount the external files directory. See the
[device report](vm-storage-device-validation.md) for the checks, firmware limits
and backup cleanup.

## Shared files

The application resolves `getExternalFilesDir(null)/storage/0`, normally
`/storage/emulated/<user>/Android/data/<applicationId>/files/storage/0`. The
installed package is resolved from Context metadata, never fixed in the runtime
path, permission or process-identity checks. VM ID 0
continues to use AVF name `default`. The exported tag is `viruaisor-storage-0` and
the guest mount point is `/mnt/shared`. Only this directory is exported, with
guest droid UID 1000 / users GID 984 mapped to the application UID and umask 022.
These IDs match the prepared Manjaro image; different guest user/group layouts
require matching shared-path mapping before use.
The root partition stays in private `filesDir/vms/default/root_part`.

On AVF versions with application-domain `SharedPath`, the framework starts the
backend. M367FC's nine-argument API lacks this mode. Its stock `crosvm_vu_fs`
domain cannot traverse the app's Android FUSE directory, and an app-domain
backend cannot receive the VM memory FDs under the existing SELinux policy.
The paired Venus runtime therefore reuses one inherited AVF switches socket as
an explicit, fixed-tag vhost-user FS channel. This conversion requires the exact
`viruaisor.storage_fd=1` marker and fixed mount argument; ordinary switches input
is unchanged. The app reflects only its own AVF `mSwitchesSock` endpoint.

`RootStorageService` authenticates the application UID, `:venus_vm` process and
Binder death token. It launches only the installer-owned, hash-pinned FS worker
for the server-derived `storage/0` path in that app's mount namespace. The launcher
drops all UID/GID slots to the app and clears capabilities, retains only Android's
9997 traversal group, and sets no-new-privileges before exec. Its SELinux domain
remains `ksu`; this is a narrow privileged bridge, not an app-domain worker.
No policy rules, external-storage grants or system images are changed. VM
lifecycle and main crosvm remain under the ordinary app UID/domain.
The launcher and forked FS worker are bound to the root service lifetime; a
client pidfd additionally stops them when the original app process dies. Child
spawn/stop use stable pidfds, with TERM/KILL escalation and descriptor cleanup.
Shared data, positive/negative dentries and attributes are uncached, with
writeback disabled, so changes from either side are visible immediately.
The FS write guard restores `CAP_FSETID` only when it was effective before the
write; an unprivileged worker never attempts to acquire this capability.

The framework route's private socket is `filesDir/shared/0.sock`. A stale socket
is removed only when no matching backend is present, and only if it is a socket.
The Venus crosvm build enables `fs_runtime_ugid_map` and its new executable hash
is pinned in `RootOverlayArtifactPolicy`. The Root display guard excludes only
the exact owned backend command after checking UID, capabilities and executable
provenance. Unknown crosvm processes still count as competing VMs.

Guest mounting uses the fixed kernel argument
`systemd.mount-extra=viruaisor-storage-0:/mnt/shared:virtiofs:rw,nodev,nosuid,nofail,x-systemd.device-timeout=10s`.
This requires systemd 254 or newer and a working guest virtio-fs driver. The
`nofail` mount does not make sharing a condition for guest startup. The paired
agent authenticates the mount source, type and destination when reporting status.
Files requiring Linux ownership, links or other full filesystem semantics belong
on ext4 rather than the Android FUSE export.

See the [systemd generator documentation](https://www.freedesktop.org/software/systemd/man/257/systemd-fstab-generator.html),
[AVF SharedPath implementation](https://android.googlesource.com/platform/packages/modules/Virtualization/+/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java)
and [crosvm shared directories](https://doc.crosvm.dev/book/devices/fs.html).

## Capacity changes and recovery

The Storage card accepts an integer GiB target. A running VM shuts down before
resizing and starts again afterward; a stopped VM stays stopped. Boot failure
restores the old disk and leaves it stopped, with an error, instead of retrying.
The service serializes lifecycle actions and waits for actual AVF shutdown,
descriptor release, Venus teardown and FS backend exit before modifying a disk.

Only raw ext filesystem partition images are accepted, not GPT/MBR disk images.
The resize and recovery paths reject symlinks. A file lock excludes concurrent
storage operations. Preflight checks packaged tools, target range, source
geometry and room for a complete working copy plus host headroom. Minimum shrink
size is estimated by resize2fs with at least 256 MiB / 10 percent headroom; the
actual resize and subsequent e2fsck remain authoritative.

The transaction directory sits beside the root image at `.rootfs-resize/`:

| Durable phase | Meaning / recovery |
| --- | --- |
| `preparing` | Copy/check/resize only `candidate`; recover by removing preparation files. |
| `ready` | Candidate is checked and durable. Atomically exchange candidate/active inodes, then rename the old inode to `original`. An interruption before `switched` rolls back, including between exchange and backup rename. |
| `switched` | New image is active; retain `original`. A later process may resume its boot verification. |
| `restoring` | Rollback started; restore is safe to repeat even after the backup rename already completed. |
| `verified` | Guest reported the expected root block-device size; cleanup can finish after an interruption. |

Journal updates use a synced temporary file, atomic rename and directory fsync.
Activation uses native `renameat2(RENAME_EXCHANGE)` and syncs both affected
directories. The active pathname stays present and the old inode is retained
without hard links (denied on this device) or another full backup copy.
Growing enlarges the working file before
resize2fs. Shrinking resizes/checks ext4 before truncation; the superblock must
match the requested capacity. The original is never passed to a modifying tool.
Corrupt journals or missing backups fail closed and retain recovery files.

Boot verification requires `STORAGE_STATUS` from the paired avf-agent. Its response
is `OK STORAGE <root-block-device-bytes> <0-or-1-mounted>`. The root device is
resolved from `/` through sysfs; the mount is checked through `/proc/self/mounts`.
This read-only command runs before desktop startup. A capacity mismatch, missing
agent support, startup error, premature stop or 180-second verification timeout
causes controlled rollback once the VM has stopped. A failed rollback retains
the journal/backup and reports that recovery needs attention.

The Storage card provides explicit restore while stopped. Restoring discards
changes made to the unverified new root disk; the external shared directory is
independent. Bundle replacement is blocked while recovery data is unresolved.

The importer and resize copier skip all-zero buffers to preserve sparse storage.
Logical capacity is not reserved physical space. Online discard/fstrim has not
been enabled or qualified as part of this change.

## Build and local verification

Build native tools in WSL/Linux with the pinned NDK r30:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r30 bash scripts/build-rootfs-tools.sh
ANDROID_NDK_HOME=/path/to/android-ndk-r30 bash scripts/build-storage-process.sh
make -C guest/avf-agent test
make -C guest/avf-agent arm64-static
```

The native script builds e2fsprogs v1.47.2 at
`c3cce4a07efefc62bc7fc57a678cb870af27d0f2`. Only libc/libdl are dynamic dependencies.
Both packaged arm64 executables install a parent-death signal and are terminated
if their owning application process dies. License notices are packaged in the
APK. The desktop crosvm must also be rebuilt using the existing pinned renderer
workflow, with `fs_runtime_ugid_map`, and its artifact pin updated deliberately.
The desktop build runs a regression against its actual scoped-capability code
and host libcap. It checks an initially effective capability, one present only
in the permitted set, and an empty permitted set under UID 65534. None of these
tests changes device credentials or installs host libraries.
After preparing and building that crosvm workspace, rerun these cases with
`bash scripts/test-storage-fsetid.sh`.

On Windows, with the existing JDK configuration:

```powershell
$env:ROOTFS_INTEGRATION='1'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
.\scripts\verify-native-packaging.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
```

The opt-in integration test uses WSL e2fsprogs on temporary host images: 512 MiB
to 1 GiB, rollback to 512 MiB, shrink to 384 MiB, reject an unsafe 64 MiB target,
and check a marker file throughout. JVM tests also interrupt each journal phase,
interrupt rollback/cleanup, reject malformed recovery state and failed/no-op
resizes, and authenticate the exact FS command. Guest C tests check mount
authentication. APK inspection checks nine arm64 ELFs, the desktop hash, exact
ext4 tool bytes and bundled notices.

## Device acceptance after user confirmation

Use both the built APK and newly built `guest/avf-agent/avf-agent`. The installed
guest agent must be updated with a recoverable copy of the original first, or a
fresh bundle prepared using `scripts/build-manjaro-bundle.sh`. An older agent
cannot confirm a resized root disk; the app deliberately rolls it back.

1. With a saved root image and the paired guest agent, install the APK and boot.
   Check the Storage card reports the shared mount, and verify file creation,
   edits, renames and deletion in both directions.
2. Confirm the desktop/display guard works with both FS backend processes and
   that stop/restart leaves no stale backends or sockets.
3. Grow from the current 8 GiB disk to a larger target. Verify guest partition
   size and filesystem size, original file content and subsequent backup cleanup.
4. Shrink to a safe target, then try a target below the safe minimum. Check
   capacity/content and that rejection preserves the original disk.
5. Repeat from both running and stopped states, including closing/reopening the
   app before first boot verification. Verify only the previously running VM
   restarts automatically.
6. Exercise interrupted preparation, interrupted activation and failed guest
   verification with a disposable copy. Verify restart recovery and explicit
   restore preserve the expected root image and external shared files.

Keep SELinux Enforcing and the existing app/root permission policy. The legacy
firmware compatibility route requires the app's existing KernelSU authorization
for its bounded bridge. It does not export raw `/data/media` or make persistent
system changes. The modern framework-only route has not been device-qualified.
