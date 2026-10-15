# AVF: Viruaisor-scoped temporary overlay research

Research date: 2026-10-15. This is a source review and a proposed, reversible launch
design. No namespace, mount, process injection, system file, AVF APEX, permission,
SELinux policy or root-module change was performed for this note. Deployment is outside
the current request.

## Result and correction to the service model

The normal AVF framework **does spawn `virtmgr` from the caller application process**.
It is incorrect to reject a private namespace design on the assumption that a global
Binder service necessarily launches every `virtmgr` and crosvm process. A dedicated
Viruaisor app process can, in principle, pass its filesystem view to this actual child
launch chain. This does not prove the replacement executable can pass SELinux and
loader checks.

In the pinned AVF revision `22f1c9ee92b146e10c8f5e71338618f77ab1631e`:

1. `VirtualizationService.getInstance()` caches a weak reference to an app-side wrapper.
   Its constructor calls `nativeSpawn()` and connects to the returned Unix socket FD.
2. JNI `nativeSpawn()` calls `get_virtualization_service()` from libvmclient.
3. `VirtualizationService::new()` calls private `new_with_path(VIRTMGR_PATH)`, creates a
   socketpair and readiness pipe, and uses `SharedChild::spawn` on
   `/apex/com.android.virt/bin/virtmgr` in the caller process.
4. `virtmgr` explicitly derives the calling PID from its parent and the calling UID from
   its own UID. It runs the public lifecycle `IVirtualizationService` over Binder RPC.
5. That `virtmgr` calls `Command::new(CROSVM_PATH)` and `SharedChild::spawn` to launch
   `/apex/com.android.virt/bin/crosvm` for the VM.

Sources: [Java wrapper][avf-java], [JNI spawn][avf-jni], [libvmclient][avf-client],
[virtmgr entrypoint][avf-main], [crosvm launch][avf-crosvm]. No namespace switch appears
in these inspected launch functions. The pinned AVF builder explicitly passes
`--disable-sandbox`; this describes the existing launch path, not a recommendation to
weaken a different sandbox.

A mount namespace belongs to a task and is inherited across ordinary child creation;
`execve` does not itself select the namespace of a Binder caller. A transaction to an
**already running** global service is executed by that service's threads in their own
namespace. A namespace FD would require an explicit receiving/`setns` implementation.
No such parameter exists in the inspected AVF configuration. Therefore an overlay in a
separate root shell or helper namespace will not automatically affect an existing app,
existing virtmgr, or global service. Sources: [mount namespaces][mount-ns],
[namespace APIs][namespaces], [AVF raw config][avf-raw].

## Official configuration boundary

The inspected public framework API, custom-image builder, raw VM AIDL and GPU AIDL
provide no mount-namespace FD, arbitrary virtmgr/crosvm executable path, renderer-server
FD/path, or external vhost-user GPU endpoint. Kernel/initrd/disk path setters choose
guest resources, not the host VMM. `new_with_path` is a private Rust implementation
detail, and `CROSVM_PATH` is a hard-coded host path, not an app override.
Sources: [published API][avf-api], [custom image builder][avf-custom],
[raw config][avf-raw], [GPU config][avf-gpu], [client launch][avf-client],
[crosvm command construction][avf-crosvm].

Selecting `backend=virglrenderer,context-types=venus` cannot enable an omitted Rust
feature. The prior Release APK probe already recorded the installed crosvm rejection.
Any custom crosvm candidate must actually include a compatible Venus/rutabaga integration,
the Android allocation/import changes and renderer lifecycle fixes, not merely replace
a library filename. Host compute success does not prove guest blob mapping or cache
coherence. See [the host results](venus-host-validation.md) and
[the renderer requirements](venus-host-build-research.md).

## Namespace creation and the useful candidate

Android 17 Zygote's `SpecializeCommon` calls `ensureInAppMountNamespace()` before
application specialization completes; that helper uses `unshare(CLONE_NEWNS)`.
Zygote's initial root filesystem view is recursively marked `MS_SLAVE`, so upstream
mount changes can flow inward without app-side mount events flowing back to the
original root. This is primary-source support for using an **existing app namespace**,
not proof of an OEM process's actual namespace or propagation flags.
Source: [Android 17 Zygote implementation][zygote]. The checked tag
`android-17.0.0_r1` resolves to frameworks/base commit
`94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`; the corresponding inspected sepolicy tag
resolves to `e066568e98d86db31a9346d30977f3632fa7073c`. These are upstream snapshots,
not claims about the OEM binary's exact source revision.

The coordinator's read-only inspection in this session recorded:

| Object | Recorded state |
| --- | --- |
| Main app PID 28357, UID 10278 | `mnt:[4026536932]`, `CapEff=0`, `Seccomp=2` |
| Zygote PID 864 | `mnt:[4026536794]` |
| Init PID 1 | `mnt:[4026535574]` |
| Stable `/apex/com.android.virt` mounts | Read-only; no `shared:`/`master:` fields |
| Stock crosvm SELinux type | `crosvm_exec` |
| Stock virtmgr SELinux type | `virtualizationmanager_exec` |

