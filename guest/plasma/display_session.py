"""Private, process-bound environment handoff to the fixed-user display helper.

This is session state, not a privileged interface: both publisher and reader
run as droid. Never source it as shell code or use it from the root agent.
"""
import json
import os
from collections.abc import Mapping
from pathlib import Path
import re
import stat
import tempfile

SESSION_NAME = 'viruaisor-display-session.json'
GRAPHICS_ENV = (
    'MESA_LOADER_DRIVER_OVERRIDE', 'GALLIUM_DRIVER', 'LIBGL_ALWAYS_SOFTWARE',
    'VIRUAISOR_ZINK_DISABLE_DRM_MODIFIERS', 'VIRUAISOR_ZINK_SMALL_UPLOAD_ALLOCATIONS',
    'VK_DRIVER_FILES', 'VK_ICD_FILENAMES', 'VN_PERF', 'LD_LIBRARY_PATH',
    'LIBGL_DRIVERS_PATH', '__EGL_VENDOR_LIBRARY_FILENAMES',
)


def process_identity(pid):
    if type(pid) is not int or not 1 <= pid <= 2**31 - 1:
        raise ValueError('invalid-session-process')
    root = Path('/proc') / str(pid)
    text = (root / 'stat').read_text()
    # comm may itself contain spaces and parentheses. Fields after its last ')'
    # begin at field 3; starttime is field 22.
    tail = text.rsplit(')', 1)[1].split()
    return root.stat().st_uid, tail[19], (root / 'comm').read_text().strip()


def session_environment(environment, runtime):
    if not isinstance(environment, Mapping):
        raise ValueError('invalid-session-environment')
    runtime = Path(runtime)
    info = runtime.lstat()
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077):
        raise ValueError('invalid-session-directory')
    name = environment.get('WAYLAND_DISPLAY')
    bus = environment.get('DBUS_SESSION_BUS_ADDRESS')
    if (not isinstance(name, str) or not re.fullmatch(r'wayland-[0-9]{1,4}', name) or
            environment.get('XDG_RUNTIME_DIR') != str(runtime) or
            not isinstance(bus, str) or not bus.startswith('unix:')):
        raise ValueError('invalid-session-environment')
    info = (runtime / name).lstat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
        raise ValueError('invalid-session-socket')
    result = {key: environment[key] for key in GRAPHICS_ENV if key in environment}
    result.update(WAYLAND_DISPLAY=name, DBUS_SESSION_BUS_ADDRESS=bus,
                  XDG_RUNTIME_DIR=str(runtime), XDG_SESSION_TYPE='wayland',
                  QT_QPA_PLATFORM='wayland', HOME='/home/droid', PATH='/usr/bin', LC_ALL='C')
    if any(not isinstance(value, str) or not value or len(value) > 4096 or
           any(char in value for char in ('\0', '\r', '\n')) for value in result.values()):
        raise ValueError('invalid-session-value')
    return result


def load_wayland_environment(runtime=None):
    runtime = Path(runtime or f'/run/user/{os.getuid()}')
    try:
        fd = os.open(runtime / SESSION_NAME, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return None
    with os.fdopen(fd, 'r', encoding='utf-8') as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                info.st_mode & 0o077 or info.st_size > 16384):
            raise ValueError('invalid-session-file')
        text = stream.read(16385)
    if len(text) > 16384:
        raise ValueError('session-file-too-large')
    try:
        data = json.loads(text)
        uid, started, command = process_identity(data['pid'])
        if uid != os.getuid() or command != 'kwin_wayland' or started != data['start_ticks']:
            raise ValueError('stale-display-session')
        return session_environment(data['environment'], runtime)
    except (KeyError, TypeError, IndexError) as error:
        raise ValueError('invalid-display-session') from error


def publish_wayland_environment(pid, environment, runtime=None):
    runtime = Path(runtime or f'/run/user/{os.getuid()}')
    uid, started, command = process_identity(pid)
    if uid != os.getuid() or command != 'kwin_wayland':
        raise ValueError('invalid-session-owner')
    data = {'pid': pid, 'start_ticks': started,
            'environment': session_environment(environment, runtime)}
    text = json.dumps(data)
    if len(text.encode('utf-8')) > 16384:
        raise ValueError('session-file-too-large')
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=runtime,
                                     prefix='.viruaisor-display-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            stream.write(text)
            stream.close()
            os.replace(temporary, runtime / SESSION_NAME)
        finally:
            temporary.unlink(missing_ok=True)
