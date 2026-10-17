#!/usr/bin/env python3
"""Rootless, app-private Vtest diagnosis. Never an AVF/Surface acceptance result."""
import importlib.util
import os
import pathlib
import stat
import sys

BASE = pathlib.Path('/data/user/0/pro.darc.viruaisor/files/venus-linux-diag')


def run():
    uid = os.geteuid()
    if uid < 10000 or BASE.stat().st_uid != uid:
        raise RuntimeError('diagnostic must run as the owning ordinary app UID')
    socket = BASE / 'venus.sock'
    info = socket.stat()
    if info.st_uid != uid or not stat.S_ISSOCK(info.st_mode):
        raise RuntimeError('expected an app-owned private Vtest socket')
    required = {
        'VN_DEBUG': 'vtest',
        'VTEST_SOCKET_NAME': str(socket),
        'VK_DRIVER_FILES': str(BASE / 'icd.json'),
        'VK_ICD_FILENAMES': str(BASE / 'icd.json'),
        'MESA_LOADER_DRIVER_OVERRIDE': 'zink',
        'GALLIUM_DRIVER': 'zink',
        'LIBGL_ALWAYS_SOFTWARE': 'false',
        'LIBGL_DRIVERS_PATH': str(BASE / 'lib/dri'),
        '__EGL_VENDOR_LIBRARY_FILENAMES': str(BASE / 'egl.json'),
    }
    if any(os.environ.get(key) != value for key, value in required.items()):
        raise RuntimeError('explicit app-private Venus/Vtest/Zink environment required')
    print(f'VTEST_DIAGNOSTIC_UID={uid} TRANSPORT=vtest NO_AVF_SURFACE_ACCEPTANCE', flush=True)
    spec = importlib.util.spec_from_file_location('guest_draw', BASE / 'guest-gpu-draw-probe.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.draw('VTEST_DIAGNOSTIC_DRAW_PASS (not AVF or Android Surface presentation)')


if __name__ == '__main__':
    try:
        run()
    except Exception as error:
        print('VTEST_DIAGNOSTIC_DRAW_FAIL: ' + str(error), file=sys.stderr, flush=True)
        sys.exit(1)
