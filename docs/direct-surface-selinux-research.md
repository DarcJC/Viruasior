# Direct Surface vs. SELinux on Android 17

## Question and scope

Can `pro.darc.viruaisor`, installed normally as a debuggable APK, use **ADB alone** to get around the SELinux denial on

```c
AServiceManager_waitForService("android.system.virtualizationservice")
```

and obtain the internal crosvm display Binder used to attach an Android `Surface`?

This report treats “ADB-only” as an authorized ADB connection to an otherwise stock production `user` build: no bootloader unlock, root exploit, Magisk, modified boot/system/product image, platform signing key, or OEM build change. Exploiting an unknown vulnerability is out of scope.

Sources are the tested device's installed policy/APEX artifacts plus primary AOSP, Android, Shizuku, and Magisk project sources.

## Bottom line

**No. There is no stock-production-device, ADB-only bypass.** The failure is a ServiceManager SELinux authorization decision on the caller's SELinux SID, not an Android runtime permission, AppOp, hidden-API setting, or Java visibility check. The app already has both AVF permissions required by `waitDisplayService()`, but ServiceManager refuses to return the first Binder handle.

The same policy deliberately prevents the ADB `shell` domain from finding this service. Consequently, `service call`, `cmd`, an ADB-started `app_process`, and ADB-mode Shizuku cannot acquire and relay it. `run-as` enters `runas_app`, which is also denied. The platform Terminal app works because AOSP gives that exact privileged package a dedicated `vmlauncher_app` domain and display-specific Binder rules.

Direct Surface is technically feasible only with one of these non-stock arrangements:

1. a platform/OEM build that assigns Viruaisor a dedicated SELinux domain and mirrors the narrow Terminal display rules;
2. a userdebug/eng build temporarily made permissive for testing, or rebuilt with equivalent policy; or
3. a rooted device with live/persistent policy injection (for example Magisk), accepting substantial security and maintenance risk; or
4. a compatible userdebug GSI booted through DSU as a disposable lab environment, not as a workaround for users remaining on the stock OS.

For an ordinary installable APK, retain/use the streaming display backend. Do not invest further in `pm`, AppOps, hidden-API settings, `service call`, `run-as`, or ADB-mode Shizuku as solutions to this denial.

## Tested-device evidence

Device snapshot:

```text
model: M367FC
fingerprint: Xiaomi/yingtian/yingtian:17/CP2A.260605.016/OS4.0.10.0.XBMCNXM:user/release-keys
SDK/release: 37 / 17
ro.build.type: user
ro.debuggable: 0
SELinux: Enforcing
Verified Boot / bootloader: green / locked
```

The app is debuggable and both permissions are already granted:

```text
pkgFlags=[ DEBUGGABLE ... ]
android.permission.USE_CUSTOM_VIRTUAL_MACHINE: granted=true
android.permission.MANAGE_VIRTUAL_MACHINE: granted=true
```

The installed permission definitions agree with AOSP:

```text
USE_CUSTOM_VIRTUAL_MACHINE: signature|development
MANAGE_VIRTUAL_MACHINE: signature|development|preinstalled
```