Those PIDs are historical evidence, not safe deployment identifiers. The private
dedicated process must be checked again on every launch. A different namespace inode
from init and Zygote alone does not prove that no unrelated process shares it.

The smallest candidate preserving ordinary app lifecycle is:

```text
Viruaisor UI
    -> ordinary same-UID :venus_vm process, waiting before any AVF access
       -> existing independent app mount namespace
          [one-shot authenticated root helper enters exactly this namespace]
          [verify isolation; make propagation private; cover allowlisted files read-only]
       -> ordinary app calls AVF nativeSpawn
          -> unchanged stock virtmgr
             -> candidate crosvm seen only in this namespace and descendants
```

This is an engineering design, not an officially supported AVF option. It changes
neither the global virtualizationservice nor the installed AVF APEX. The root helper
would perform only namespace/mount operations for this package; VM creation, lifecycle,
images, vsock, callbacks and input remain in the ordinary app process.

The helper must enter the **already isolated target namespace**. If it subsequently
calls `unshare(CLONE_NEWNS)` and mounts there, it creates a helper-only copy and the
waiting app will not see the overlay. Conversely, entering a namespace shared with
another package would violate the requested scope. A new process created directly by a
root launcher is a different design: its UID, SELinux domain, app parent identity and
permission checks do not become correct merely by calling `setuid`.
Sources: [setns semantics][setns], [unshare semantics][unshare],
[caller identity in virtmgr][avf-main].

An alternative source modification could put `unshare` in a controlled pre-exec child
before spawning virtmgr. It still requires `CAP_SYS_ADMIN` and permitted mount operations.
Stock app domains do not have those capabilities, and the virtmgr policy explicitly
prohibits granting itself capabilities. Adding `unshare` to an unprivileged app/virtmgr
binary is therefore not a complete solution. The existing Zygote-created namespace
avoids this particular self-`unshare` requirement, while retaining the helper's exact
capability and SELinux checks. Sources: [unshare][unshare], [app policy][sepolicy-app],
[virtmgr policy][sepolicy-virtmgr].

## Required isolation and rollback invariants

Before any future mount operation, a reviewed implementation must establish all of these:

- The target is the expected same-UID Viruaisor `:venus_vm` process, authenticated using
  the package/certificate and a private nonce/FD channel. Use stable process handles and
  validate PID start time; a numeric PID or process-name substring is insufficient.
- No AVF wrapper, virtmgr or VM has been created in that process yet. The cached Java
  wrapper must never be reused from a different namespace or an earlier attempt.
- The namespace is distinct from init, Zygote, the UI, other packages and unrelated
  VMs. Examine all observable namespace members and each relevant `mountinfo` entry.
- Before overlay creation, make the isolated namespace's propagation recursively
  private and verify the result. Do not change propagation in init/Zygote or a shared
  ancestor namespace. Namespace cloning alone does not remove shared-mount peer groups.
- Limit mounts to exact reviewed executable/dependency targets with fixed source
  digests, read-only final mount flags and no caller-supplied shell command or arbitrary
  destination. Keep original verified APEX files untouched.
- Release the app wait only after every mount, label, dependency and identity check
  passes. On any failure, undo only the mounts recorded for this namespace and return
  an explicit failure before spawning a VM.

On normal stop, stop the target VM through AVF, wait for its crosvm/virtmgr descendants,
remove only this namespace's recorded mounts, and close namespace/process FDs. On a
crash, close the private RPC/control channels and reap only authenticated descendants;
then release the mount namespace. A namespace can outlive its original app while child
processes or open namespace FDs retain it. Namespace destruction removes its mounts;
an ordinary clean app restart should obtain the original filesystem view. No boot-time
module, service restart or global unmount is part of rollback.
Sources for propagation/lifetime: [mount namespaces][mount-ns],
[namespace references][namespaces]; AVF client death/process-group handling:
[libvmclient][avf-client].

## SELinux, labels, APEX and dynamic linking remain separate gates

Mount visibility is not execution authority. Android 17's private
`virtualizationservice_use(domain)` macro transitions the executing app on
`virtualizationmanager_exec` into a client-specific virtmgr domain, then on
`crosvm_exec` into its crosvm domain. This matches the project's observed
`untrusted_app_all_virtmgr_crosvm` type. A bind mount exposes the source inode; spelling
its destination `/apex/com.android.virt/bin/crosvm` does not automatically give an
APK/data-file source the `crosvm_exec` label or its entrypoint permissions.
Sources: [Android 17 transition macro][sepolicy-macro],
[domain transition/entrypoint macro][sepolicy-public],
[SELinux labels][selinux-concepts], [bind mounts][mount].

