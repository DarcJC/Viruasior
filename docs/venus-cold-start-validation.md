# Venus desktop cold-start failure, 2026-10-20

Device: M367FC, Android 17/API 37, Manjaro Plasma, Venus/Zink on Mali.
The user's rootfs remains 64 GiB (68,719,476,736 bytes).

## Failure and diagnosis

The 8 to 64 GiB resize completed normally. At 01:14:16 the agent verified
`rootBytes=68719476736`, `pending=false`, `mounted=true`. The subsequent
desktop start failed with `Guest control command failed: ERROR systemctl`.

The failing command was `avf-plasma.service`'s `ExecStartPre`, not ext4 or the
resize recovery transaction. Venus reported Mali, EGL/Zink initialized, shaders
compiled and GL draw/finish/readback reported no GL error. However, all three
RGBA samples were `[0, 0, 0, 0]`, rather than the expected gradient. The single
probe exited with `GPU_DRAW_READBACK_FAIL`, so systemctl failed the start.

Requesting the desktop again in the **same VM**, without changing the 64 GiB
disk, passed all three samples and displayed Plasma. A delayed first request
also passed; immediate cold starts can pass too. The same symptom had occurred
at 8 GiB before this resize. Guest-agent readiness therefore does not guarantee
that the first GPU draw has usable readback. These observations establish the
transient startup failure; they do not establish its underlying Mali/renderer
cache or synchronization mechanism.

## Correction

The draw probe reports the exact three-sample all-zero result separately with
exit status 75. This is never a successful draw. The Venus preflight permits at
most three fresh probe processes, waiting one second between attempts, only
for that result. It starts Plasma only after a real gradient passes with the
existing per-channel tolerance of two. The service's existing 40-second outer
timeout still bounds the whole preflight.

Other mismatched pixels, incomplete samples, GL/EGL errors, wrong hardware,
software renderers and other process failures remain fatal. Persistent zero
readback fails after three attempts. There is no generic retry of systemctl,
disk rollback, CPU fallback or new privilege/policy requirement.

Both guest files were backed up, replaced while the VM and its helpers were
stopped, and dumped back to verify SHA-256. Offline `e2fsck -fn` passed before
and after the update. Installed hashes:

- `venus-preflight`: `f73cfce8c3324c5fdb542889d132e738d65a3cb8129f27bd5615b95431cee43a`
- `guest-gpu-draw-probe.py`: `245ade4dc5eb1f857319051c44ac9fefac0ef00f301188e621e4910f241e5e2c`

The bundle builder already installs these two source files; new bundles receive
the correction. This device received the guest update directly, preserving its
existing disk and data.

## Verification

`python3 -m unittest discover -s guest/plasma -p '*test.py'`: 43 tests passed.
Nine new regressions cover exact/tolerated gradients, zero versus permanent
errors, CLI result classification, zero-then-success, immediate success,
bounded persistent failure, permanent failure and software rejection. The
zero-then-success replay failed against the old preflight (exit 75 instead of
success), then passed after the fix. The shell fixtures execute the production
preflight with a substituted render-node path and fake external GPU commands;
they do not establish physical GPU correctness.

All 117 Node script tests passed. Shell syntax, Python compilation and
`git diff --check` passed. No Android/native source changed.

Three complete stop/cleanup/cold-start cycles on the existing 64 GiB disk:

| Start | GPU draw pass | Plasma hardware-ready | Actual viewport screenshot |
| --- | --- | --- | --- |
| 01:24:11 | 01:24:15 | 01:24:19 | 400/400 lit samples, 39 colors |
| 01:25:08 | 01:25:12 | 01:25:17 | 400/400 lit samples, 39 colors |
| 01:26:21 | 01:26:26 | 01:26:29 | 400/400 lit samples, 39 colors |

Each cycle produced the correct three shader samples, KWin OpenGL readiness,
real visible Plasma frames, 64 GiB agent verification and `mounted=true` for
`storage/0`. No desktop-start systemctl error occurred. These three device runs
passed on their first probe; the transient retry and persistent-failure paths
were verified by the deterministic regressions above. Screenshots verify the
captured desktop, not a new physical-panel color qualification.

Root was used only to dispatch the existing fixed service actions. VM lifecycle,
main crosvm, Binder/vsock and UI retain ordinary app ownership; existing bounded
display/storage helpers retain their existing roles. SELinux remains Enforcing;
permissions and policy were unchanged. The VM was left running successfully.
Temporary device-side script backups were removed after verification; captures
and logs remain local in `.pi/device-debug/desktop-start-64g-20261020/`.
