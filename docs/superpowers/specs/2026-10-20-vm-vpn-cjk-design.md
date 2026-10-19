# VM Android VPN forwarding and Chinese fonts

Date: 2026-10-20 (Asia/Singapore)

## Requested result and boundaries

The imported Manjaro VM can use the active Android VPN while retaining a kernel
packet path. Both the default prepared image and the currently imported image
display Chinese text in Chromium. Preserve existing user data and image capacity.

Use the app's existing KernelSU authorization. All persistent artifacts belong to
the application or guest images. Do not write Android system files, install root
modules, change SELinux policy, change global networking settings, or alter other
apps' VPN configuration. Existing root display and storage helpers keep their
current responsibilities.

## Evidence collected before changes

- Application network configuration currently only calls AVF `useNetwork`.
- The attached M367FC has working UID 0 access and SELinux Enforcing.
- One app-owned `crosvm_default` is running with `avf_tap_fixed`.
- The active VPN interface is `tun0`. Its current routing table is split into
  public IPv4 prefixes; its IPv6 default is unreachable. Interface names, addresses
  and routing table identities must be discovered again at runtime.
- `ip route get 1.1.1.1 from 10.178.3.245 iif avf_tap_fixed` selects `wlan0`.
  Supplying the existing VPN explicit-selection mark selects `tun0` instead.
  These were read-only route lookups, not packet forwarding validation.
- Android tethering only has IPv4 NAT and forwarding rules for TAP to Wi-Fi.
- The TAP ingress has Android tethering BPF classifiers for IPv4 and IPv6.
  A routing or iptables-only solution cannot claim to prevent bypass.
- The default rootfs build tree returns no fonts for `fc-list :lang=zh`.
  `fc-match sans-serif:lang=zh-cn` returns ordinary Noto Sans.
- The imported rootfs is a sparse 64 GiB ext4 file. Never replace it with the
  default 8 GiB rootfs or edit it while crosvm owns it.

## Approaches considered

1. **Scoped kernel forwarding (selected).** Add temporary policy routing, NAT,
   forwarding and TAP ingress control for this VM only. Root configures the
   control plane; packets do not traverse an additional application proxy.
2. **Application socket relay.** Forward through an ordinary app process so its
   sockets follow Android VPN policy. It reduces network administration needs but
   adds a network stack, packet copies and protocol/performance work.
3. **Guest VPN client.** Offers clear guest isolation but needs separate VPN
   credentials/configuration and does not follow the user's Android VPN selection.

## Application behavior

Add an explicit VM network mode: ordinary AVF networking or Android VPN sharing.
Enable sharing for the requested setup. In sharing mode, wait without external
connectivity when no eligible VPN is available. VPN disconnect or replacement
blocks guest external traffic until the new configuration is verified. The user
can deliberately select ordinary networking to restore the existing AVF behavior.

Expose connected, waiting for VPN, unsupported VPN, and cleanup/error states in
the application. Do not claim success merely because a rule command succeeded.
Do not start, stop or reconfigure the user's VPN application automatically.

## Narrow Root capability and lifetime

Use a separate authenticated libsu service following the existing storage bridge.
Accept only start/update/status/stop operations for the fixed VM capability;
provide no arbitrary shell, path, routing table, interface or command API.
Authenticate full caller UID, PID, application identity and Binder ownership.
Independently verify the single app-owned crosvm and expected AVF TAP before any
mutation. Discover and validate the active VPN for the caller's Android user;
never infer VPN identity merely from a `tun` interface name.

The normal app observes connectivity changes and owns VM lifecycle. A restricted
Root lease exists only while VPN-sharing resources need supervision and cleanup.
Binder death requests VM termination and retains the blocking guard until the VM
has exited; losing the helper stops the VM or leaves its external traffic
blocked. Each external command has a timeout and fixed argument
construction. Persist the exact owned-resource journal in app-private storage.
Never trust journal contents as arbitrary privileged commands.

## Packet path and isolation

1. Establish the blocking guard before allowing VM external traffic.
2. Add an app-owned TAP ingress classifier before tethering BPF classifiers that
   passes packets to the normal kernel stack. Preserve Android's classifiers,
   qdisc and BPF maps. Validate its identity and precedence. If this cannot be
   established reliably, refuse sharing without disabling offload globally.
3. Use dedicated, collision-checked rules/tables and app-tagged chains. Match the
   exact TAP and guest subnet; accept return traffic only for established related
   flows. Leave other tethering interfaces and Android app traffic unchanged.
4. Select the verified VPN's permitted routes and use subnet-scoped source NAT
   to its current tunnel address. Preserve the return route to the guest. Do not
   silently send destinations outside the VPN's routes over Wi-Fi.
5. Send guest TCP/UDP DNS through verified VPN DNS. Avoid the local Android
   tethering DNS proxy, whose requests could use a different upstream.
6. Preserve PMTU handling and clamp TCP MSS for the actual tunnel MTU. Support
   IPv6 only when the complete VPN route, addressing and return path are valid;
   otherwise block guest IPv6 external forwarding in sharing mode.
7. On VPN change, close the guard, replace only owned configuration, validate it,
   then reopen. Revalidate TAP identity and classifier presence. Do not flush
   system conntrack or unrelated rules.

Cleanup deletes only exact resources created by this lease, in a safe order.
Never restore a complete saved Android ruleset over concurrent system changes.
Keep collision or ownership mismatches visible and fail closed. VM stop removes
all owned resources; restart checks and recovers this application's stale journal
before opening traffic. No boot scripts or persistent Android networking changes.

## Chinese fonts and image update

Use official Noto CJK fonts with pinned versions, SHA-256 verification and their
OFL license. Include Chinese sans regular/bold and serif fallback sufficient for
Chromium and Plasma. Keep existing fonts and locale preferences. Install into a
guest-only font directory and refresh fontconfig caches as the guest environment.

Update the image preparation/bundle scripts so newly built default images include
the fonts and a build-time Chinese glyph-coverage check. Update the existing
prepared rootfs and bundle while preserving their current runtime components.

For the imported image, use a stopped-VM maintenance operation with a retained
recovery copy. First verify that crosvm and storage workers have released the
rootfs. Modify a sparse staging copy with app-owned ext4 maintenance tools;
never mount or write the live ext4 image. Preserve its
64 GiB size, user files and ownership. Verify filesystem integrity and the precise
font payload before activating it. Retain recovery until a boot and guest font
check pass. Do not reimport the default image over the user's imported image.

## Acceptance checks

- Unit tests exercise actual plan generation, command validation, resource
  ownership, route selection and rollback after partial failure. Run the required
  Gradle unit tests and debug assembly.
- Device checks show the guest's tested connections enter the selected VPN, DNS
  follows that VPN, IPv6 cannot escape, reconnect works, and ordinary mode works.
  Route lookup alone is insufficient; inspect packet counters and actual guest
  connections. Do not log VPN credentials, user browsing or full VPN configuration.
- Compare throughput and CPU under equivalent endpoints and VPN settings before
  and after; report measured results without promising a universal throughput.
- Test VM stop, application death, VPN loss/change and three restart cycles. Verify
  all owned rules/classifiers are removed and unrelated configuration remains.
- In both default and imported images, `fc-list :lang=zh` is nonempty and the
  selected fonts contain the required Chinese glyphs. Render a local Chinese
  sample in Chromium and inspect it, avoiding the user's browsing session.
- Confirm Android remains Enforcing and no Android system files or global settings
  were changed.

## Approval point

Review the scoped kernel forwarding design and the proposed behavior of pausing
guest external networking when Android VPN is unavailable. Implementation and
device/image mutations follow design approval.
