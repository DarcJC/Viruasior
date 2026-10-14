# AVF private-overlay execution checklist

User approved the scoped experiment on 2026-10-15. Do not unlock or bypass device
authentication. Do not replace system files/APEX, change global mount namespaces,
SELinux policy, hidden-API settings, permission grants or persistent modules.

1. Record baseline namespaces, installed APK certificate and original crosvm/virtmgr hashes.
2. Add fail-closed authorization/identity/namespace/lease tests before implementation.
3. Build a normal Release APK with an unexported, same-UID `:venus_vm` foreground
   service. Root may start this unexported lab service for ADB testing while locked;
   the service itself and future AVF lifecycle remain ordinary app UID.
4. Add one narrowly authenticated root Binder capability for the app-owned sentinel
   experiment. A native child enters only the calling lab process's exclusive mount
   namespace, checks private propagation, temporarily binds a fixed sentinel file
   read-only, waits for an ordinary-app observation, then unmounts and exits.
   It accepts no arbitrary command, mount target or caller-selected process.
5. Verify original/mapped/restored bytes from the ordinary lab process and unchanged
   control-process views. Exercise timeout/client death and refusal of unsafe identity.
   Stop before any crosvm covering mount if isolation or clean rollback is unproven.
6. Only if the sentinel gate passes, test a byte-identical stock crosvm copy and
   its real AVF execution/domain/loader gate in that private namespace, diskless/GPU-off.
   A denial is a result, not authorization to inject policy or alter system labels.
7. Only if the execution gate passes, build and try a compatible Venus-enabled
   candidate and Android allocator/blob integration; prove guest mapping/cache/fences
   and full GPU readback. Host-only and initialization-only success do not qualify.
8. Run required Gradle/native/regression tests, remove experiment helpers/VMs/mounts,
   preserve user VM data, retain the normal APK, and report the highest proven gate.

Testing uses bounded processes and fixed app-private lab filenames. Any need for
global changes, additional authority, install confirmation, or an unreviewed
privileged capability stops that stage. No automatic privilege expansion is allowed.
