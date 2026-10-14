# Conservative AVF permission setup

## Boundary

`root/AvfPermissionClient.kt` exposes two fixed capabilities: grant missing AVF permissions to
`pro.darc.viruaisor` for the current Android user, and revoke only the setup journal's originally
absent permissions. Callers cannot supply a package, permission string, shell script, or system
setting. The permission enum contains only `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`.

The UI's **补齐本应用 AVF 权限** button is explicit; obtaining root does not automatically perform
setup. The ordinary app's PackageManager checks existing access before each grant and checks the
result afterward. Existing permissions do not invoke `su` and do not enter the rollback journal.
The root subprocess executes a bounded, one-shot `pm grant --user <current-user>` and exits.
VM creation, lifecycle, vsock, console, and bundle storage remain in the ordinary app process.

## Audit and rollback

Before each attempted mutation, `AvfPermissionRuntime` synchronously writes the originally absent
permission into app-private `avf_permission_journal` SharedPreferences, scoped by Android user.
Failure to persist the journal aborts the mutation. Journaling before execution preserves recovery
information when a command times out after applying a grant. Logs use `ViruaisorPermissions` and
record before/after verification, not just command exit status.

**撤销配置新增权限** uses only those journal entries and leaves permissions present before setup
unchanged. Stop the VM first. Android may terminate the app as a consequence of revocation; reopen
it and retry rollback if necessary. The journal survives process death and is reconciled against
PackageManager. Treat a journaled attempted grant as potentially applied when investigating an
interrupted setup; inspect current permission state before deciding whether to revoke it.

No SELinux policy, AppOps, hidden-API settings, filesystem modes, system installation, or persistent
root modules are changed. A hidden-API error is reported as a separate gate rather than triggering
device-wide relaxation. A grant is not proof of Direct Surface availability.

## Verification

Run `:app:testDebugUnitTest :app:assembleDebug` using the Gradle Wrapper. The permission tests cover
preexisting grants, exact package/user scope, mandatory journaling, partial failure, actual
permission verification, timeout ambiguity, and rollback exclusion of preexisting grants.

On a configured device, open the app, confirm root, press the setup button, and check that **AVF
support** becomes available. In the debug build only, the fixed
`pro.darc.viruaisor.action.DEBUG_GRANT_AVF` activity action invokes the same setup capability;
it accepts no package, permission, or command extras. Run it only after the root check completes.

```sh
adb shell am start -n pro.darc.viruaisor/.MainActivity \
  -a pro.darc.viruaisor.action.DEBUG_GRANT_AVF
adb logcat -d -s ViruaisorPermissions ViruaisorRoot
```

Repeat setup to verify that already-granted permissions produce no root mutation. Then test VM
boot and Direct Surface independently, keeping SELinux Enforcing throughout.
