# GitHub release build

Push an exact `v<major>.<minor>.<patch>` tag to GitHub to run `.github/workflows/release.yml`.
For example, `v1.2.3` becomes Android `versionName=1.2.3` and
`versionCode=1002004`. Components must have no leading zero, minor and patch
must be below 1000, and the result must fit the Android version code range.
Invalid tags can match GitHub's broad tag glob, but fail before any build or
publication.

The workflow publishes a signed APK, a separate VM import ZIP, and `SHA256SUMS`.
The ZIP has four root entries: `vm.json`, `vmlinuz`, `initrd.img`, and
`root_part`. The two reviewed crosvm PIE executables are packaged inside the
APK as `libvenus_crosvm.so` and `libvenus_desktop_crosvm.so`; they are not
independent downloads. The APK gate verifies all eleven arm64 ELF files,
the two crosvm hashes and lengths from `RootOverlayArtifactPolicy.kt`, and
the required third-party notices. GitHub allows each Release asset to be
smaller than 2 GiB; the validator rejects an oversized bundle before upload.

## Runner setup

Use a persistent Linux x64 runner with labels `self-hosted`, `linux`, `x64`,
and `viruaisor-release`. It needs passwordless `sudo` for the guest image
build, Android SDK API 37 and build tools, NDK r30, JDK 25, Gradle's network
dependencies, `gh`, Python 3, Rust with the Android arm64 target, the
arm64 GNU cross compiler, Meson, Ninja, glslangValidator, QEMU binfmt,
e2fsprogs, and the other tools used by the existing `scripts/build-*` scripts.
Do not make the runner available to untrusted pull requests. The build job
receives a token with `contents: write` only for tag releases.

Create these GitHub Actions **repository variables** with absolute Linux paths:

| Variable | Contents |
| --- | --- |
| `VIRUAISOR_INPUTS_DIR` | Persistent `device-debug` input directory containing `image/vmlinuz`, `image/initrd.img`, and `venus-crosvm-build/mesa-guest-reference` plus the existing pinned guest build sources. |
| `VIRUAISOR_CROSVM_BIN_DIR` | Directory containing the two reviewed `libvenus_crosvm.so` and `libvenus_desktop_crosvm.so` executables. |
| `VIRUAISOR_VENUS_WORK_DIR` | Pinned Android host renderer build workspace used by `build-venus-host.sh`. |
| `VIRUAISOR_ANDROID_NDK_HOME` | Linux Android NDK r30 directory. |
| `VIRUAISOR_ANDROID_SDK_HOME` | Linux Android SDK directory containing API 37 and build tools. |

The runner also needs the prepared Manjaro workspace under
`/tmp/viruaisor-manjaro`: `rootfs.tar.gz`, an extracted and configured `root/`,
and the guest Mesa build directories. The build checks the official Manjaro
rootfs archive SHA-256 before use, recompiles the source-built Android
helpers and guest Mesa/desktop pieces, creates a new ext4 image, runs Android
unit tests, and assembles the Release APK. The two crosvm executables remain
reviewed binary inputs: their pinned hashes must be updated and validated
after any crosvm source change. The current scripts still require device
APEX linker inputs in the prepared crosvm workspace, so this is a
preprovisioned runner build rather than a fresh GitHub-hosted rebuild.

Create these **repository secrets** for an existing, stable Android release
signing key: `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`,
`ANDROID_SIGNING_STORE_PASSWORD`, and `ANDROID_SIGNING_KEY_PASSWORD`.
The first secret is the base64 encoding of the keystore file. The workflow
keeps the decoded keystore under the runner's temporary directory, signs with
`apksigner`, verifies the result, and removes the temporary keystore.
Keep the original keystore safe: Android updates must use the same signer.

## Kernel and guest source

The current reviewed kernel and initrd came from the device's Google
Ferrochrome guest image (`hourly-11815`, September 2026), not from an Android
ROM. The release build accepts only their SHA-256 values:

```text
vmlinuz    1008c4aa740113c3bccd1aa72376bcd23602a2f94aa170d51617b7089c647343
initrd.img 39b4de660fc6d7a965265164bfc4ca71662032d97a7d43ef25a905ead7c2c475
```

Google's public Ferrochrome `5100000` archive is available at
`https://dl.google.com/android/ferrochrome/5100000/aarch64/images.tar.gz`.
It currently contains a newer October build with different kernel/initrd
hashes. `scripts/fetch-ferrochrome-kernel.sh` downloads that fixed archive,
verifies its SHA-256, extracts only the kernel and initrd, verifies each,
and refuses to overwrite differing reviewed files. The release workflow
continues to use the September inputs until the newer kernel is validated
on the target device and its reviewed pins are deliberately updated.

The Manjaro userspace starts from the
[official 20261012 arm64 rootfs release](https://github.com/manjaro-arm/rootfs/releases/tag/20261012),
with SHA-256 `1ae540a80e21e1ca55e0576df9618fd2865cf4596b8204d20780f225aca5164a`.
The resulting rootfs is writable when imported and its hash changes during
normal guest use. See [the bundle format](vm-bundle.md) for import details.

## Before creating a tag

1. Ensure the GitHub repository contains the workflow and the runner has all
   reviewed inputs. The existing `origin` remote may still point to a
   different Git service; push the tag to the intended GitHub remote.
2. Verify the signing secrets and runner variables are configured.
3. After pushing the tag, inspect the workflow result and Release asset
   hashes. Test the signed APK and imported image on the target device before
   treating the release as device validated.
