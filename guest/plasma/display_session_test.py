import importlib
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch


class DisplaySessionTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(Path(__file__).with_name('display_session.py').exists(), 'Wayland session handoff is not implemented')
        self.module = importlib.import_module('display_session')
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.runtime = Path(self.directory.name)
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.bind(str(self.runtime / 'wayland-0'))
        self.addCleanup(self.socket.close)
        self.identity = (os.getuid(), '12345', 'kwin_wayland')
        self.env = {'XDG_RUNTIME_DIR': str(self.runtime), 'WAYLAND_DISPLAY': 'wayland-0',
                    'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/tmp/dbus-test',
                    'LD_LIBRARY_PATH': '/opt/viruaisor/zink25/lib', 'UNRELATED_SECRET': 'not-copied'}
        self.path = self.runtime / self.module.SESSION_NAME

    def publish(self):
        with patch.object(self.module, 'process_identity', return_value=self.identity):
            self.module.publish_wayland_environment(123, self.env, self.runtime)

    def load(self):
        with patch.object(self.module, 'process_identity', return_value=self.identity):
            return self.module.load_wayland_environment(self.runtime)

    def test_handoff_round_trip_keeps_only_graphics_environment(self):
        self.publish()
        env = self.load()
        self.assertEqual('/opt/viruaisor/zink25/lib', env['LD_LIBRARY_PATH'])
        self.assertEqual('wayland', env['QT_QPA_PLATFORM'])
        self.assertEqual('wayland', env['XDG_SESSION_TYPE'])
        self.assertNotIn('UNRELATED_SECRET', env)
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_publish_accepts_process_environment_mapping(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(self.module, 'process_identity', return_value=self.identity):
            self.module.publish_wayland_environment(123, os.environ, self.runtime)
        self.assertEqual('wayland-0', self.load()['WAYLAND_DISPLAY'])

    def test_missing_session_allows_x11_but_corrupt_session_fails(self):
        self.assertIsNone(self.load())
        self.path.write_text('{broken')
        self.path.chmod(0o600)
        with self.assertRaises(ValueError): self.load()

    def test_reused_pid_is_rejected(self):
        self.publish()
        self.identity = (os.getuid(), '12346', 'kwin_wayland')
        with self.assertRaises(ValueError): self.load()

    def test_symlink_public_file_and_oversized_file_are_rejected(self):
        self.publish()
        self.path.chmod(0o644)
        with self.assertRaises(ValueError): self.load()
        self.path.unlink()
        self.path.symlink_to(self.runtime / 'missing')
        with self.assertRaises(OSError): self.load()
        self.path.unlink()
        self.path.write_text(' ' * 16385)
        self.path.chmod(0o600)
        with self.assertRaises(ValueError): self.load()

    def test_non_socket_and_outside_runtime_socket_are_rejected(self):
        self.publish()
        (self.runtime / 'wayland-0').unlink()
        (self.runtime / 'wayland-0').write_text('not a socket')
        with self.assertRaises(ValueError): self.load()
        self.env['WAYLAND_DISPLAY'] = '../wayland-0'
        with self.assertRaises(ValueError): self.publish()

    def test_non_kwin_process_cannot_publish(self):
        self.identity = (os.getuid(), '12345', 'unrelated')
        with self.assertRaises(ValueError): self.publish()

    def test_process_identity_reads_current_process(self):
        identity = self.module.process_identity(os.getpid())
        self.assertEqual(os.getuid(), identity[0])
        self.assertGreater(int(identity[1]), 0)


if __name__ == '__main__':
    unittest.main()
