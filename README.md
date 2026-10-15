# Viruaisor

Experimental Android 17 (API 37) application for running a custom Linux VM with the Android
Virtualization Framework (AVF). The app imports a VM bundle through the Storage Access Framework,
keeps VM lifecycle ownership in `VmService`, and controls guest services over vsock.

## Current scope and limitations

- A single VM bundle is imported into app-private `filesDir/vms/default/` storage.
- Guests boot headlessly to `multi-user.target`; desktop services start only on an explicit
  `START_DESKTOP` request after the Android Surface is ready.
- GPU virtualization through `gfxstream` is the graphics development target. The current default
  is crosvm `2d`, retained as a diagnostic baseline; software-rendered output is not proof of GPU
  acceleration. See [gfxstream validation](docs/gfxstream-validation.md) for separate host, guest,
  GPU-execution and presentation checks.
- The app requests root through KernelSU-compatible `su` on first launch. The Root card shows
  authorization status and provides a retry after enabling Viruaisor in KernelSU's Superuser list.
  A grant is verified by a root child reporting UID 0, not merely by finding an installed `su`.
  The check is read-only and does not change SELinux, grant AVF permissions, or elevate the app process.
- Custom VMs require AVF support, `MANAGE_VIRTUAL_MACHINE`, `USE_CUSTOM_VIRTUAL_MACHINE`, and
  appropriate hidden-API access. Installation alone (or granting a root child) does not grant this access.
- The experimental Direct Surface adapter is additionally SELinux-gated. It does not work for a
  normal user-installed app on the current test device; graphical presentation requires platform
  policy integration or a separate streaming Display backend.
- The current Ferrochrome kernel has virtio GPU/DRM support built in. A module blacklist cannot
  prevent its early DRM probing; strict deferred probing requires a modular kernel or host-side
  GPU hotplug.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Android UI, KernelSU/root authorization, AVF lifecycle, VM bundle import, Display adapter and native binder shim |
| `guest/avf-agent/` | Guest vsock control daemon, desktop helper and Makefile |
| `docs/guest/` | Guest systemd units |
| `docs/` | VM bundle format and Direct Surface/SELinux research |
| `scripts/` | Native build and device diagnostics |
| `gradle/` | Version catalog, Wrapper and daemon JVM configuration |

## Build

Use an Android SDK with API 37 installed. The project pins Gradle 9.6.0, Android Gradle Plugin 9.4.0,
and a JDK 25 daemon toolchain. Configure your SDK in Android Studio or in an untracked
`local.properties` file.

### Native binder shim

The native library is generated and is not checked in. Before packaging an APK, build it in Linux
(or WSL) with Bash, `gcc-aarch64-linux-gnu`, `binutils-aarch64-linux-gnu`, and `file` available:

```sh
bash scripts/build-native-binder.sh
```

This creates `app/src/main/jniLibs/arm64-v8a/libviruaisor_native.so`. Gradle packages that file but
**does not run the native build script automatically**. On Windows, run the script in WSL against
this checkout, then run Gradle from Windows.

### Experimental Venus host validation

The normal app includes a bounded, non-root Venus compute validator and a separate
diskless AVF backend check. Build its generated worker before packaging using the
[Android Venus build instructions](native/venus-host/README.md). Host validation
does not mean AVF guest GPU acceleration is available; see the
[device results and remaining system-backend boundary](docs/venus-host-validation.md).

### Android application

```sh
# Linux/macOS
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

```bat
rem Windows
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. VM images and bundles are not
included in this repository; see [the bundle format and installation requirements](docs/vm-bundle.md).

### Guest agent

Build on Linux with a C compiler and Make:

```sh
make -C guest/avf-agent
# Cross-build a static arm64 binary for the test guest:
make -C guest/avf-agent arm64-static
```

See [the guest agent instructions](guest/avf-agent/README.md) for installation and control commands.

## KernelSU authorization

Install the debug APK and open Viruaisor. The first launch runs a bounded `su -c` probe from the
**application UID**, so KernelSU registers the request for Viruaisor rather than for ADB shell.
Allow **Viruaisor** (`pro.darc.viruaisor`) in KernelSU → Superuser, return to the app, and tap
**重新检测 Root** if the initial request was denied or timed out. The UI must show
**已获得 Root（UID 0）** to confirm success. Requests run off the UI thread, wait at most 60 seconds
for authorization, and are not repeated automatically after a denial or Activity recreation.

