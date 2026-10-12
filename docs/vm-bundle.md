# Viruaisor VM bundle v1

The MVP imports one ZIP file into the app-private `filesDir/vms/default/` directory. `vm.json`
must be at the ZIP root. Paths in the manifest are relative to that root.

```json
{
  "version": 1,
  "kernel": "Image",
  "initrd": "initramfs.img",
  "rootfs": "root_part",
  "rootfsPartition": {
    "label": "ROOT",
    "guid": "EBBBECCD-10A6-4969-A904-38F513E6D5CA"
  },
  "extraDisks": [
    { "image": "cidata.iso", "writable": false }
  ],
  "osName": "debian",
  "memoryMiB": 4096,
  "cpuTopology": "match_host",
  "vsockPort": 4050,
  "network": true,
  "autoMemoryBalloon": true,
  "debuggable": true,
  "consoleInputDevice": "ttyS0",
  "gpuProfile": "2d",
  "display": {
    "width": 1280,
    "height": 720,
    "horizontalDpi": 240,
    "verticalDpi": 240,
    "refreshRate": 60
  },
  "kernelArgs": [
    "root=/dev/vda1",
    "ds=nocloud",
    "console=ttyS0"
  ]
}
```

`initrd` and `rootfsPartition` are optional. Without `rootfsPartition`, `rootfs` is attached as a
whole writable disk. `cpuTopology` may be `match_host` or `one_cpu`. `gpuProfile` may be `2d`
(the default) or `gfxstream`; the latter is an optional profile rather than an application
requirement. The optional `display` object defaults to 1280x720, 240 dpi, and 60 Hz.

The importer replaces any supplied `systemd.unit` argument with
`systemd.unit=multi-user.target` and adds `systemd.mask=display-manager.service` and
`systemd.mask=graphical.target`. Creating a virtual display/GPU does not start Weston. Guest images
must not use shell profiles, udev rules, or boot units to start Weston. Viruaisor adds
`modprobe.blacklist=drm,virtio_gpu` so headless systemd startup does not probe DRM/KMS; the explicit
desktop unit loads `virtio_gpu` immediately before Weston. The desktop starts only after the
Android Surface is ready and the app sends `START_DESKTOP` over vsock.

The root filesystem must contain `avf-agent.service`, enabled for `multi-user.target`. Its daemon
listens on the configured host-vsock port and uses one UTF-8 line per connection:

- On a readiness probe, immediately write `READY\n`.
- On receiving `SHUTDOWN\n`, invoke `systemctl poweroff` (or the equivalent orderly shutdown).

The Android app accepts either AVF's standard `IGuestAgent` registration or the line protocol
above. Stop first calls the standard agent's `shutdownAsync()` when registered, otherwise sends
`SHUTDOWN` over the configured raw vsock port. Both paths use AVF's hard stop only if graceful
shutdown cannot be requested or the guest has not exited after 10 seconds.

## Display integration

The Android 17 Direct Surface path remains isolated behind the Display adapter because it uses the
internal AVF/crosvm AIDL protocol. The internal display service is also SELinux-gated: on the test
device, a normal user-installed APK cannot look it up even after both AVF permissions and the exact
`VirtualMachine` hidden-API exemption are granted. Direct Surface therefore requires platform
SELinux integration. Ordinary user-installed builds need a decoder/streaming Display backend; this
does not change VM lifecycle or vsock control orchestration.

## Installation requirement

The AVF classes used for custom Linux images are Android system APIs, not part of the public
`android.jar` in API 37. The APK declares `MANAGE_VIRTUAL_MACHINE` and
`USE_CUSTOM_VIRTUAL_MACHINE`, but the platform build/install policy must grant them and allow this
package to use the AVF system API. A regular user-installed APK cannot grant itself that access.
The SAF importer requires no broad external-storage permission and the app does not request
`MANAGE_EXTERNAL_STORAGE`. `MANAGE_VIRTUAL_MACHINE` is still required independently by the API 37
`VirtualMachineManager` system interface; SAF cannot remove that AVF permission requirement.
