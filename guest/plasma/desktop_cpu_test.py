import unittest
from desktop_cpu import desktop_cpus


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


if __name__ == '__main__':
    unittest.main()
