# Software Qt animation cadence

The paired Plasma session uses `QT_QUICK_BACKEND=software` with Qt's basic
render loop. Its default animation timer fires every 16 ms, even on a 120 Hz
screen. A device regression test confirms about 312–313 rendered QML frames
per five seconds and about 62.4 actual displayed FPS at 1920×1200.

This QPA generic plugin adjusts the GUI thread's `QUnifiedTimer` interval to
the current primary screen refresh rate. Qt 5 accepts integer milliseconds,
so it uses 8 ms at 120 Hz and 16 ms at 60 Hz. Qt's existing wall clock still
determines animation speed; the plugin does not double playback speed.
`QScreen::refreshRateChanged` also updates already-open clients when the
resolution changes. Qt restarts a running timer while preserving elapsed
animation time, and leaves it stopped when no animation needs it.

It applies only to X11 software Qt Quick with the basic render loop. GPU
backends, the software threaded loop, and explicit fixed-step diagnostics
are left alone. KWin's OpenGL compositing, Mesa, GPU fences, core affinity,
and Android settings are unchanged. Generic plugins load in Qt GUI clients;
there is no preload library or replacement of the distro Qt binaries.

The implementation uses Qt 5's private `QUnifiedTimer` interface. Rebuild and
repeat the device regression checks when changing the paired Qt package.
Relevant upstream source:

- [Default timer and setTimingInterval](https://github.com/qt/qtbase/blob/v5.15.12-lts-lgpl/src/corelib/animation/qabstractanimation.cpp)
- [Software backend loop selection](https://github.com/qt/qtdeclarative/blob/v5.15.12-lts-lgpl/src/quick/scenegraph/adaptations/software/qsgsoftwareadaptation.cpp)
- [QPA generic plugin loading](https://github.com/qt/qtbase/blob/v5.15.12-lts-lgpl/src/gui/kernel/qguiapplication.cpp)

## Build and validation

`bash scripts/build-qt-software-refresh.sh` builds against the paired Qt
5.15.12 sysroot. It invokes target `moc` through explicit `qemu-aarch64` and
uses the existing AArch64 cross compiler; it installs no host packages or
binfmt handlers. Put the resulting library in a private `plugins/generic`
directory and opt in with:

```sh
QT_PLUGIN_PATH=/private/path/plugins \
QT_QPA_GENERIC_PLUGINS=viruaisor-software-refresh \
QT_QUICK_BACKEND=software QSG_INFO=1 QT_FORCE_STDERR_LOGGING=1 \
  qmlscene /path/to/guest/xorg/gpu-motion.qml
```

Leave `QSG_RENDER_LOOP` unset. After at least 22 seconds, capture the client's
cadence log. The same command without the generic-plugin environment gives
the control. Check the software backend and animation speed/rate with:

```sh
node guest/plasma/qt-software-refresh/check-cadence.cjs 120 wide.log
node guest/plasma/qt-software-refresh/check-cadence.cjs 60 native.log restored.log
```

Also keep one client alive through native 60 Hz → wide 120 Hz → native 60 Hz;
verify unchanged PID, 16 → 8 → 16 ms log messages, and the current geometry.
The check allows 10% cadence tolerance and is **not** a certification of
stable displayed 120 FPS. Measure actual SurfaceFlinger frame timestamps,
visible motion and sampling coverage separately.

Run `qmlscene lifecycle.qml` with the same plugin environment as well: it
must close itself with exit code zero. Generic-plugin instances are owned by
Qt's plugin list and must not also be parented to the application. This test
detected a double deletion during clean shutdown in the first prototype.
