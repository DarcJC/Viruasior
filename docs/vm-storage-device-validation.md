# Storage device acceptance, 2026-10-19/20

Status: accepted; installed and verified, task backups deleted after success at
01:11:46 on 2026-10-20. Device: M367FC, Android 17/API 37, ordinary app UID 10278, SELinux
Enforcing. VM ID 0 uses AVF name `default`, Manjaro droid UID 1000/GID 984.

## Recovery baseline

Before installation, the stopped 8 GiB raw ext4 root partition and `vm.json`
were copied to app-private `files/storage-validation-20261019/`. The root image
and backup SHA-256 both matched
`1c131fb73245f3be606ae6a596bea1c1221539f3829ea1ad8367428e989cc7c4`;
the manifest hash was
`0c4806271ca1b48b705183e2e4ffdb7af360125c4d678ec8298c34085b9e5974`.
The installed APK was also saved locally. The paired static avf-agent was
updated offline and dumped back from ext4 to verify SHA-256
`dfc1c6cfabc1f2a1d1bf6cd229a188a670e93817b1de3c2d3def12940e414a63`.
Offline e2fsck checks were run around the update. No host mount was used.

Evidence is retained in ignored `.pi/device-debug/storage-validation-20261019/`.
The older unrelated VM backup is outside this task's cleanup scope.

## Firmware findings and fixes

- This firmware has the nine-argument SharedPath API, without app-domain mode.
  The stock FS domain failed external directory traversal. Moving the backend
  to the app domain got further but SELinux blocked VM memory FD transfer:
  `SET_MEM_TABLE`, `recvmsg MSG_CTRUNC`, no received memfd. These were recorded
  before adding the fixed-scope bridge described in [vm-storage.md](vm-storage.md).
- Root-launched FS workers enter the app's mount namespace, then drop UID/GID
  and capabilities. `/storage` is 0710 shell:everybody; only GID 9997 is retained
  for traversal. The worker retains the `ksu` SELinux domain. Main crosvm,
  virtmgr, Binder lifecycle and vsock remain ordinary app processes.
- Android's `/data/user/0` and `/data/data` aliases require canonicalizing the
  trusted filesDir once before checking untrusted leaf paths.
- This device denies app hard links with EACCES. Rootfs activation now uses
  atomic `renameat2(RENAME_EXCHANGE)` and durable directory fsync.
- The real bidirectional test reproduced a stale guest dentry after an Android
  deletion. Default metadata cache timeout was five seconds. The fixed worker
  now uses `cache=never,timeout=0,negative_timeout=0,writeback=false`.
- The upstream CAP_FSETID guard unconditionally restored a capability that was
  absent at entry. A real Linux libcap test reproduced both EPERM with zero
  capabilities and unintended elevation when only the permitted bit existed.
  The guard now changes/restores only a previously effective capability. All
  three credential cases pass; the final candidate has no new shared-library
  dependency. This does not grant any capability to the device worker.

No permissive mode, SELinux allow rules, persistent root modules, system image
changes, or broad external storage grants were made. Exploratory appops writes
did not take effect and were reset to their original default.

## Checks completed

| Check | Evidence/result |
| --- | --- |
| Real guest mount | Agent authenticates `viruaisor-storage-0`, `/mnt/shared`, `virtiofs`; mounted=true. |
| Live bidirectional file operations, 8 GiB | Android instrumentation runs file I/O as UID 10278; temporary systemd fixture runs as droid 1000:984. Both directions create/read/write/rename/delete passed. |
| Payload integrity | 256 KiB deterministic payload SHA-256 `743cdf7849dc6ffdc775371adb60313afb6ecb74e2a8ef22f63c8d419e0b36ec` matched on both sides. |
| Stopped growth, 8 to 12 GiB | 00:48:53–00:49:18; active pathname continuously present, original 8 GiB inode retained, VM stays stopped. |
| Cold process verification | Force-stopped after completed activation; new process resumed verification. At 00:50:33 agent reported 12 GiB and mounted=true, journal/recovery disk cleaned. |
| Data after growth | Rootfs marker and live bidirectional file test passed at 12 GiB. |
| Running shrink, 12 to 8 GiB | 00:50:59 request; guest shutdown confirmed, FS backend released, copy/check/shrink completed 00:52:04; automatic reboot verified 8 GiB and mounted=true at 00:52:08, recovery directory removed. |
| Unsafe 1 GiB target | Rejected below the safe ext4 minimum; no recovery files left. Whole active image SHA-256 before/after both `0ee2f867fa0eda7ee2d6d6619eaaf1403d4c815af5b794d334b356485b9e557d`. |
| Interrupted copy | App force-stop during candidate copy left original 8 GiB untouched. Explicit restore cleaned preparation files at 00:36:18. |
| Activation hard-link failure (before fix) | EACCES caused safe rollback, active image remained 8 GiB. Replaced hard links with atomic exchange and reran growth successfully. |
| Interrupted atomic exchange | Actual `renameat2(RENAME_EXCHANGE)` exit was delayed using strace injection; after observing active=12 GiB, candidate=8 GiB, journal=ready, the app was force-stopped. The tracer was terminated to release the killed tracee. Explicit restore at 00:56:39 exchanged back and cleaned the journal. Whole-image SHA-256 matched `0ee2f867fa0eda7ee2d6d6619eaaf1403d4c815af5b794d334b356485b9e557d`. |
| First boot crash | A second 8→12 GiB activation was completed. Main crosvm was killed 0.7 seconds after appearing, before agent verification. AVF reported killed; automatic rollback completed at 00:57:46 and left the VM stopped, with no recovery directory/backends. The restored whole-image hash again matched `0ee2f867fa0eda7ee2d6d6619eaaf1403d4c815af5b794d334b356485b9e557d`. |
| Final candidate at 8 GiB | 00:58:22 ordinary-app/droid bidirectional probe, root marker and mounted=true passed after shrink and both recovery tests; no CAP_FSETID warning recurred. Full helper teardown completed before the runner exited. |
| Unexpected FS backend exit | Killed only the authenticated FS parent at 00:58:58. Its worker died, the app detected the failure, stopped the VM and removed all helper/VM processes. A fresh start mounted successfully. |
| App process death | At 00:59:33 force-stopped the package with an active guest and two FS processes. No crosvm, virtmgr, FS worker or root helper remained. |
| Offline integrity | After the crash tests, replayed the guest journal offline, removed the temporary guest unit/script/marker, and ran e2fsck -fn successfully. Ext4 clean, 2,097,152 × 4096-byte blocks = 8 GiB. Agent dump still matched the pinned hash. Fixture was temporarily re-added to qualify the subsequent package-identity refactor. |

