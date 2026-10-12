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

`avf-desktop.service` has no `[Install]` section and therefore cannot be enabled by a target. The VM
kernel command line blacklists `drm` and `virtio_gpu` during automatic module discovery. Only an
explicit `START_DESKTOP` command starts this unit; its `ExecStartPre` loads `virtio_gpu`, and then
it starts the existing Weston user unit. Do not install a shell-profile or udev desktop trigger.

Each accepted connection begins with `READY`. The daemon handles `SHUTDOWN`, `START_DESKTOP`, and
`STOP_DESKTOP`. `RESIZE` is reserved and reports `display-unavailable` in this headless version.
