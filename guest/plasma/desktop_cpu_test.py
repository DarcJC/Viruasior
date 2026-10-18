import unittest
from desktop_cpu import fastest_cpus


class DesktopCpuTest(unittest.TestCase):
    def test_heterogeneous_tablet_uses_fastest_tier(self):
        capacities = dict(enumerate([663] * 4 + [975] * 4 + [1024] * 2))
        self.assertEqual({8, 9}, fastest_cpus(set(range(10)), capacities))

    def test_respects_existing_affinity_and_keeps_two_cpus(self):
        self.assertEqual({1, 2, 3}, fastest_cpus({0, 1, 2, 3}, {0: 400, 1: 800, 2: 800, 3: 1024}))
        self.assertEqual({0, 1}, fastest_cpus({0, 1}, {0: 400, 1: 1024, 2: 1024}))

    def test_homogeneous_or_unknown_capacity_preserves_allowed_cpus(self):
        allowed = {0, 2, 4}
        for capacities in ({0: 1024, 2: 1024, 4: 1024}, {}, {0: 1024, 2: 0, 4: 800}):
            self.assertEqual(allowed, fastest_cpus(allowed, capacities))


if __name__ == '__main__':
    unittest.main()
