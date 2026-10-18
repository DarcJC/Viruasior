# AHB / KWin / resize implementation checklist

Approved design: [2026-10-18-ahb-kwin-resize-design.md](2026-10-18-ahb-kwin-resize-design.md).

1. Capture the existing visible CPU-copy desktop and preserve rollback artifacts.
2. Add red regressions for direct submission, attachment preservation, composer usage,
   pending GPU fences, supported display modes, resize validation and rollback.
3. Submit the original AHB through the dedicated visible layer. Return the Android
   consumer fence and retain its buffer until it signals. Keep CPU fallback explicit.
4. Preserve pending Vulkan fences, distinguish deferred from completed renderer work,
   and skip presentation until producer queues are complete.
5. Add bounded guest XRandR queries/modesets and ordinary-app controls. Confirm input
   geometry from the first submitted new-size frame; reject stale session callbacks.
6. Build/pin/package the native candidate, run local tests, and verify direct AHB
   presentation on device with KWin disabled before changing the guest session.
7. Preserve a stopped-VM backup of changed guest files. Enable KWin OpenGL, reproduce
   and isolate any remaining rendering/interop failure, then verify actual visible frames.
8. Exercise at least three supported resolutions (including aspect-ratio changes),
   input, detach/reattach, VM restart and resource cleanup. Record performance evidence.
9. Update strict acceptance scripts, current-state documentation and validated limitations.

The Android holder/virtio ABS input ranges keep their boot dimensions. Native source
geometry follows the actual guest scanout and is scaled into that fixed parent space;
the view aspect ratio and relative mouse mapping follow the committed frame dimensions.
This avoids an unnecessary helper restart during every modeset.
