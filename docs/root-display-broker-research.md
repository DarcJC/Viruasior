# Minimal root display broker: libsu 6.0.0

Scope: source-only research for Viruaisor/API 37, KernelSU 3.3.0, root domain `ksu`. No code/device changes or runtime tests. Downloaded sources reside only in ignored `.pi/research-cache`. libsu's actual release tag is **`6.0.0`**, not `v6.0.0`.

## Recommendation and policy boundary

**Conditionally suitable:** use a normal, non-daemon `RootService` exposing only Surface attach/detach. Keep both AVF platform Binders entirely inside the helper. This is a minimal interface, **not a least-privilege root process**.

Audited libsu startup does **not** write global `hidden_api_policy`/exemption settings, call `VMRuntime.setHiddenApiExemptions`, run `setenforce`, or inject SELinux rules. Its default shell runs `su` (optional mount-master mode is unnecessary); startup writes an app-cache trampoline and launches root `app_process32/64` with `-Xnoimage-dex2oat`. It uses hidden framework reflection, not a global hidden-API-policy change. README's `setenforce 0` example is optional user code, not automatic startup. Sources: [BuilderImpl](https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/internal/BuilderImpl.java), [ShellImpl](https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/internal/ShellImpl.java), [manager](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java).

However, KernelSU itself already changes policy: 3.3.0 creates `ksu`, marks that domain permissive, grants it all access, and permits other domains' Binder transactions into it. Thus “no SELinux changes” can mean **no additional broker changes**, not an untouched stock policy. Existing KernelSU policy plausibly supplies the missing access; this is not device verification. [Exact rules](https://github.com/tiann/KernelSU/blob/v3.3.0/kernel/selinux/rules.c#L86-L159); [Root Profile](https://github.com/tiann/KernelSU/blob/v3.3.0/website/docs/guide/app-profile.md).

## Dependency and manifest

[Tagged README](https://github.com/topjohnwu/libsu/blob/6.0.0/README.md#download) specifies JitPack and:

```kotlin
// settings.gradle.kts: dependencyResolutionManagement.repositories
maven { url = uri("https://jitpack.io") }
// app dependencies; service transitively includes core
implementation("com.github.topjohnwu.libsu:service:6.0.0")
// For our AIDL interface:
android { buildFeatures { aidl = true } }
```

Existing `google()`/`mavenCentral()` alone do not satisfy the documented repository setup. Explicit `core:6.0.0` is optional; `nio` is unnecessary. Java 11 already exceeds the library's Java-8 requirement. [Service dependency](https://github.com/topjohnwu/libsu/blob/6.0.0/service/build.gradle.kts); [sample AIDL build flag](https://github.com/topjohnwu/libsu/blob/6.0.0/example/build.gradle.kts).

**No `<service>` manifest entry is required:** `RootService` extends `ContextWrapper`, not Android `Service`; the server reflectively instantiates the class. The official [sample manifest](https://github.com/topjohnwu/libsu/blob/6.0.0/example/src/main/AndroidManifest.xml) declares only its activity. Do not use `Context.bindService`, `android:process`, or an exported platform service.

## Verified lifecycle APIs

Use `Intent(context, DisplayRootService::class.java)` and, on the main thread, `RootService.bind(intent, connection)` or `RootService.bind(intent, executor, connection)`. Receive the AIDL Binder through `ServiceConnection.onServiceConnected`; disconnect with `RootService.unbind(connection)`. `bind` returns **void**, not success; provide timeout/root-denial handling.

`RootService.bindOrTask(intent, executor, connection)` returns a nullable `Shell.Task`; execute a non-null task via `shell.execTask(task)` on a verified root shell, off the UI thread. `RootService.stop(intent)`/`stopOrTask(intent)` are intended for daemon shutdown and may launch another root process. Daemons require `CATEGORY_DAEMON_MODE` on bind and stop; avoid them here. Helper-side shutdown is `stopSelf()`.

Normal services die on last unbind/client Binder death; all normal services from one client process share a root process. With no remaining services the server calls `System.exit(0)`; unconnected normal startup has a ten-second idle exit. APK update/deletion also terminates it. No automatic rebind. Perform best-effort platform detach/release in `onDestroy()`; handle abrupt helper death separately. [Tagged API/Javadoc source](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java); [server implementation](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceServer.java); [published Javadoc](https://topjohnwu.github.io/libsu/com/topjohnwu/superuser/ipc/RootService.html).

## Authentication and native implementation

In helper `onCreate()`, require `Process.myUid() == 0` and cache `allowedUid = getApplicationInfo().uid`. The helper's package Context belongs to the launching Android user, while its Linux UID remains zero. libsu uses `createPackageContextAsUser` and falls back to ordinary package context: fail closed if this resolves the wrong user's UID. Never compare app-ID/modulo UID or trust a UID supplied through IPC. Context functionality is limited. [Bootstrap source](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServerMain.java); [Context applicationInfo](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ContextImpl.java#564).

For **every attach/detach transaction**, check `Binder.getCallingUid() == allowedUid` on the incoming Binder thread **before** clearing identity or dispatching work; otherwise throw `SecurityException`. Wrap platform calls with `long token = Binder.clearCallingIdentity()` and `finally { Binder.restoreCallingIdentity(token); }`. Cleanup uses private internal routines, not an unauthenticated RPC. [Binder APIs](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Binder.java#330).

Bootstrap uses a package-targeted broadcast, a receiver requiring `BROADCAST_PACKAGE_REMOVED`, and `RECEIVER_NOT_EXPORTED`. **There is no explicit sender-UID check/nonce in v6.0.0.** AOSP permits root/system access to unexported receivers; this excludes ordinary hostile apps, not a compromised privileged sender. The manager also lacks package-UID authorization for its management methods: never expose its Binder. Strict bootstrap proof of UID 0 is a blocker for unmodified v6.0.0 if required. [Manager](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java); [AOSP authorization](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityManager.java#5190).

Load JNI only in root with `if (Process.myUid() == 0) System.loadLibrary("viruaisor_native")`, following the [official sample](https://github.com/topjohnwu/libsu/blob/6.0.0/example/src/main/java/com/topjohnwu/libsuexample/AIDLService.java). README explicitly supports `extractNativeLibs=false`.

Platform sequence: `ServiceManager.waitForService("android.system.virtualizationservice")`, `IVirtualizationServiceInternal.Stub.asInterface(b).waitDisplayService()`, then `ICrosvmAndroidDisplayService.Stub.asInterface(displayBinder).setSurface(surface, false)`; detach uses **`removeSurface(false)`**, not a guessed null Surface. These hidden interfaces require matching AIDL/JNI implementation; compileSdk 37 does not publish them. [Official Terminal implementation](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/DisplayProvider.kt).

## API-37 blockers

libsu 6.0.0 builds with AGP 8.5/compile-target 34, not this project's AGP 9.4/Gradle 9.6/API 37. Consuming its published AAR differs from building upstream scripts; neither dependency resolution nor API-37 execution was tested. Hidden reflection/bootstrap, native ABI/loading, complete Surface Binder/FD transport and OEM AVF interfaces remain validation gates. Avoid daemon ServiceManager registration; install using Android Studio's **Always install with package manager** option. Do not fix failures by weakening policy. [Upstream build](https://github.com/topjohnwu/libsu/blob/6.0.0/build.gradle.kts); [hidden calls](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/HiddenAPIs.java).