## Application identity correction

New and existing runtime package dependencies now use trusted Context metadata
or generated `BuildConfig.APPLICATION_ID`: shared paths, exact process names,
native overlay files roots, permission grant/revoke targets and Intent actions.
Native helpers receive identity from their authenticated root service, validate
package syntax, and restrict paths to that package's UID/user. The root display
observer derives its shared path without calling Android's UID-checked
external-files API as UID 0; that API caused the initial display regression.
Source/JNI namespaces and existing Binder wire interface IDs remain stable.

An alternate `org.example.viruaisor.validation` applicationId build and JVM
suite passed. Then the default build ran all 184 JVM tests, including real ext4
growth/shrink/rollback, with zero failures or skips. Native package/path tests
also cover another applicationId, multi-user paths, cross-package rejection and
malformed names. Build override:

```powershell
.\gradlew.bat '-Pviruaisor.applicationId=org.example.viruaisor.validation' :app:testDebugUnitTest :app:assembleDebug
```

## Final installed state

- Root: bounded helpers obtained UID 0. FS leaf uses app UID 10278, effective
  capabilities zero, supplementary traversal GID 9997, no-new-privileges and the
  existing `ksu` SELinux domain. No new system policy or broad storage permission.
- Android permissions: both MANAGE_VIRTUAL_MACHINE and
  USE_CUSTOM_VIRTUAL_MACHINE granted; MANAGE_EXTERNAL_STORAGE remains default.
- AVF available; VM booted and paired agent ready. Root block device and clean
  offline filesystem both 8 GiB. No rootfs recovery directory remains.
- Shared storage mounted at `/mnt/shared`. After the package refactor, the live
  ordinary-app/droid probe passed again at 01:07:10. Normal stop remained Stopped
  through complete helper teardown. Guest fixture/unit/marker and shared probe
  directory were then removed; final e2fsck and agent hash checks passed.
- Root display Surface attached with the two FS processes correctly excluded by
  its guard. Actual guest frames were displayed. A screenshot shows the Manjaro
  desktop and Storage card with 8 GiB/mounted; the metadata-derived PowerShell
  pixel probe passed 400 lit samples and 34 colors. Physical panel color
  correctness was not independently measured.
- Installed APK matches the local build SHA-256
  `d488a13d1618d45f2d7bc102eea58c294fc40c3daeee46527e338a610b957d1d`.
  Desktop candidate SHA-256 is
  `ccdf777ee6e2098cf92d7b51cc0d47f403714c170b568e04daf9165140708571`.
- Final script checks: 117 Node/mock tests and seven native overlay tests passed;
  PowerShell/Bash/Python checks passed. Host script targets resolve from APK
  metadata, with validated PACKAGE/--package overrides. Android-only diagnostic
  entrypoints require an explicit package because host metadata is unavailable.

Immediately before cleanup, the original backup's full hash still matched the
baseline. Cleanup checked exact app-private scope, distinct active/backup inodes,
active capacity and absence of a pending transaction. It removed this task's
`files/storage-validation-20261019/` (root image, manifest and old agent copies)
and local `baseline.apk`. Temporary device uploads, traces and test UI dumps were
removed. Other VM directories were outside the cleanup scope. Logs, hashes and
screenshots remain locally; the current VM and its shared files remain usable.

Only this firmware and paired guest were device-qualified. The modern
framework-only SharedPath branch remains unqualified. Abrupt process termination
and syscall interruption were tested; physical power-cut durability was not.

## Test harness

`StorageSharingDeviceTest` is opt-in. It requires this task's fixed guest fixture
and backup/evidence directory; it does not alter the production guest agent's
command interface. It exchanges files live and checks UID/GID, full payloads,
root device bytes and a rootfs marker. It waits for boot verification and full
VM/helper teardown before instrumentation exits. A read-only shell process
observer is used only to wait for teardown; shared file I/O is the ordinary app.

```powershell
adb shell am instrument -w -r `
  -e class pro.darc.viruaisor.StorageSharingDeviceTest `
  -e storageValidation 20261019 -e rootBytes 12884901888 `
  pro.darc.viruaisor.test/androidx.test.runner.AndroidJUnitRunner
```

The temporary guest unit/script and rootfs marker are removed after acceptance.
No fixture or arbitrary shell execution command is added to the production
agent. A final clean boot validates the installed agent and normal mount path.
