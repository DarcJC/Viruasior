# Viruaisor

Experimental Android 17 (API 37) application for running a custom Linux VM with the Android
Virtualization Framework (AVF). The app imports a VM bundle through the Storage Access Framework,
keeps VM lifecycle ownership in `VmService`, and controls guest services over vsock.

## Current scope and limitations

- A single VM bundle is imported into app-private `filesDir/vms/default/` storage.
- Guests boot headlessly to `multi-user.target`; desktop services start only on an explicit
  `START_DESKTOP` request after the Android Surface is ready.
- The default graphics profile is crosvm `2d`; `gfxstream` is optional.
- Custom VMs require AVF support, `MANAGE_VIRTUAL_MACHINE`, `USE_CUSTOM_VIRTUAL_MACHINE`, and
  appropriate hidden-API access. Installation alone does not grant this access.
- The experimental Direct Surface adapter is additionally SELinux-gated. It does not work for a
  normal user-installed app on the current test device; graphical presentation requires platform
  policy integration or a separate streaming Display backend.
- The current Ferrochrome kernel has virtio GPU/DRM support built in. A module blacklist cannot
  prevent its early DRM probing; strict deferred probing requires a modular kernel or host-side
  GPU hotplug.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Android UI, AVF lifecycle, VM bundle import, Display adapter and native binder shim |
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
