# Explicit guest GPU profile and render access

## Goal

Make the selected VM bundle GPU profile authoritative from Android through the guest desktop
launcher. A `gfxstream` bundle must start Weston with the gfxstream Vulkan ICD and non-root access
to the render node. A `2d` bundle must retain the existing Lavapipe software baseline. This stage
does not attempt to fix the separate crosvm `v_gpu` crash.

## Considered approaches

1. Make every guest use gfxstream. This is small, but breaks the documented `2d` diagnostic
   baseline and lets the host manifest disagree with the guest.
2. Auto-detect the profile from installed ICD files or `/dev/dri`. File and node presence already
   proved insufficient, so auto-detection can silently select an unusable path.
3. Pass the manifest profile over the existing vsock control protocol. This is explicit,
   fail-closed, testable, and preserves both profiles. This is the selected approach.

## Protocol and components

- Android sends `START_DESKTOP 2d` or `START_DESKTOP gfxstream`; stop uses the matching
  `STOP_DESKTOP 2d` or `STOP_DESKTOP gfxstream`. The values come only from the parsed
  `VmGpuProfile`, not arbitrary input.
- The guest agent accepts only those exact values and maps them to a systemd template instance,
  `avf-desktop@2d.service` or `avf-desktop@gfxstream.service`. The legacy argument-free start and
  stop commands remain aliases for `2d` so an updated guest does not turn an older host into an
  accelerated configuration accidentally.
- The template passes its validated instance name to `avf-desktop start <profile>`. Invalid
  profiles fail before Weston starts.
- `2d` writes the current Zink + Lavapipe + `LIBGL_ALWAYS_SOFTWARE=1` environment.
- `gfxstream` requires the `render` group and gfxstream ICD, ensures `droid` is a member of
  `render` before creating a fresh PAM/user-systemd session, selects Zink plus only
  `gfxstream_vk_icd.json`, and does not set `LIBGL_ALWAYS_SOFTWARE`.
- The render node remains `root:render 0660`; the implementation must not grant world access or
  alter Android SELinux/policy.

## Failure and lifecycle behavior

Missing `render`, a missing gfxstream ICD, a failed group update, or an invalid profile is fatal to
desktop startup and must leave an actionable journal message. Profile preparation happens before
the user session starts so Weston cannot inherit stale supplementary groups. Stop remains
idempotent and profile-scoped. No fallback from gfxstream to Lavapipe is allowed.

## Tests

1. Add a pure Android command-format test for both profiles and verify invalid/arbitrary strings
   cannot reach the wire.
2. Add guest protocol tests for exact start/stop mappings, legacy `2d` behavior, and rejection of
   unknown values.
3. Add shell tests that exercise the actual desktop helper with fixture paths and commands. Assert
   the exact `2d` and gfxstream environment files, one-time `render` membership preparation, and
   fail-closed missing group/ICD behavior.
4. Run existing Node and guest preflight suites, `:app:testDebugUnitTest`, and
   `:app:assembleDebug`.

## Device rollout and rollback

After local tests pass, stop all VMs and retain byte-for-byte backups of every guest file changed.
Install the rebuilt agent/helper/template into the current imported rootfs, add `droid` to the
existing `render` group, and boot once with the current `gfxstream` manifest. The stage passes only
if the guest reports UID 1000 with render-node read/write access and the desktop process environment
selects the gfxstream ICD without software forcing. GPU resource execution and clean crosvm
shutdown remain stage-two gates and must not be claimed here.

Rollback restores the saved guest files and `/etc/group`, reloads systemd, and returns the VM
manifest to its backed-up profile if requested. Android policy, hidden-API settings, package grants,
and render-node permissions remain unchanged throughout.
