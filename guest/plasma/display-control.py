#!/usr/bin/python3
"""Bounded XRandR control, run as the fixed desktop user by avf-agent."""
import os
import re
import subprocess
import sys

MAX_PIXELS = 4_194_304


def valid_size(width, height):
    return 1 <= width <= 4096 and 1 <= height <= 4096 and width * height <= MAX_PIXELS


def parse_modes(text):
    connected = re.findall(r"^(\S+) connected(?:\s|$)", text, re.M)
    if len(connected) != 1:
        raise ValueError("ambiguous-output")
    output = connected[0]
    section = text.split(output + " connected", 1)[1]
    section = re.split(r"\n\S", section, maxsplit=1)[0]
    modes = {}
    current = None
    for line in section.splitlines():
        match = re.match(r"\s+(\d+)x(\d+)\s+(.+)$", line)
        if not match:
            continue
        size = (int(match[1]), int(match[2]))
        if valid_size(*size):
            modes[size] = f"{size[0]}x{size[1]}"
            if "*" in match[3]:
                if current is not None and current != size:
                    raise ValueError("ambiguous-current-mode")
                current = size
    if current is None:
        raise ValueError("current-mode-unavailable")
    return output, current, modes


def run_xrandr(arguments):
    environment = dict(os.environ, DISPLAY=":0", XAUTHORITY="/home/droid/.Xauthority", LC_ALL="C")
    result = subprocess.run(["/usr/bin/xrandr", *arguments], env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            timeout=3, check=True, encoding="utf-8")
    if len(result.stdout) > 65536:
        raise ValueError("mode-list-too-large")
    return result.stdout


def query():
    return parse_modes(run_xrandr(["--query"]))


def resize(width, height):
    output, old, modes = query()
    target = (width, height)
    if not valid_size(width, height) or target not in modes:
        raise ValueError("unsupported-mode")
    if target == old:
        return old
    try:
        run_xrandr(["--output", output, "--mode", modes[target]])
        actual_output, actual, _ = query()
        if actual_output != output or actual != target:
            raise RuntimeError("mode-verification-failed")
        return actual
    except (subprocess.SubprocessError, ValueError, RuntimeError):
        # A failed mode change must not silently leave input using the requested size.
        run_xrandr(["--output", output, "--mode", modes[old]])
        raise


def main(arguments):
    if arguments == ["QUERY"]:
        _, current, modes = query()
        if len(modes) > 64:
            raise ValueError("too-many-modes")
        return "OK MODES " + " ".join(str(n) for size in [current, *modes] for n in size)
    if len(arguments) == 3 and arguments[0] == "RESIZE" and all(re.fullmatch(r"[0-9]{1,4}", v) for v in arguments[1:]):
        actual = resize(int(arguments[1]), int(arguments[2]))
        return f"OK SIZE {actual[0]} {actual[1]}"
    raise ValueError("invalid-display-command")


if __name__ == "__main__":
    try:
        print(main(sys.argv[1:]))
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError):
        print("ERROR display-control")
        sys.exit(1)