The root child only runs `id -u`, `id`, and `getenforce`; `su -v` identifies the implementation.
This works with standard KernelSU `su` and compatible providers; there is no manager-package
hardcoding. A root grant is a prerequisite for future privileged integration, **not** confirmation
that the AVF/Direct Surface SELinux gates have been resolved. No policy injection, system settings,
or permission grants are performed by this authorization flow.

After authorization, **补齐本应用 AVF 权限** explicitly grants only missing
`MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE` permissions to this app and current
Android user. The ordinary app verifies the result and refreshes AVF availability. Existing grants
are preserved; attempted new grants are journaled before mutation. **撤销配置新增权限** rolls back
only that journal after the VM is stopped (Android may terminate the app during revocation).
See [the permission setup design](docs/root-permissions.md) for scope, audit, and verification.

For authorization diagnostics:

```sh
adb logcat -s ViruaisorRoot
```

## Experimental Root Direct Surface

After the one-shot AVF permission setup, the **实验 Root Direct Surface（仅显示桥接）** button
opts into a short-lived libsu root helper for AVF display service lookup and Surface attach/detach
only. VM lifecycle, bundle files, vsock and input continue to run in the ordinary application.
The helper authenticates the full application UID for every RPC, accepts no arbitrary commands or
service names, retains platform Binders privately, and is released with the Surface/VM/UI. No root
daemon or general-purpose cached shell is kept; stuck display operations kill the disposable helper.

This is a **single-VM experiment**, not a production ownership-isolated display API. Android 17's
internal `waitDisplayService()` has no VM identity argument; the helper refuses zero or multiple
crosvm processes, but cannot prove global-display ownership or eliminate process races. Stop
Terminal/other AVF VMs before testing. See [the security and lifetime design](docs/root-display-broker.md)
and [libsu source research](docs/root-display-broker-research.md).

No SELinux or hidden-API settings are changed by the display helper. KernelSU's existing `ksu`
policy is itself broad/domain-permissive: global **Enforcing** does not imply a least-privileged root
child. Success must be verified separately as service access, Surface attachment, and **visible guest
frames**. The reproducible single-VM check is `scripts/test-root-direct-surface.sh`. Unlock the
phone first and keep this app in the foreground: the test refuses off/locked or unverified screen
states before starting a VM, and never wakes/unlocks the device or changes its screen timeout.

## Conservative root policy

Root is a setup capability, not the default execution environment. Prefer a **one-time grant of
only the required Android permissions** to `pro.darc.viruaisor`, then run VM lifecycle, imports,
vsock, UI, and input normally. Check existing access first and leave already-granted permissions
unchanged. If Android permission grants cannot satisfy a gate, keep **only that operation** in an
explicitly allowlisted, authenticated privileged bridge with the shortest necessary lifetime.

Every privileged change must have a reproduced need, an exact package/resource scope, a recorded
result and rollback, and an ordinary-app verification. Keep SELinux **Enforcing**. Do not replace
this with global permissive mode, wildcard permissions, shared `untrusted_app`/`shell` allow rules,
recursive filesystem permission changes, or blanket hidden-API exemptions. Preserve unrelated
settings and grants; persistent modules/system-image changes or device-wide hidden-API relaxation
require separate approval. See [AGENT.md](AGENT.md) for the agent-facing rules.

Root granted, AVF permissions granted, VM running, Direct Surface attached, and guest frames
visible are separate milestones. In particular, root alone does not authorize the app's display
Binder calls, and attaching a Surface alone does not prove the desktop is rendering.

## Device diagnostics

The Bash helpers accept an `ADB` environment override. Their current default is
`/c/Tools/Android/platform-tools/adb.exe` for Git Bash on Windows.

- `scripts/device-smoke.sh`: check AVF availability and the expected VM UI status.
- `scripts/wait-device-ui.sh <text> [timeout-seconds]`: wait for a guest/UI status.
- `scripts/check-direct-surface-selinux.sh`: diagnose Direct Surface attachment; exits with a
  failure when the known SELinux denial is detected.

These helpers require a connected, configured device and an installed APK. Read
[the Direct Surface research](docs/direct-surface-selinux-research.md) before attempting platform
policy integration.

## Local-only files

Build output, IDE/SDK configuration, generated native binaries, signing credentials, and local Pi
debug/research artifacts are ignored. In particular, `.pi/device-debug/` contains local downloaded
VM images and test bundles, not source assets. Keep the Gradle Wrapper JAR in version control.
