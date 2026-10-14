# avf-agent

Minimal guest-side vsock control daemon for the headless MVP.

```sh
make
sudo make install
sudo systemctl enable avf-agent.service
```

Cross-compilation is supported through standard Make variables; `make arm64-static` produces the
self-contained aarch64 binary used by the device test image. The agent service runs in
`multi-user.target`; it does not pull in a display manager or `graphical.target`.

`avf-desktop@.service` has no `[Install]` section and therefore cannot be enabled by a target. The VM
kernel command line blacklists `drm` and `virtio_gpu` during automatic module discovery. Only an
explicit `START_DESKTOP 2d` or `START_DESKTOP gfxstream` command starts its matching instance; its
`ExecStartPre` loads `virtio_gpu` only when `/sys/module/virtio_gpu` is absent, and then it starts the
existing Weston user unit. A built-in or already-loaded driver must not be blocked by `modprobe`
failing due to missing module files. If the driver is absent and loading fails, startup still fails
rather than hiding the missing GPU.
Module blacklisting does not disable a driver compiled into the kernel; Weston remains explicitly
gated either way. Do not install a shell-profile or udev desktop trigger.

The `2d` instance retains the explicit Lavapipe software baseline. The `gfxstream` instance fails
closed unless the gfxstream Vulkan ICD and `render` group exist, adds `droid` to that group before
creating a fresh user session, verifies render-node access, and starts Zink without a software
override. It never changes render-node permissions or falls back to Lavapipe.

Run the preflight regression from the repository root (Bash; no root or VM needed):

```sh
bash scripts/test-guest-desktop-preflight.sh
bash scripts/test-guest-desktop-profile.sh
make -C guest/avf-agent test
```

The four cases execute the unit's actual `ExecStartPre` command with fixture module/loader paths.
They cover an already-available driver with missing module files, avoiding redundant loads,
loading an absent driver, and propagating a real load failure. This is not a Weston/rendering test.

Each accepted connection begins with `READY`. The daemon handles `SHUTDOWN`, profile-qualified
`START_DESKTOP`/`STOP_DESKTOP`, and legacy argument-free desktop commands as safe `2d` aliases.
Unknown profile text is rejected rather than passed to systemd. `RESIZE` is reserved and reports
`display-unavailable` in this headless version.
