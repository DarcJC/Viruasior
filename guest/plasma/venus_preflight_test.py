"""Replay the cold-start zero readback without requiring a GPU on the test host."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[2]
PROBE = REPO / "scripts/guest-gpu-draw-probe.py"
spec = importlib.util.spec_from_file_location("draw_probe", PROBE)
probe = importlib.util.module_from_spec(spec)
with patch.object(sys, "argv", [str(PROBE), "--venus"]):
    spec.loader.exec_module(probe)


class ReadbackTest(unittest.TestCase):
    def validate(self, pixels):
        probe.validate_readback(pixels)

    def test_real_gradient_and_rounding_tolerance_pass(self):
        self.validate([(12, 12, 128, 255), (131, 131, 128, 255), (243, 243, 128, 255)])
        self.validate([(10, 14, 126, 253), (133, 129, 130, 255), (241, 245, 128, 255)])

    def test_only_three_all_zero_samples_are_retryable(self):
        with self.assertRaises(probe.ReadbackNotReady):
            self.validate([(0, 0, 0, 0)] * 3)

    def test_other_bad_pixels_remain_permanent_failures(self):
        for pixels in (
            [(0, 0, 0, 255)] * 3,
            [(0, 0, 0, 0), (131, 131, 128, 255), (243, 243, 128, 255)],
            [(12, 12, 128, 255)] * 3,
            [(0, 0, 0, 0)] * 2,
            [(0, 0, 0)] * 3,
        ):
            with self.subTest(pixels=pixels):
                with self.assertRaises(RuntimeError) as caught:
                    self.validate(pixels)
                self.assertNotIsInstance(caught.exception, probe.ReadbackNotReady)

    def test_not_ready_and_permanent_errors_have_distinct_exit_codes(self):
        for error, status, label in (
            (probe.ReadbackNotReady("zero readback"), 75, "GPU_DRAW_READBACK_NOT_READY"),
            (RuntimeError("GL error"), 1, "GPU_DRAW_READBACK_FAIL"),
        ):
            with self.subTest(status=status), patch.object(probe, "run", side_effect=error):
                output = io.StringIO()
                with contextlib.redirect_stderr(output):
                    self.assertEqual(probe.main(), status)
                self.assertIn(label, output.getvalue())


class VenusPreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "renderD128").touch()
        self.program("id", 'echo 1000\n')
        self.program("vulkaninfo", 'cat "$FIXTURE/report"\n')
        self.program("sleep", 'echo delay >> "$FIXTURE/delays"\n')
        self.program("python3", '''test "$1" = "$DRAW_PROBE" && test "$2" = --venus || exit 99
echo probe >> "$FIXTURE/calls"
status=$(head -n 1 "$FIXTURE/results")
sed -i '1d' "$FIXTURE/results"
case "$status" in
    0) echo GPU_DRAW_READBACK_PASS ;;
    75) echo 'GPU_DRAW_READBACK_NOT_READY: all three samples are zero' >&2 ;;
    *) echo GPU_DRAW_READBACK_FAIL >&2 ;;
esac
exit "$status"
''')
        source = (REPO / "guest/plasma/venus-preflight").read_text()
        # Run the production shell program, substituting only the platform device path.
        (self.root / "preflight").write_text(source.replace(
            "/dev/dri/renderD128", str(self.root / "renderD128")))

    def program(self, name, source):
        path = self.root / name
        path.write_text("#!/bin/sh\nset -eu\n" + source)
        path.chmod(0o755)

    def run_preflight(self, results, report="driverID = MESA_VENUS\ndeviceName = Mali-G2\n"):
        (self.root / "report").write_text(report)
        (self.root / "results").write_text("\n".join(map(str, results)) + "\n")
        env = dict(os.environ, PATH=str(self.root) + ":" + os.environ["PATH"],
                   FIXTURE=str(self.root), XDG_RUNTIME_DIR=str(self.root),
                   DRAW_PROBE="/opt/viruaisor/guest-gpu-draw-probe.py")
        result = subprocess.run(["sh", str(self.root / "preflight")], env=env,
                                text=True, capture_output=True, timeout=5)
        def count(name):
            path = self.root / name
            return len(path.read_text().splitlines()) if path.exists() else 0
        return result, count("calls"), count("delays")

    def test_zero_readback_then_real_pixels_starts_desktop(self):
        result, calls, delays = self.run_preflight([75, 0])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((calls, delays), (2, 1))
        self.assertIn("GPU_DRAW_READBACK_PASS", result.stdout)

    def test_success_does_not_delay_start(self):
        result, calls, delays = self.run_preflight([0])
        self.assertEqual((result.returncode, calls, delays), (0, 1, 0))

    def test_persistent_zero_readback_fails_after_three_attempts(self):
        result, calls, delays = self.run_preflight([75, 75, 75, 0])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((calls, delays), (3, 2))
        self.assertIn("GPU_DRAW_READBACK_FAIL", result.stderr)

    def test_real_draw_error_is_not_retried(self):
        result, calls, delays = self.run_preflight([1, 0])
        self.assertEqual((result.returncode, calls, delays), (1, 1, 0))

    def test_software_renderer_is_rejected_before_drawing(self):
        result, calls, delays = self.run_preflight([0],
            "driverID = MESA_VENUS\ndeviceName = Mali-G2 llvmpipe\n")
        self.assertEqual((result.returncode, calls, delays), (1, 0, 0))


if __name__ == "__main__":
    unittest.main()
