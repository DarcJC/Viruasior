# Android VPN sharing and Chinese fonts

The VM network card enables Android VPN sharing by default. Change between VPN
sharing and ordinary AVF networking only while the VM is stopped. Connect the
Android VPN before starting the VM; when no usable VPN exists, the VM can boot
but its external network remains blocked. VPN loss pauses external networking.

## Privilege and packet path

VM startup, shutdown, vsock, UI and image maintenance run as the application UID.
The existing KernelSU grant starts a separate authenticated `RootVpnService` leaf.
It accepts only Start, Status and Stop, bound to the ordinary VM process UID,
PID and Binder token. It accepts no commands, executable paths or interface names.

The leaf verifies the single application-owned `crosvm_default`, the current TAP
ifindex, Android's active VPN transport, its owner/user, IPv4 address, DNS and
routing table. Public NetworkCapabilities redacts another application's owner;
only that owner identity is read from the active ConnectivityService agent dump.
Link configuration still comes from ConnectivityManager. Unknown formats, multiple
VPNs, foreign owners or classifier collisions fail closed. See Android's
[owner UID API](https://developer.android.com/reference/android/net/NetworkCapabilities#getOwnerUid()).
The numeric routing-table identity is read from netd's current alias metadata,
verified against the tunnel's routes and recorded in journal version 2. Neither
adding nor removing the owned lookup rule depends on an alias surviving VPN loss.
The host alias file is read-only; it is never changed.

The fixed TAP `avf_tap_fixed` receives a priority 1 classic BPF classifier,
`RET TC_ACT_OK`, ahead of Android's tethering offload classifiers. The exact
handle, bytecode and priority are checked. Android's qdisc, classifiers and BPF
maps are preserved. This sends VM packets into the normal kernel routing path.

Per-UID chains and policy rules then provide:

- A terminal IPv4/IPv6 unreachable rule before ordinary tethering can select Wi-Fi.
- IPv4 routing through the verified VPN's existing permitted routes.
- Guest-subnet-scoped masquerading and established/related return forwarding.
- TCP/UDP DNS redirection to the VPN DNS, with the local tethering DNS proxy blocked.
- Scoped TCP MSS clamping for PMTU handling.
- IPv6 external forwarding blocked; this version does not promise IPv6 VPN sharing.

Root never copies packets or proxies connections. Linux handles routing, NAT and
conntrack; the user's existing Android VPN handles its tunnel as usual. The root
worker refreshes state every 1.5 seconds, and Status reads its cached result.
The kernel blocking guard remains effective independently of that refresh when
the VPN route disappears. A bounded application partial wake lock belongs to the
VM session and is released during teardown.

Only exact owned resources are removed, in reverse order. No system-file writes,
global sysctl changes, complete ruleset restoration, global conntrack flush,
SELinux relaxation, VPN-client reconfiguration or persistent root module is used.
The app-private `files/vpn-lease/journal` records intent before mutations. Failed
cleanup retains the journal/guard; the next start recovers it after all VMs exit.
Binder death closes forwarding and retains protection until the VM has exited.

## Fonts and safe image maintenance

`guest/fonts/manifest.json` pins official OFL Noto Sans CJK SC regular/bold and
Noto Serif CJK SC regular fonts, their license and SHA-256 digests. Preparation
checks the fonts' real Unicode cmap for simplified and traditional Chinese.
Fonts live only in guest `/usr/local/share/fonts/viruaisor`.

Both rootfs preparation and bundle building install the fonts and refresh
fontconfig. Existing images use `scripts/update-guest-cjk.py`, with the VM stopped:

```sh
python3 scripts/prepare-cjk-fonts.py --verify
python3 scripts/update-guest-cjk.py --device --adb /path/to/adb --action apply
# After boot and guest font/render verification, stop the VM again:
python3 scripts/update-guest-cjk.py --device --adb /path/to/adb --action confirm
```

The updater uses ordinary app-UID sparse-copy/debugfs/e2fsck tools. It creates a
maintenance marker that prevents VM startup, retains the original disk, modifies
a separate sparse candidate, verifies every payload hash and the filesystem,
preserves the disk's logical size, and atomically activates the candidate.
It updates only the three fonts, license, fixed guest agent and diagnostic helper.
It does not reimport the default image over the user's disk.

The recovery copy is retained at `files/vms/default/.cjk-update/original` even
after confirmation. To restore the pre-update disk while stopped:

```sh
python3 scripts/update-guest-cjk.py --device --adb /path/to/adb --action recover
```

Recovery also restores the user's files to that backup's point in time. Do not
use it to undo fonts after creating new guest documents without first preserving
those documents. `refresh` updates a new candidate while retaining the first
original. A prepared ZIP keeps a separate `.pre-cjk.zip` recovery copy; later
repacking never overwrites an earlier recovery artifact.

## Verification

Required host checks:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
make -C guest/avf-agent test
python3 scripts/test-cjk-tools.py
```

The ext4 fixture checks real apply/refresh/confirm/rollback, original-disk hashes,
user-document preservation, sparse holes, symlink rejection and refusal to
overwrite an existing destination. `verify-native-packaging.ps1` checks all eleven
arm64 ELF artifacts, their pins/bytes and ext4-tool notices in the APK.

`scripts/test-vm-vpn-headless.py` drives an opt-in instrumentation controller
without launching an Activity. Fixed guest checks run as `droid`, use a fresh
temporary Chromium profile and return bounded JSON. `--benchmark` downloads a
fixed 1 MiB HTTPS file with proxy environment variables disabled. `--kill-switch`
temporarily removes/restores only this lease's VPN lookup rule and checks failure
and recovery. `--app-death` tests force-stop cleanup. It never toggles the user's
VPN client. Test evidence is kept in ignored `.pi/device-debug/`.

On this Xiaomi Android 17 device, Greeze freezes an unattended app even with a
foreground service and partial wake lock. `--vendor-thaw` uses the existing root
grant only to thaw this app UID during the bounded headless test. It changes no
freezer policy/whitelist and stops when the test ends. Without that test aid, this
device's freezer can suspend VM heartbeats and the existing display lease stops
the VM. Normal screen-off survival is therefore device-policy dependent.

Device results and remaining validation limits are recorded in
`vm-vpn-cjk-validation-2026-10-20.md`.
