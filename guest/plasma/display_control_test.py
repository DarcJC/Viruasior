import importlib.util
import pathlib
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("display_control", pathlib.Path(__file__).with_name("display-control.py"))
display = importlib.util.module_from_spec(spec)
spec.loader.exec_module(display)

XRANDR = """Screen 0: minimum 16 x 16, current 1280 x 720, maximum 16384 x 16384
Virtual-1 connected primary 1280x720+0+0 (normal left inverted right x axis y axis)
   1920x1080     60.00 +
   1280x720      60.00*
   1024x768      60.00
   7680x4320     60.00
Virtual-2 disconnected (normal left inverted right x axis y axis)
"""

class DisplayControlTest(unittest.TestCase):
    def test_query_uses_real_modes_with_budget_and_current_size(self):
        output, current, modes = display.parse_modes(XRANDR)
        self.assertEqual("Virtual-1", output)
        self.assertEqual((1280, 720), current)
        self.assertEqual({(1920,1080), (1280,720), (1024,768)}, set(modes))

    def test_ambiguous_outputs_and_missing_current_mode_are_rejected(self):
        for text in (XRANDR.replace("Virtual-2 disconnected", "Virtual-2 connected"), XRANDR.replace("60.00*", "60.00"), ""):
            with self.assertRaises(ValueError):
                display.parse_modes(text)

    def test_unsupported_size_never_invokes_modeset(self):
        with patch.object(display, "query", return_value=display.parse_modes(XRANDR)), patch.object(display, "run_xrandr") as run:
            with self.assertRaises(ValueError):
                display.resize(2000, 1000)
            run.assert_not_called()

    def test_failed_verification_rolls_back_to_previous_mode(self):
        before = display.parse_modes(XRANDR)
        with patch.object(display, "query", side_effect=[before, before]), patch.object(display, "run_xrandr") as run:
            with self.assertRaises(RuntimeError):
                display.resize(1024, 768)
            self.assertEqual(["--output", "Virtual-1", "--mode", "1280x720"], run.call_args_list[-1].args[0])

    def test_success_reports_actual_size(self):
        after = XRANDR.replace("60.00*", "60.00").replace("60.00\n   7680", "60.00*\n   7680")
        with patch.object(display, "query", side_effect=[display.parse_modes(XRANDR), display.parse_modes(after)]), patch.object(display, "run_xrandr"):
            self.assertEqual((1024,768), display.resize(1024,768))

if __name__ == "__main__":
    unittest.main()
