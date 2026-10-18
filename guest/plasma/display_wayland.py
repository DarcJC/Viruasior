"""Bounded KScreen control for the current droid Wayland session."""
from decimal import Decimal, ROUND_HALF_UP
import json
import math
import re
import subprocess

from display_limits import valid_rate


class WaylandDisplay:
    def __init__(self, environment):
        self.environment = environment

    @staticmethod
    def parse_configs(text):
        try:
            data, end = json.JSONDecoder().raw_decode(text.lstrip())
            trailer = text.lstrip()[end:].strip()
            if trailer and not re.match(r'(?:\x1b\[[0-9;]*m)*Output: ', trailer):
                raise ValueError('unexpected-kscreen-output')
            outputs = [item for item in data['outputs'] if item['connected'] is True]
            if len(outputs) != 1:
                raise ValueError('ambiguous-output')
            output = outputs[0]
            output_id = output['id']
            if (type(output_id) is not int or not 1 <= output_id <= 65535 or
                    output['enabled'] is not True or output['scale'] != 1 or
                    output['rotation'] != 1 or output['pos'] != {'x': 0, 'y': 0}):
                raise ValueError('unsupported-output')
            modes, ids, current = {}, set(), None
            if len(output['modes']) > 256:
                raise ValueError('too-many-modes')
            for mode in output['modes']:
                identifier = mode['id']
                if (not isinstance(identifier, str) or not re.fullmatch(r'[0-9]{1,6}', identifier) or
                        identifier in ids):
                    raise ValueError('invalid-mode-id')
                ids.add(identifier)
                width, height = mode['size']['width'], mode['size']['height']
                rate = mode['refreshRate']
                if (type(width) is not int or type(height) is not int or
                        type(rate) not in (int, float) or not math.isfinite(rate)):
                    raise ValueError('invalid-mode')
                if not 10 <= rate <= 120.25:
                    continue
                millihz = int((Decimal(str(rate)) * 1000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
                if not valid_rate(width, height, millihz):
                    continue
                key = (width, height, millihz)
                if key in modes:
                    raise ValueError('ambiguous-mode-rate')
                modes[key] = identifier
                if identifier == output['currentModeId']:
                    current = key
            if current is None or len(modes) > 64:
                raise ValueError('current-rate-unavailable-or-too-many-modes')
            return output_id, current, modes
        except (KeyError, TypeError, OverflowError) as error:
            raise ValueError('invalid-kscreen-config') from error

    def run(self, arguments):
        result = subprocess.run(['/usr/bin/kscreen-doctor', *arguments], env=self.environment,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                timeout=3, check=True, encoding='utf-8')
        if len(result.stdout) > 65536:
            raise ValueError('mode-list-too-large')
        return result.stdout

    def query_configs(self):
        # In paired libkscreen 5.27, showJson leaves the static QTextStream
        # buffered until after QGuiApplication teardown, intermittently aborting
        # in QTextStream::~QTextStream. showOutputs ends with Qt::endl, flushing
        # the same stream while Qt is alive. Read only the preceding JSON object;
        # the human-readable listing rounds refresh rates and is not mode data.
        return self.parse_configs(self.run(['-j', '-o']))

    def query(self):
        output, current, modes = self.query_configs()
        return output, current[:2], {key[:2]: identifier for key, identifier in modes.items()}

    def resize_rate(self, width, height, millihz):
        output, old, modes = self.query_configs()
        target = (width, height, millihz)
        if target not in modes:
            raise ValueError('unsupported-mode-rate')
        if target == old:
            return old

        def select(mode):
            self.run([f'output.{output}.mode.{modes[mode]}'])

        try:
            select(target)
            actual_output, actual, _ = self.query_configs()
            if actual_output != output or actual != target:
                raise RuntimeError('mode-rate-verification-failed')
            return actual
        except (subprocess.SubprocessError, ValueError, RuntimeError, OSError):
            select(old)
            raise

    def resize(self, width, height):
        _, _, modes = self.query_configs()
        rates = [rate for w, h, rate in modes if (w, h) == (width, height)]
        if not rates:
            raise ValueError('unsupported-mode')
        return self.resize_rate(width, height, max(rates))[:2]
