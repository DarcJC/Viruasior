## Assets

- `Viruaisor-vX.Y.Z.apk`: signed Android APK for arm64. The reviewed Venus crosvm executables and the other native helpers are inside the APK under `lib/arm64-v8a/`.
- `Viruaisor-vX.Y.Z-manjaro-plasma-venus.zip`: VM import bundle. Import this ZIP from the app; do not extract it first. Its root contains `vm.json`, `vmlinuz`, `initrd.img`, and the writable `root_part` image.
- `SHA256SUMS`: hashes of the two downloadable assets.

This build targets the currently reviewed Android 17 AVF and KernelSU device configuration. AVF permissions, the stock APEX artifact hashes, and graphics support are device dependent. See [bundle requirements](vm-bundle.md) and [release build details](releasing.md). Keep a backup of an existing VM before replacing its bundle.
