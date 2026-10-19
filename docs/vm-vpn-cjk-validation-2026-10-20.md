# Device validation: 2026-10-20

Device: Xiaomi M367FC, Android 17 / API 37. Application UID 10278,
`pro.darc.viruaisor`. Existing KernelSU and AVF access were reused. No Android
system file, SELinux policy or VPN-client configuration was changed.

## Networking

- The ordinary application process booted `crosvm_default`; the existing desktop
  lease verified the crosvm UID as 10278 and effective capabilities as zero.
- The verified active VPN was `tun0`, netId 159, owned by v2rayNG in Android user 0.
  These values are discovered at runtime, not hardcoded into forwarding rules.
  Its numeric route table was 1083. Rules and recovery journal use that number so
  removal still works after Android deletes the interface-name alias; the parser
  and absent/ambiguous-alias cases are unit tested without changing a system file.
- The installed ingress classifier was priority 1, handle `0x764d`, classic BPF
  bytecode `1,6 0 0 0`, direct action. Android's priority 2/3 tethering programs
  retained their original IDs 3/9 and tags `074a3b492ee3f948` /
  `097ddcb62dcfc8de`.
- Actual guest DNS and HTTPS passed. Ordinary mode reported a CN exit; VPN mode
  reported SG with a different exit-address hash. Raw exit addresses were not logged.
- Scoped FORWARD counters recorded traffic in both TAP-to-tun0 and tun0-to-TAP
  directions. DNS DNAT counters increased for the verified VPN DNS `1.1.1.1`.
- Removing only this application's VPN lookup rule produced guest DNS failure,
  while the IPv4 and IPv6 terminal unreachable guards remained installed.
  Restoring that exact rule restored DNS/HTTPS through the SG exit. No Wi-Fi
  fallback succeeded during the interruption.
- At least three start/stop cycles completed with owned rules/chains and journals
  removed. A separate deliberate application force-stop also terminated the VM
  and removed the network lease. Subsequent startup and normal shutdown passed.
- Final checks include absence of all `vvm_10278_*` filter/NAT/mangle resources,
  absence of the app's unreachable rules, no VM/root helper remaining, SELinux
  Enforcing and the phone asleep.

The interruption test removed the app's own route; it did not disconnect or
reconfigure the user's Android VPN. Physical VPN reconnection, switching VPN
providers, cellular handoff and sustained high-throughput stress remain untested.
IPv6 sharing is intentionally unsupported and external IPv6 is blocked.

## Performance spot check

Both modes downloaded the same fixed 1 MiB HTTPS file from
`https://proof.ovh.net/files/1Mb.dat`, with guest proxy environment variables
disabled. This small sample includes DNS/TLS/remote-server latency:

| Mode | Download time | Effective throughput |
| --- | ---: | ---: |
| Ordinary AVF | 2.457 s | 3.41 Mbps |
| Android VPN sharing | 2.030 s | 4.13 Mbps |

These measurements show no observed throughput regression for this endpoint;
they are not a capacity limit or a universal performance guarantee. Packet
forwarding/NAT stays in the kernel, with no root packet proxy.

Removing repeated Status-driven refreshes and using one 1.5-second worker reduced
the root helper's sampled CPU use from 0.50 CPU seconds over 3.377 wall seconds to
0.18 over 3.489 seconds. The latter is about 5.2% of one CPU core over this short
harness interval. This process also hosts the existing privileged services; the
measurement is not a pure per-packet VPN cost. The corresponding VM sample used
1.02 CPU seconds over 3.489 seconds.

## Images and Chinese text

- The current imported rootfs remains 68,719,476,736 bytes (64 GiB). Its original
  sparse disk is retained at `files/vms/default/.cjk-update/original`.
- The default rootfs remains 8,589,934,592 bytes (8 GiB), with its original retained
  beside it in `.cjk-update/original`.
- All three fonts, OFL license, guest agent and diagnostic helper were individually
  dumped from each updated candidate and SHA-256 checked before activation.
  Read-only e2fsck passed again at confirmation. The real ext4 fixture separately
  verifies that a user document survives updates and rollback restores original
  disk bytes.
- `fc-list :lang=zh` lists both Noto Sans CJK SC and Noto Serif CJK SC in the default
  guest root. The booted imported guest's FONT_STATUS check passed; its current
  generic sans and serif fallback both resolve to Noto Sans CJK SC, preserving its
  existing fontconfig preferences.
- Chromium rendered a local simplified/traditional/bold Chinese sample using a
  fresh temporary profile. The PNG was visually inspected: Chinese glyphs appear
  rather than missing-glyph boxes. No Android display was needed. The PNG is
  44,107 bytes, SHA-256
  `a16106ff682942e86b97cb9d915a3650572f71f1d64fd2dc1923dcba806fb81d`.
- The prepared ZIP and its copy in this application's external files directory
  are 1,738,528,433 bytes with matching SHA-256
  `069994c9430e9341e8667e461f00948bfd04d08bdd4888d57f647c51b559f53f`.
  Full ZIP CRC verification passed, and the original `.pre-cjk.zip` is retained.

The default prepared image was checked offline and through its unpacked guest
font environment; it was not imported over the user's current 64 GiB disk merely
to repeat the Chromium check. Chromium boot/render verification used the imported
image with the same hash-verified font payload.

## Host checks and screen-off testing

The final Gradle run passed `testDebugUnitTest`, `assembleDebug` and
`assembleDebugAndroidTest`: 192 tests, zero failures, one existing skip. New rule
tests cover scope, untrusted inputs, real subnet calculation, classifier identity,
VPN owner/user selection and retryable partial cleanup. Native guest protocol
tests and both real sparse-copy/ext4-maintenance fixtures passed. APK verification
confirmed eleven arm64 ELF tools, exact native bytes/pins and required notices.

One earlier full run hit a Windows AccessDeniedException when an unchanged
rootfs-recovery test renamed its temporary file. A subsequent full run passed;
no unrelated Android recovery code was changed to work around that host failure.

Greeze froze the unattended app even with a foreground service/partial wake lock.
A short, app-specific temporary Doze exemption was tried, proved ineffective and
expired. Headless checks then used bounded `cmd greezer thuid` calls for UID 10278
only. They did not disable freezing globally or change a persistent whitelist.
The thaw worker exits after each test. VM lifetime with the screen off remains
subject to this device's freezer policy; the existing lease stops the VM when
its application heartbeat cannot run.

Evidence is local and ignored under
`.pi/device-debug/vpn-cjk-validation-20261020/`, including kernel counters,
Chromium PNG, guest JSON, CPU samples and instrumentation output. The completed
test state is VM stopped, application closed and temporary resources removed.
