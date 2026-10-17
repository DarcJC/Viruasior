#!/usr/bin/env python3
"""Explicit, local 30-second guest input check. Does not grab devices or change input policy."""
import collections
import glob
import json
import os
import select
import struct
import time

event = struct.Struct("<qqHHi")
devices = {}
pending = {}
counts = collections.Counter()
path = os.path.expanduser("~/viruaisor-input-check.jsonl")
try:
    for name in glob.glob("/dev/input/event*"):
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NONBLOCK | os.O_CLOEXEC)
            devices[fd] = name
            pending[fd] = bytearray()
        except PermissionError:
            print("Cannot read " + name, flush=True)
    if not devices:
        raise SystemExit("No readable guest input devices")
    print("For 30 seconds: press ESC twice, move/click/scroll the touchpad, touch/drag the screen, then test the exit chord or Home. No passwords during this check.", flush=True)
    with open(path, "w", buffering=1) as output:
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            ready, _, _ = select.select(list(devices), [], [], 0.25)
            for fd in ready:
                try:
                    data = os.read(fd, event.size * 64)
                except BlockingIOError:
                    continue
                if not data:
                    os.close(fd)
                    del devices[fd]
                    continue
                pending[fd].extend(data)
                while len(pending[fd]) >= event.size:
                    seconds, micros, kind, code, value = event.unpack(pending[fd][:event.size])
                    del pending[fd][:event.size]
                    if kind == 0:
                        continue
                    counts[(kind, code, value)] += 1
                    output.write(json.dumps({"device": devices[fd], "type": kind, "code": code, "value": value}) + "\n")
        summary = {"escDown": counts[(1, 1, 1)], "escUp": counts[(1, 1, 0)],
                   "relativeEvents": sum(n for (kind, _, _), n in counts.items() if kind == 2),
                   "absoluteEvents": sum(n for (kind, _, _), n in counts.items() if kind == 3)}
        output.write(json.dumps({"summary": summary}) + "\n")
        print(json.dumps(summary, indent=2), flush=True)
        print("Saved locally: " + path, flush=True)
finally:
    for fd in devices:
        os.close(fd)