AOSP declares those permissions in [`android/android.system.virtualmachine.res/AndroidManifest.xml`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/android.system.virtualmachine.res/AndroidManifest.xml#20). In particular, `USE_CUSTOM_VIRTUAL_MACHINE` is explicitly documented there as not for third-party applications, although its `development` flag permits the development grant used on this device.

### The decisive AVC

The installed policy maps the service name to `virtualization_service`:

```text
/system/etc/selinux/plat_service_contexts:191:
android.system.virtualizationservice u:object_r:virtualization_service:s0
```

The supplied capture used `u:r:untrusted_app_32:s0`; a current launch of the target-SDK-37 APK reports `u:r:untrusted_app:s0`. Both are members of the untrusted-app policy family and neither has the required rule. The current denial is:

```text
avc: denied { find } for uid=10286
name=android.system.virtualizationservice
scontext=u:r:untrusted_app:s0:c30,c257,c512,c768
tcontext=u:object_r:virtualization_service:s0
tclass=service_manager permissive=0
```

The AOSP equivalents are:

- service label: [`system/sepolicy/private/service_contexts`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/service_contexts#177)
- service type: [`system/sepolicy/public/service.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/service.te#58)

The JNI API does not evade this check. [`AServiceManager_waitForService`](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/libs/binder/ndk/service_manager.cpp#111) calls `IServiceManager::waitForService`. ServiceManager's [`Access::canFind`](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/cmds/servicemanager/Access.cpp#128) resolves the service's SELinux label and calls `selinux_check_access(..., "find", "service_manager")` ([same file, lines 140–170](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/cmds/servicemanager/Access.cpp#140)). The caller's granted package permissions and AppOps are not inputs to that decision.

### Android permissions are a second, later gate

If ServiceManager returned the Binder, `waitDisplayService()` would then enforce both AVF permissions. The implementation calls `check_manage_access()` and `check_use_custom_virtual_machine()` before returning the display Binder: [`android/virtualizationservice/src/aidl.rs`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/virtualizationservice/src/aidl.rs#226). Those helpers call the permission controller with the calling PID/UID ([lines 951–982](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/virtualizationservice/src/aidl.rs#951)). Viruaisor already passes this second gate. It cannot reach it because the SELinux `find` gate comes first.

## Why the Terminal app works

This is not a general privilege enjoyed by any app with the two permissions.

AOSP assigns the exact privileged package name a dedicated domain:

```text
user=_app isPrivApp=true name=com.android.virtualization.terminal \
    domain=vmlauncher_app type=privapp_data_file levelFrom=all
```

Source: [`system/sepolicy/private/seapp_contexts`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts#214). The installed device has the same mapping and its running process is `u:r:vmlauncher_app:s0:...`.

Under the AVF custom-paravirtualized-device feature, AOSP then grants exactly the display path the Terminal needs:

```selinux
allow vmlauncher_app virtualization_service:service_manager find;
allow vmlauncher_app virtualizationservice:binder call;
allow vmlauncher_app crosvm:binder { call transfer };
allow virtualizationservice vmlauncher_app:binder transfer;
```

Sources:

- first three rules: [`system/sepolicy/private/vmlauncher_app.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/vmlauncher_app.te#24)
- return transfer: [`system/sepolicy/private/virtualizationservice.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/virtualizationservice.te#58)

The installed policy also grants the Terminal domain crosvm FD and shared-buffer access (`fd use` and `{ read write map }` on crosvm tmpfs/memfd types). Removing the first `{ find }` denial is therefore only the first policy step; Surface transport must pass all subsequent Binder, FD, and shared-memory checks too.

The Terminal does exactly what Viruaisor's adapter attempts: `ServiceManager.waitForService("android.system.virtualizationservice")`, `IVirtualizationServiceInternal.waitDisplayService()`, and `ICrosvmAndroidDisplayService.setSurface(...)`. See [`TerminalApp/DisplayProvider.kt`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/DisplayProvider.kt#42).

It does **not** export a Binder service that third-party apps can use. Its `InstallerService` and `VmLauncherService` are `android:exported="false"`. The internal `DisplayActivity` is also unexported; launching it with `adb shell am start` was rejected by ActivityTaskManager. Only the intended launcher/VM-terminal entry activities are exported, and they do not accept an app-owned `Surface` as a broker API. See [`TerminalApp/AndroidManifest.xml`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/AndroidManifest.xml#41).

## Option-by-option assessment

### `pm grant`

**Stock ADB-only: no.** It has already achieved everything it can: both required AVF permissions show `granted=true`, yet ServiceManager emits the AVC. `pm grant` changes PackageManager permission state, not the caller's SELinux SID or the loaded SELinux allow rules.

Revoking either permission would create an additional failure inside `waitDisplayService()`; granting them cannot remove the preceding `{ find }` denial.

### AppOps

**Stock ADB-only: no.** There is no virtualization AppOp in AOSP's [`AppOpsManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/AppOpsManager.java), and ServiceManager's authorization path uses SELinux directly. Setting unrelated operations to `allow` cannot synthesize `virtualization_service:service_manager find`.

### `settings` and hidden-API exemptions

**Stock ADB-only: no.** The device setting is already exactly:

```text
hidden_api_blacklist_exemptions=Landroid/system/virtualmachine/VirtualMachine
```

Non-SDK restrictions govern whether ART permits Java code to link/reflect against hidden interfaces; see the official [Restrictions on non-SDK interfaces](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces). They do not edit SELinux policy. Moreover, the failing call is the public NDK Binder symbol, not a Java hidden-API linkage. Changing this setting cannot affect ServiceManager's `selinux_check_access` result.

### `service call` and `cmd`

**Stock ADB-only: no.** On the test device:

```text
$ adb shell service list | grep virtualizationservice
88 android.system.virtualizationservice: []

$ adb shell service check android.system.virtualizationservice
Service android.system.virtualizationservice: not found

$ adb shell cmd -l | grep virtualization
android.system.virtualizationservice_internal.IVmnic
```

`service list` is not evidence that shell can obtain the Binder. ServiceManager checks `list` and `find` as separate permissions; see [`Access::canList` and `Access::canFind`](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/cmds/servicemanager/Access.cpp#128). `service call` must first find the named service, so it fails at the same gate. The global virtualization service does not expose a `cmd` shell frontend for the display operation.

AOSP makes this deliberate with an explicit rule:

```selinux
neverallow shell {
  ...
  virtualization_service
}:service_manager find;
```

Source: [`system/sepolicy/private/shell.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/shell.te#508). Therefore guessing transaction 18, using Java `ServiceManager`, or using NDK Binder from a shell program does not help.

### ADB shell Binder broker or Shizuku

**ADB-mode, stock: no. Root-mode with policy changes: potentially, but no longer ADB-only.**

Shizuku's own [introduction](https://shizuku.rikka.app/introduction/) says its service is a Java `app_process` started using ADB or root and sends its Binder to the client app. In ADB mode that process has ADB/shell privilege, not the Terminal's `vmlauncher_app` SELinux domain. The installed device records the same `{ find }` denial with `scontext=u:r:shell:s0`; AOSP additionally has the explicit shell `neverallow` above. A broker cannot relay a Binder it cannot obtain.

Even after a rooted policy patch lets the broker obtain the global service and display Binder, Surface presentation needs additional Binder transfer/call permission between the broker/client and the crosvm display service. This is a custom privileged bridge design, not a stock Shizuku capability and not a supported AVF API.

### `run-as` and shell `app_process`

**Stock ADB-only: no.** Because Viruaisor is debuggable, `run-as` works, but the observed domain is:

```text
uid=10286(u0_a286) ... context=u:r:runas_app:s0:c30,c257,c512,c768
```

A lookup from that process produces the same `{ find }` denial with `scontext=u:r:runas_app:s0`. `run-as` changes credentials/domain for debugging; it does not turn the package into `vmlauncher_app`, `platform_app`, or `priv_app`.

An attempted `runcon u:r:vmlauncher_app:s0 ...` from shell failed with `Operation not permitted`; stock policy provides no such transition. A shell-started `app_process` likewise remains governed by shell policy. The only relevant allowed transition is the AVF framework executing the labeled `virtualizationmanager_exec`; the `virtualizationservice_use(domain)` macro creates that transition and UDS/FD access, but does not grant the parent app direct service lookup. See [`system/sepolicy/public/te_macros`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/public/te_macros#183).

### `adb root` and `setenforce 0`

**Tested production user build: no. Userdebug/eng: useful for testing.**

Observed:

```text
$ adb root
adbd cannot run as root in production builds

$ adb shell setenforce 0
setenforce: Couldn't set enforcing status to '0': Permission denied
```

AOSP adbd permits `adb root` only when `ro.debuggable` is true; that property is set on eng/userdebug builds, not production user builds. See [`packages/modules/adb/daemon/main.cpp`](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/main.cpp#66).

On a userdebug/eng build, `adb root` **alone** does not logically grant an SELinux allow rule: UID 0 and SELinux authorization are distinct. `adb root` followed by `setenforce 0` can establish whether SELinux is the only blocker, provided that build/kernel allows permissive mode. This is a development test, not a production solution. Keep permissive mode off outside an isolated test device.

### `adb remount`, system/product policy edits, and custom images

**Tested stock/locked production device: no. Custom userdebug/platform build: yes.**

The device reports a green, locked verified boot state; `/system`, `/product`, and `/vendor` policy is loaded from verified read-only images, and the visible product/vendor filesystems are EROFS/read-only. `adb root` is unavailable.

AOSP's OverlayFS documentation explicitly frames writable remount as an eng/userdebug workflow and starts with:

```text
adb root
adb disable-verity
adb reboot
adb root
adb remount
```

See [`system/core/fs_mgr/README.overlayfs.md`](https://android.googlesource.com/platform/system/core/+/refs/heads/main/fs_mgr/README.overlayfs.md#7).

Even where files can be overlaid, changing a text policy file after policy has been loaded does not patch the live kernel policy. A persistent correct solution is a rebuilt/signed image (and reboot), or a root policy loader. Android's official [Customize SELinux](https://source.android.com/docs/security/features/selinux/customize) documentation describes adding device policy through the build's sepolicy directories and enforcing `neverallow` rules.

For a platform integration, create a dedicated Viruaisor app domain and narrowly mirror the four display rules shown above. Do not grant these capabilities to all `untrusted_app` processes. A shell-based design would additionally require intentionally changing AOSP's explicit shell `neverallow`, which is a strong reason to prefer a dedicated app domain.

### DSU and a userdebug GSI

**Possible as a separate lab OS, not a stock-OS bypass.** The tested device exposes `gsi_tool` and supports Dynamic System Updates. A compatible userdebug GSI could therefore provide an environment where `adb root`, permissive-mode diagnosis, or rebuilt policy is possible without rewriting the currently booted system partition. It still requires installing and rebooting into another system image; image acceptance and compatibility with this OEM's API-37 AVF stack are not established. It is not a deployment path for users who remain on the stock build.

### Privileged installation and platform signing

**ADB-only: unavailable. Platform build: signing/install location alone is still insufficient; signing plus policy integration is feasible.**

A normal `adb install` places the APK in `/data/app`; it cannot make it a verified `/system/priv-app` component or assign the custom Terminal domain. AOSP's generic mappings assign `seinfo=platform` to `platform_app` and generic privileged packages to `priv_app`, while only the exact Terminal package maps to `vmlauncher_app`: [`private/seapp_contexts`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts#189).

Generic `platform_app` and `priv_app` policy invokes `virtualizationservice_use(...)`, which permits spawning the separate virtualization-manager helper, not direct lookup of the global internal service:

- [`private/platform_app.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/platform_app.te#144)
- [`private/priv_app.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/priv_app.te#211)

Therefore platform signing or privileged placement by itself does not reproduce Terminal's Direct Surface access. A custom platform build can make it work only by also supplying the package-to-domain mapping and display-specific Binder policy (and, normally, appropriate privileged permission allowlisting/signing).

### Root/Magisk policy injection

**Rooted device: technically possible, unsupported and high risk.**

Magisk documents persistent module `sepolicy.rule` files in its [Developer Guides](https://topjohnwu.github.io/Magisk/guides.html#sepolicyrule) and live policy statements through [`magiskpolicy`](https://topjohnwu.github.io/Magisk/tools.html#magiskpolicy). A rooted proof of concept can mirror the Terminal rules into the *actual* app and crosvm domains reported by `ps -AZ`.

Conceptually, the necessary permissions are:

```selinux
allow <viruaisor_domain> virtualization_service:service_manager find;
allow <viruaisor_domain> virtualizationservice:binder call;
allow virtualizationservice <viruaisor_domain>:binder transfer;
allow <viruaisor_domain> <actual_crosvm_domain>:binder { call transfer };
```

These are policy statements, not commands to run verbatim. Domain/type names vary with the OEM policy and AVF feature layout; the tested image, for example, uses crosvm attributes and derived domains that differ from current AOSP source. Validate the live domains while a VM is running and inspect fresh AVCs.

Injecting the rule into `untrusted_app`, `untrusted_app_32`, or another shared app type grants the capability to every process in that type, not just Viruaisor. That is materially broader than AOSP's named `vmlauncher_app` domain. A dedicated platform domain is safer. A root daemon/Binder broker can avoid broad direct app access, but it must implement and authorize Binder/FD/Surface transfer correctly and remains a custom privileged component.

### Supported AVF API or existing proxy for the display Binder

**No supported API on this build.** There is a supported architectural proxy for VM lifecycle, but it intentionally does not proxy the display Binder yet.

The high-level framework's private `VirtualizationService` spawns `virtmgr` and connects to an `IVirtualizationService` over UDS Binder RPC: [`libs/framework-virtualization/.../VirtualizationService.java`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualizationService.java#39). `virtmgr` exports a separate `BnVirtualizationService` as that RPC server's root object: [`android/virtmgr/src/main.rs`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/virtmgr/src/main.rs#125), implemented as the public/lifecycle `IVirtualizationService` in [`android/virtmgr/src/aidl.rs`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/virtmgr/src/aidl.rs#217).

That RPC object is not `IVirtualizationServiceInternal` and does not export `waitDisplayService()`. The internal AIDL still contains:

```aidl
// TODO(b/330257000): Remove these functions when a display service is running with binder RPC.
void setDisplayService(IBinder ibinder);
void clearDisplayService();
IBinder waitDisplayService();
```

Source: [`IVirtualizationServiceInternal.aidl`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/virtualizationservice/aidl/android/system/virtualizationservice_internal/IVirtualizationServiceInternal.aidl#120). That TODO is direct first-party evidence that the display service is not yet available through Binder RPC.

The module's published API signature contains no `Surface`, display-Binder, or `waitDisplayService` API: [`libs/framework-virtualization/api/current.txt`](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/api/current.txt). The API-37 device JAR does contain blocked hidden VM display-configuration methods such as `addDisplay/getDisplays/removeDisplay`, but no method accepting `android.view.Surface`; those methods configure virtual displays and do not return the crosvm presentation Binder.

Trying to reflect the framework's private lifecycle Binder does not change this: the RPC root is the separate 17-method `IVirtualizationService`, while `waitDisplayService` is transaction 18 on the differently-described internal interface. Terminal therefore still performs the direct ServiceManager lookup.

## Verification matrix

| Mechanism | Stock production `user`, ADB only | Rooted / userdebug / platform build | Decisive check or source |
|---|---:|---:|---|
| Existing `pm grant` permissions | **No bypass** | Still required unless caller UID 0 | Both granted while AVC persists; service checks both permissions |
| AppOps | **No** | Not relevant | No AVF AppOp; ServiceManager calls SELinux directly |
| Hidden-API `settings` exemption | **No** | Not an SELinux solution | NDK lookup still reaches ServiceManager `canFind` |
| `service call` / Java or NDK shell lookup | **No** | Works only after permissive/policy change | `service check` denied; shell `neverallow` |
| `cmd` | **No** | No display command is exported | `cmd -l` has no global display frontend |
| ADB-mode Shizuku / shell broker | **No** | Root-mode custom broker potentially | ADB service runs as shell; shell cannot `find` |
| `run-as` | **No** | Root changes the premise | Enters `runas_app`; same AVC |
| shell `app_process` | **No** | Root/policy may change domain rights | Remains shell; explicit shell `neverallow` |
| `adb root` alone | **Unavailable here; insufficient by itself** | Available on eng/userdebug, but SELinux still applies | `ro.debuggable=0`; adbd source |
| `adb root` + `setenforce 0` | **No** | **Yes for isolated testing** where permissive is supported | Production commands fail; userdebug/eng development path |
| `adb remount` + policy files | **No** | **Yes only as a custom image/boot policy workflow** | Locked green user build; AOSP remount docs require root/userdebug |
| Terminal's internal display activity/service | **No** | A modified platform broker could work | Display activity/services are unexported; ADB launch is rejected |
| DSU userdebug GSI | **No change to the running stock OS** | **Potentially useful as a separate lab OS** | Device exposes DSU, but image/AVF compatibility remains unverified |
| Privileged/platform-signed APK only | **No** | **Still no** without matching seapp/TE policy | Generic `platform_app`/`priv_app` gets lifecycle helper, not display rules |
| Dedicated platform app domain + narrow policy | **Not ADB-only** | **Yes; recommended Direct Surface integration** | Mirror AOSP `vmlauncher_app` display rules |
| Magisk/live sepolicy injection | **Not applicable** | **Likely yes; unsupported/high risk** | Mirror all four Binder/service rules using actual OEM domains |
| Existing supported AVF display proxy/API | **No** | Same API limitation | Internal AIDL TODO says display is not yet Binder-RPC-proxied |
| Streaming display backend | **Yes** | Yes | Avoids restricted host display Binder entirely |

## Reproduction commands

For the repeatable app-level probe, run:

```bash
scripts/check-direct-surface-selinux.sh
```

On the stock device it exits `1` with `RED: Direct Surface lookup is blocked by SELinux service_manager find`; a policy-integrated build must exit `0` only after logging `Direct AVF surface attached`.

For lower-level inspection, use the project's known ADB binary on Windows:

```powershell
$adb = 'C:\Tools\Android\platform-tools\adb.exe'

& $adb shell 'getprop ro.build.type; getprop ro.debuggable; getenforce'
& $adb shell 'ps -AZ | grep pro.darc.viruaisor'
& $adb shell 'grep -n android.system.virtualizationservice /system/etc/selinux/plat_service_contexts'
& $adb shell 'service check android.system.virtualizationservice'
& $adb shell 'run-as pro.darc.viruaisor id'
& $adb shell 'run-as pro.darc.viruaisor /system/bin/service check android.system.virtualizationservice'
& $adb shell 'logcat -d -b all | grep "virtualization_service.*service_manager"'
& $adb root
& $adb shell 'setenforce 0'
```

Expected on the tested stock build: the app is `untrusted_app` (or the supplied build's `untrusted_app_32`), `run-as` is `runas_app`, both lookups return “not found” and log `{ find }` AVCs, `adb root` reports a production build, and `setenforce 0` is denied.

For policy-integrated or rooted experiments, success requires all of the following, not merely disappearance of the first AVC:

1. no `{ find }` denial for `virtualization_service`;
2. no app-to-`virtualizationservice` Binder `call` denial;
3. no `virtualizationservice`-to-app Binder `transfer` denial when returning the display Binder;
4. no app-to-crosvm Binder `call`/`transfer` denial when passing the `Surface`;
5. both AVF permissions accepted by `waitDisplayService()`; and
6. `setSurface()` succeeds while the VM/display service is running.

## Related guest-side finding

The Direct Surface experiments also exposed a separate limitation in the current Ferrochrome guest profile: its kernel initializes built-in `virtio_gpu`/DRM roughly 0.11 seconds after boot. `modprobe.blacklist=drm,virtio_gpu` cannot defer a built-in driver, and a later `modprobe virtio_gpu` fails because no loadable module exists. Graphical userspace still remains stopped until explicitly requested, but the stronger invariant “boot never touches DRM/KMS” is not met by this kernel. Meeting it requires a kernel with modular DRM/virtio-gpu or a host design that hot-plugs the GPU only after `START_DESKTOP`.

## Recommendation

For the shipping, normally installed Viruaisor APK, treat Direct Surface as **unavailable on stock API 37** and select the streaming backend. Keep Direct Surface behind an explicit capability/profile intended for platform-integrated or rooted test devices.

If Direct Surface is a product requirement, pursue an OEM/platform build that gives `pro.darc.viruaisor` its own SELinux app domain and narrowly mirrors AOSP's `vmlauncher_app` display rules. Use userdebug permissive mode only to confirm the rule set, then return to enforcing. Do not weaken `shell` or all `untrusted_app` domains in a production policy.
