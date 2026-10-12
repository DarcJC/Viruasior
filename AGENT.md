# Viruaisor agent rules

## Conservative root use

Use the least privilege that passes the next observable check. Keep VM lifecycle, bundle import,
vsock, UI, and input in the ordinary application process. Prefer a one-time grant of the exact
required Android permission over running those features as root. If a grant cannot solve a gate,
keep only the gated operation in a narrow, authenticated privileged bridge.

Before a privileged change:
1. Reproduce the failing operation and record the specific permission/SELinux denial.
2. Check the existing grant or policy; make no change when access already exists.
3. Limit the operation to this package, required permission, resource, and lifetime. Validate all
   privileged inputs against an explicit allowlist; expose capabilities, not an arbitrary root shell.
4. Record what changed and how to undo it. Verify success from the ordinary app, then exit the
   one-shot root process or stop the bridge when the protected resource is released.

Keep SELinux Enforcing. Do not use global permissive mode, wildcard grants, recursive filesystem
permission relaxation, or allow rules for shared `untrusted_app`/`shell` domains as a shortcut.
Preserve unrelated system settings and existing grants. Persistent root modules, system-image
changes, and device-wide hidden-API relaxation require separate explicit user approval.

Root authorization is not AVF authorization: a UID 0 child does not elevate the app's Binder calls.
When changing AVF permissions or Direct Surface transport, first read
`docs/direct-surface-selinux-research.md` for the separate permission, ServiceManager, Binder, FD,
and shared-memory gates. Root implementation/authorization is under
`app/src/main/java/pro/darc/viruaisor/root/`; keep its API restricted to necessary capabilities.

## Verification and reporting

For Android changes, run `:app:testDebugUnitTest` and `:app:assembleDebug` via the Gradle Wrapper.
Confirm the generated arm64 native shim is present in the APK when changing Binder integration;
Gradle does not build it automatically (see README.md). Keep device images and captures in ignored
`.pi/device-debug/`, not in commits.

Report separately: root obtained, Android permissions granted, AVF available, VM booted, Surface
attached, and guest frames actually displayed. A successful `setSurface` call alone is not proof
of a working desktop. Record any remaining denial; do not widen privilege speculatively.
