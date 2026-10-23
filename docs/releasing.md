# GitHub release build

Push an exact `v<major>.<minor>.<patch>` tag to GitHub to run `.github/workflows/release.yml`.
For example, `v1.2.3` becomes Android `versionName=1.2.3` and
`versionCode=1002004`. Components must have no leading zero, minor and patch
must be below 1000, and the result must fit the Android version code range.
Invalid tags can match GitHub's broad tag glob, but fail before any build or
publication.

For an end-to-end build check without creating a version tag, run **Build and
publish release → Run workflow** on the desired branch and supply a valid
`version_tag` such as `v0.0.2`. This follows the same input, compilation,
signing and asset validation steps, then uploads the signed APK, VM ZIP and
checksums as a seven-day Actions artifact. Only a pushed version tag publishes
a GitHub Release. Manual verification still requires the signing secrets.

The workflow publishes a signed APK, a separate VM import ZIP, and `SHA256SUMS`.
The ZIP has four root entries: `vm.json`, `vmlinuz`, `initrd.img`, and
`root_part`. The two reviewed crosvm PIE executables are packaged inside the
APK as `libvenus_crosvm.so` and `libvenus_desktop_crosvm.so`; they are not
independent downloads. The APK gate verifies all eleven arm64 ELF files,
the two crosvm hashes and lengths from `RootOverlayArtifactPolicy.kt`, and
the required third-party notices. GitHub allows each Release asset to be
smaller than 2 GiB; the validator rejects an oversized bundle before upload.

## GitHub-hosted runner setup

The job uses `ubuntu-latest`. It installs JDK 25, Android SDK API 37.0,
build tools 37.0.0, NDK 30.0.16248370, the arm64 cross compiler, QEMU and
the native/guest build dependencies. Each run downloads the pinned Manjaro
base rootfs, prepares Plasma and its development headers, fetches the pinned
Mesa and virglrenderer sources, and rebuilds the native helpers and VM image.
The guest builders check the paired Qt 5.15.12, KWin 5.27.10 and Xorg 21.1.11
versions; a distro package change requires reviewing those compatibility checks.
No self-hosted runner registration or persistent build workspace is needed.

The September kernel/initrd and two reviewed crosvm binaries still need a
one-time export because they are not available at a pinned public download
URL. On the existing development checkout, run this with Python 3 (or WSL):

```sh
python3 scripts/release_inputs.py pack \
  .pi/device-debug/image app/src/main/jniLibs/arm64-v8a \
  dist/viruaisor-release-inputs.zip
```

The exporter checks all four pinned hashes and includes only those four
files. It never exports the surrounding `.pi` workspace, device logs,
keystores, or user directories. It prints the ZIP's SHA-256 and writes
`dist/viruaisor-release-inputs.zip.sha256`. For the validated snapshot
renderer, the current archive digest is
`d3dd520c097d6f8c563978dcb559158c6afdb3c8f0942fe4ccd84d5c0e4de950`.
Regenerate it and update the repository variable when any reviewed input
changes. Upload the ZIP once as a public
GitHub Release asset under a non-version tag such as `release-inputs-20261023`
(this tag does not trigger the release workflow), or to another HTTPS host.
Mark the input Release as a pre-release so it is not presented as the latest app release.
Retain that asset for future builds. Use its direct download URL, not the
Release page URL.

The reviewed 2026-10-23 input archive is available from
[the build-input Release](https://github.com/DarcJC/Viruasior/releases/tag/release-inputs-20261023).
Its uploaded asset digest matches the archive digest above.

Create these GitHub Actions **repository variables**:

| Variable | Contents |
| --- | --- |
| `VIRUAISOR_RELEASE_INPUTS_URL` | Public HTTPS download URL for `viruaisor-release-inputs.zip`. |
| `VIRUAISOR_RELEASE_INPUTS_SHA256` | The 64-character lowercase SHA-256 printed by the exporter. |

The download is verified against this digest and every member is checked
against the reviewed pins in `scripts/release_inputs.py` before extraction.
The crosvm executables remain binary inputs: the build does not need device
APEX linker files, but a crosvm source change still requires rebuilding,
device validation, and updating its pins before exporting a replacement ZIP.
The validated desktop crosvm SHA-256 is
`7232890999d06ab07784f9f3a68b06a2ea6a47ec1e8ae98e44fc011379296ff2`.
The previous five variables containing local Linux paths are no longer used.

The guest Mesa and Zink drivers are rebuilt from their pinned sources and
repository patches, including the paired WSI completion-fd handoff. The
distribution's unmodified vkmark remains the default; no custom benchmark
is built or installed. On the validated tablet, all 13 default scenes score
1838 on XCB and 2764 on nested Wayland; see
[the cache and synchronization validation](guest/coherent-memory.md).

Create these **repository secrets** for an existing, stable Android release
signing key: `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`,
`ANDROID_SIGNING_STORE_PASSWORD`, and `ANDROID_SIGNING_KEY_PASSWORD`.
The first secret is the base64 encoding of the keystore file. The workflow
keeps the decoded keystore under the runner's temporary directory, signs with
`apksigner`, verifies the result, and removes the temporary keystore.
Keep the original keystore safe: Android updates must use the same signer.

The release signer created for this application on 2026-10-23 uses RSA-4096
and has certificate SHA-256
`07b67627ef1734198d7bd9134ee250ccb090b5a86e7e0a24eaab2ff4bd955a34`.
The keystore and passwords are kept outside the source tree. Back up both
securely; GitHub Secrets cannot be used to recover them later.

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

1. Ensure the GitHub repository contains the workflow and the reviewed input
   ZIP has a reachable download URL. The existing `origin` remote may still point to a
   different Git service; push the tag to the intended GitHub remote.
2. Verify the signing secrets and the two input variables are configured.
3. After pushing the tag, inspect the workflow result and Release asset
   hashes. Test the signed APK and imported image on the target device before
   treating the release as device validated.

## Replacing a queued self-hosted run

A run created from the previous workflow waits for a runner with all of
`self-hosted`, `linux`, `x64`, and `viruaisor-release`. Without a registered,
online runner matching those labels, it cannot start. Cancel that queued
run, push the updated workflow, and create a new version tag pointing at
the updated commit. Re-running the old run uses its original commit and
will still request the old self-hosted labels.
