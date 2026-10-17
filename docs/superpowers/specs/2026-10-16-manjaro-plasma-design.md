# Manjaro Plasma with Venus and Direct Surface

User scope: replace only Viruaisor's imported VM, require real Venus hardware rendering,
display the Plasma desktop inside the app and enter fullscreen on click. Forward keyboard
(including both ESC presses), touchpad and touchscreen. Ctrl+Shift+Alt+Q exits capture;
Android Back/Home navigation remains available. Prepare locked-device validation without
unlocking or changing lock/power settings. Do not modify system images, APEX bytes, system
drivers, SELinux, or global settings. All changes must be confined to this application.

Use an official checksum-pinned Manjaro ARM rootfs and install Plasma and its dependencies.
Keep a separate reproducible image builder; generated images stay ignored. The AVF-compatible
kernel is retained as a boot component, not a Debian userspace. Install the paired private
Venus ICD; Zink supplies GL over Venus. GPU failures must fail visibly, never silently select
llvmpipe/lavapipe. The existing compute result is not a desktop acceleration result.

Use the existing app-scoped Venus runtime isolation and narrow display helper where their
gates pass. Preserve system/APEX files and external mount namespaces byte-for-byte. Reject
competing VMs and unknown ownership. VM lifecycle and input stay under the ordinary app UID.
If an operation cannot meet these boundaries, retain prepared artifacts and report the gate.

Capture input at the Activity boundary while fullscreen. Use evdev scan codes with explicit
Android fallback mappings, track pressed keys and release them on focus loss/exit. Map touch
coordinates to the guest buffer; route relative mouse movement, buttons and scrolling through
AVF mouse input. Preserve Android navigation and transient system-bar gestures. Surface
ownership is independent from fullscreen layout and input ownership.

Acceptance requires separate evidence for root authorization, existing Android grants, AVF,
Manjaro boot, Venus GPU compute and graphics, Plasma running, Surface attachment, actual
changing guest frames, keyboard/ESC, mouse/scroll, touch, exit chord, Back/Home and cleanup.
Unit/native tests and APK construction can pass while the locked-device UI checks remain pending.
