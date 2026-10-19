import os
import unittest
from unittest.mock import patch
from desktop_cpu import desktop_cpus, main


class DesktopCpuTest(unittest.TestCase):
    def test_three_tier_tablet_avoids_prime_cores(self):
        capacities = dict(enumerate([663] * 4 + [975] * 4 + [1024] * 2))
        self.assertEqual({4, 5, 6, 7}, desktop_cpus(set(range(10)), capacities))
        self.assertEqual({4, 5, 6, 7}, desktop_cpus(set(range(4, 10)), capacities))

    def test_two_tier_big_little_uses_big_cores(self):
        capacities = {0: 400, 1: 400, 2: 1024, 3: 1024}
        self.assertEqual({2, 3}, desktop_cpus(set(capacities), capacities))

    def test_respects_existing_affinity_and_keeps_two_cpus(self):
        self.assertEqual({1, 2}, desktop_cpus({0, 1, 2, 3}, {0: 400, 1: 800, 2: 800, 3: 1024}))
        self.assertEqual({0, 1}, desktop_cpus({0, 1}, {0: 400, 1: 1024, 2: 1024}))

    def test_homogeneous_or_unknown_capacity_preserves_allowed_cpus(self):
        allowed = {0, 2, 4}
        for capacities in ({0: 1024, 2: 1024, 4: 1024}, {}, {0: 1024, 2: 0, 4: 800}):
            self.assertEqual(allowed, desktop_cpus(allowed, capacities))

    def test_display_exec_owns_sync_fd_opt_in_despite_inherited_environment(self):
        key = 'VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID'
        for wrapper, program in (('desktop-xorg', '/usr/bin/Xorg'),
                                 ('desktop-kwin', '/usr/bin/kwin_x11')):
            with self.subTest(wrapper=wrapper), \
                    patch.dict(os.environ, {key: 'inherited-parent'}), \
                    patch('desktop_cpu.sys.argv', [wrapper, '--version']), \
                    patch('desktop_cpu.os.sched_getaffinity', return_value={4, 5}), \
                    patch('desktop_cpu.Path.glob', return_value=[]), \
                    patch('desktop_cpu.Path.is_file', return_value=False), \
                    patch('desktop_cpu.os.execv') as execute:
                main()
                self.assertEqual(str(os.getpid()), os.environ[key])
                execute.assert_called_once_with(program, [program, '--version'])

    def test_private_xorg_enables_clock_only_for_its_own_exec(self):
        clock_key = 'VIRUAISOR_X11_SOFTWARE_CLOCK'
        for wrapper, installed, executable, expected in (
                ('desktop-xorg', True, True, '/opt/viruaisor/xorg/Xorg'),
                ('desktop-xorg', False, True, '/usr/bin/Xorg'),
                ('desktop-xorg', True, False, '/usr/bin/Xorg'),
                ('desktop-kwin', True, True, '/usr/bin/kwin_x11')):
            with self.subTest(wrapper=wrapper, installed=installed, executable=executable), \
                    patch.dict(os.environ, {clock_key: 'inherited-parent'}), \
                    patch('desktop_cpu.sys.argv', [wrapper, ':0', '-nolisten', 'tcp']), \
                    patch('desktop_cpu.Path.is_file', return_value=installed), \
                    patch('desktop_cpu.os.access', return_value=executable), \
                    patch('desktop_cpu.os.sched_getaffinity', return_value={4, 5}), \
                    patch('desktop_cpu.Path.glob', return_value=[]), \
                    patch('desktop_cpu.os.execv') as execute:
                main()
                execute.assert_called_once_with(expected, [expected, ':0', '-nolisten', 'tcp'])
                if expected == '/opt/viruaisor/xorg/Xorg':
                    self.assertEqual('1', os.environ.get(clock_key))
                else:
                    self.assertIsNone(os.environ.get(clock_key))


if __name__ == '__main__':
    unittest.main()
