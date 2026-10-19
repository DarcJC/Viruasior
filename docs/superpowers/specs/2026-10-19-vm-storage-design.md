# VM 0 storage

Approved in chat: share external-files `storage/0` through AVF virtio-fs; resize the
ext4 root partition only with the VM stopped; preserve recovery data. Device
installation and validation require the user's later confirmation.

## Storage and lifetime

VM ID 0 continues to use AVF name `default`. Its shared directory is resolved by
`getExternalFilesDir(null)`, tag `viruaisor-storage-0`, mounted at `/mnt/shared`.
Only that directory is exported. The backend maps the app UID
to guest droid. Modern AVF uses its application-domain mode; the current device
requires an authenticated fixed-scope root bridge that drops Linux credentials
to the application before serving the inherited socket (see `docs/vm-storage.md`).
Guest mount configuration and
the custom crosvm build must agree with the AVF SharedPath protocol. Display
process authentication distinguishes the exact owned FS backend from competing VMs.

## Offline ext4 transaction

Lifecycle operations are serialized. Require actual AVF shutdown and release the
Venus lease before accessing a root partition for modification. Reject full-disk,
non-ext4 and unsafe paths. A native, packaged e2fsprogs tool runs with the ordinary
application UID. Work on a sparse temporary copy, repair/check it, resize it, check
again, and verify its superblock fits the target before activation. Never truncate
an unshrunk filesystem. Capacity parsing, minimum-size headroom and free-space
checks precede destructive work.

Persist each phase with file and directory fsync. Atomically exchange the candidate
and active images, then rename the old inode to the recovery path. Android denies
hard links on the current device. Interrupted preparation leaves the
original active. A durable `switched` record can resume boot verification after
process restart. Earlier interrupted phases roll back; explicit restore also
rolls back an unverified activation. Guest device-capacity reporting verifies a
new activation; failed boot retains/restores the original
without retry loops. Explicit restore is available while stopped. A VM running
before resizing restarts afterward; a stopped VM stays stopped. Imports and
resizes cannot discard unresolved recovery data.

## Execution and validation

1. Add transaction, capacity, sparse-copy and process-role regression tests.
2. Implement durable rootfs transactions and build packaged arm64 e2fsprogs tools.
3. Wire storage state/actions into VmService, boot verification/recovery and UI.
4. Add AVF SharedPath, guest mount integration and backend build compatibility.
5. Run JVM tests, real ext4 host integration (grow, shrink, content, failures),
   native/guest checks and debug APK assembly. Inspect packaged workers. Document
   the device acceptance steps without running adb, installing or modifying a
   device image.
