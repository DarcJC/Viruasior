# Application icon

The launcher and workbench use the same transparent blue/violet layered V mark
at `app/src/main/res/drawable-nodpi/viruaisor_mark.png`. The PNG is the original
generated asset; Android scales it without baking a launcher mask into it.

Adaptive icons use a dark `#101C36` background and a 16 dp foreground inset.
The monochrome drawable is a simple V silhouette for themed launcher icons.
The app requires API 37, so the adaptive resources cover every supported device;
the Android Studio template's legacy robot bitmaps have been removed.

Generated with the built-in image generation tool on 2026-10-23. Prompt:

> Create a polished Android launcher foreground icon for Viruaisor, an Android
> app running hardware-accelerated Linux virtual desktops. A bold geometric V
> formed from two folded, slightly overlapping virtual display panes, with a
> subtle second layer suggesting virtualization. Blue, indigo and violet with
> a cool cyan highlight, restrained dimensional shading, crisp silhouette,
> readable at 48 pixels. Transparent background, one centered mark, no
> wordmark, Android robot, tile, border or outside shadow. Square composition
> with generous transparent margins for adaptive icon masking.

Validate resource compilation with `:app:testDebugUnitTest :app:assembleDebug`
and inspect the launcher mask and workbench branding on a device.
