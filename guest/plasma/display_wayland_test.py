import copy
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('display_control', Path(__file__).with_name('display-control.py'))
display = importlib.util.module_from_spec(spec)
spec.loader.exec_module(display)

CONFIG = {'outputs': [{
    'id': 1, 'connected': True, 'enabled': True, 'currentModeId': '0',
    'scale': 1, 'rotation': 1, 'pos': {'x': 0, 'y': 0},
    'modes': [
        {'id': '0', 'size': {'width': 3408, 'height': 2272}, 'refreshRate': 59.99800109863281},
        {'id': '1', 'size': {'width': 1920, 'height': 1280}, 'refreshRate': 119.99800109863281},
        {'id': '2', 'size': {'width': 1920, 'height': 1200}, 'refreshRate': 120},
        {'id': '3', 'size': {'width': 1920, 'height': 1200}, 'refreshRate': 59.8849983215332},
        {'id': '4', 'size': {'width': 3408, 'height': 2272}, 'refreshRate': 120},
    ]}]}


class WaylandDisplayTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(hasattr(display, 'WaylandDisplay'), 'Wayland display control is not implemented')
        self.config = copy.deepcopy(CONFIG)

    def parse(self):
        return display.WaylandDisplay.parse_configs(json.dumps(self.config))

    def test_real_reported_rates_are_rounded_to_millihertz_and_budgeted(self):
        output, current, modes = self.parse()
        self.assertEqual(1, output)
        self.assertEqual((3408, 2272, 59998), current)
        self.assertEqual('1', modes[1920, 1280, 119998])
        self.assertEqual('3', modes[1920, 1200, 59885])
        self.assertNotIn((3408, 2272, 120000), modes)

    def test_json_query_accepts_listing_that_flushes_kscreen_before_qt_teardown(self):
        text = json.dumps(CONFIG) + '\n\x1b[01;32mOutput: \x1b[0;0m1 Virtual-1 enabled connected\n'
        backend = display.WaylandDisplay({})
        with patch.object(backend, 'run', return_value=text) as run:
            self.assertEqual((3408, 2272, 59998), backend.query_configs()[1])
            run.assert_called_once_with(['-j', '-o'])

    def test_unexpected_text_after_json_is_rejected(self):
        with self.assertRaises(ValueError):
            display.WaylandDisplay.parse_configs(json.dumps(CONFIG) + '\nunexpected')

    def test_ambiguous_outputs_modes_and_unrecognized_current_fail_closed(self):
        for change in ('output', 'duplicate', 'current', 'scale', 'rotation', 'position', 'disabled'):
            with self.subTest(change=change):
                self.config = copy.deepcopy(CONFIG)
                output = self.config['outputs'][0]
                if change == 'output': self.config['outputs'].append(copy.deepcopy(output))
                if change == 'duplicate': output['modes'][1]['id'] = '0'
                if change == 'current': output['currentModeId'] = '99'
                if change == 'scale': output['scale'] = 1.5
                if change == 'rotation': output['rotation'] = 2
                if change == 'position': output['pos']['x'] = 30
                if change == 'disabled': output['enabled'] = False
                with self.assertRaises(ValueError): self.parse()

    def test_mode_identifiers_cannot_supply_kscreen_commands(self):
        self.config['outputs'][0]['modes'][1]['id'] = '1 output.1.disable'
        with self.assertRaises(ValueError): self.parse()

    def test_nonfinite_refresh_or_noninteger_dimensions_are_rejected(self):
        for field, value in [('refreshRate', float('nan')), ('refreshRate', '120'), ('size', {'width': True, 'height': 1280})]:
            with self.subTest(field=field, value=value):
                self.config = copy.deepcopy(CONFIG)
                self.config['outputs'][0]['modes'][1][field] = value
                with self.assertRaises(ValueError): self.parse()

    def test_rate_change_selects_advertised_id_and_verifies_actual_output(self):
        before = self.parse()
        self.config['outputs'][0]['currentModeId'] = '2'
        after = self.parse()
        backend = display.WaylandDisplay({})
        with patch.object(backend, 'query_configs', side_effect=[before, after]), patch.object(backend, 'run') as run:
            self.assertEqual((1920, 1200, 120000), backend.resize_rate(1920, 1200, 120000))
            run.assert_called_once_with(['output.1.mode.2'])

    def test_failed_verification_rolls_back_to_original_id(self):
        before = self.parse()
        backend = display.WaylandDisplay({})
        with patch.object(backend, 'query_configs', side_effect=[before, before]), patch.object(backend, 'run') as run:
            with self.assertRaises(RuntimeError): backend.resize_rate(1920, 1200, 120000)
            self.assertEqual([['output.1.mode.2'], ['output.1.mode.0']], [call.args[0] for call in run.call_args_list])

    def test_unadvertised_rate_does_not_change_display(self):
        backend = display.WaylandDisplay({})
        with patch.object(backend, 'query_configs', return_value=self.parse()), patch.object(backend, 'run') as run:
            with self.assertRaises(ValueError): backend.resize_rate(3408, 2272, 60000)
            run.assert_not_called()

    def test_protocol_uses_wayland_backend_without_invoking_xrandr(self):
        with patch.object(display, 'load_wayland_environment', return_value={}), patch.object(display.WaylandDisplay, 'run', return_value=json.dumps(CONFIG)), patch.object(display, 'run_xrandr') as xrandr:
            response = display.main(['QUERY_RATES'])
            self.assertTrue(response.startswith('OK CONFIGS 3408 2272 59998 '), response)
            xrandr.assert_not_called()

    def test_absent_wayland_session_keeps_x11_protocol(self):
        with patch.object(display, 'load_wayland_environment', return_value=None), patch.object(display, 'query_configs', return_value=('Virtual-1', (1920, 1200, 120000), {(1920, 1200, 120000): ('1920x1200', '120')})):
            self.assertEqual('OK CONFIGS 1920 1200 120000 1920 1200 120000', display.main(['QUERY_RATES']))


if __name__ == '__main__':
    unittest.main()