Therefore the candidate must prove, under the **existing** policy, all of the following:
the helper can enter/mount in the target namespace; the source object's label and MCS
range are valid; stock virtmgr can open/execute it; the correct crosvm domain transition
occurs; that domain retains the expected KVM/GPU/FD access. A custom app-native binary,
UID 0, a file descriptor or a matching path does not establish these checks. No policy
injection, chmod/chown of system files, global permissive mode or privilege grant is
included. Private-artifact labeling/mount contexts are also unverified privileged
operations, not an implied fix; do not silently relabel files to force a pass.
Sources: [app restrictions][sepolicy-app], [virtmgr restrictions][sepolicy-virtmgr],
[crosvm access and data-file restrictions][sepolicy-crosvm].

The original crosvm SHA-256 recorded by the coordinator is
`e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d`; stock virtmgr is
`081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c`.
The stable APEX path normally points to the verified active APEX version. A private
covering mount would leave that installed payload unchanged, but the candidate bytes
are **not** authenticated by the OEM's original APEX signature. Keep the package-signed
candidate's digest/provenance separate from the original APEX identity and verify both
views. Do not describe the result as an official APEX update.
Source: [APEX activation, signing and stable paths][apex].

Retain the stock virtmgr and match the candidate crosvm's platform/control ABI, Android
display integration, pKVM support and dynamic dependencies. The AVF source itself says
virtmgr/crosvm platform versions are updated together. APEX/linker namespaces restrict
library resolution independently of mount namespaces; changing mounts does not remove
those restrictions. Domain transitions also request secure executable loading through
the SELinux macro, so `LD_PRELOAD`/environment tricks must not be assumed available.
Sources: [AVF platform version][avf-crosvm], [linker namespaces][linker],
[secure domain-transition macro][sepolicy-public].

Even after backend startup, guest `HOST_VISIBLE|HOST_CACHED` memory requires a separate
mapping/coherence test. The successful host type-15 allocation used explicit
`DMA_BUF_IOCTL_SYNC`; this does not establish the guest's cache maintenance, KVM mapping,
virtio blob map flags or fence correctness. Require full input-dependent guest GPU
readback before desktop/presentation tests. See [host validation](venus-host-validation.md).

## Excluded approaches and unproven assumptions

The following conflict with the current constraints: replacing files in `/system` or
the AVF APEX; installing/updating an APEX; global bind mounts; persistent root overlays;
changing SELinux/permissions/modules; injecting into global virtualizationservice,
Zygote or unrelated virtmgr processes; or using an arbitrary root VMM as the app's VM
owner. None is needed merely to prepare this plan.

Still unproven: the OEM's dedicated-process inheritance chain, the helper's exact
`setns`/mount permissions, a policy-compliant executable label for the private candidate,
loader/ABI compatibility, successful Venus backend initialization, guest export/map/cache
coherence, guest GPU readback, frames and clean shutdown. The launch-chain source makes
the scoped overlay worth evaluating; it does not make these remaining gates pass.

[avf-java]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/framework-virtualization/src/android/system/virtualmachine/VirtualizationService.java
[avf-jni]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/libvirtualization_jni/android_system_virtualmachine_VirtualizationService.cpp
[avf-client]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/libvmclient/src/lib.rs
[avf-main]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/main.rs
[avf-crosvm]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtmgr/src/crosvm.rs
[avf-api]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/framework-virtualization/api/current.txt
[avf-custom]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineCustomImageConfig.java
[avf-raw]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtualizationservice/aidl/android/system/virtualizationservice/VirtualMachineRawConfig.aidl
[avf-gpu]: https://android.googlesource.com/platform/packages/modules/Virtualization/+/22f1c9ee92b146e10c8f5e71338618f77ab1631e/android/virtualizationservice/aidl/android/system/virtualizationservice/GpuConfig.aidl
[zygote]: https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/jni/com_android_internal_os_Zygote.cpp
[sepolicy-macro]: https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/private/te_macros
[sepolicy-public]: https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/public/te_macros
[sepolicy-app]: https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/private/app.te
[sepolicy-virtmgr]: https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/private/virtualizationmanager.te
[sepolicy-crosvm]: https://android.googlesource.com/platform/system/sepolicy/+/e066568e98d86db31a9346d30977f3632fa7073c/private/crosvm.te
[mount-ns]: https://man7.org/linux/man-pages/man7/mount_namespaces.7.html
[namespaces]: https://man7.org/linux/man-pages/man7/namespaces.7.html
[unshare]: https://man7.org/linux/man-pages/man2/unshare.2.html
[setns]: https://man7.org/linux/man-pages/man2/setns.2.html
[mount]: https://man7.org/linux/man-pages/man2/mount.2.html
[selinux-concepts]: https://source.android.com/docs/security/features/selinux/concepts
[apex]: https://source.android.com/docs/core/ota/apex
[linker]: https://source.android.com/docs/core/architecture/partitions/linker-namespace
