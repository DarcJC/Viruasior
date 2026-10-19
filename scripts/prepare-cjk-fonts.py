#!/usr/bin/env python3
"""Fetch pinned OFL fonts and verify real Unicode cmap coverage, without system installs."""
import argparse
import hashlib
import json
import pathlib
import struct
import urllib.request

REPO = pathlib.Path(__file__).resolve().parents[1]
SAMPLE = "中文测试汉字繁體網頁"


def has_glyph(data, code):
    u16 = lambda offset: struct.unpack_from(">H", data, offset)[0]
    u32 = lambda offset: struct.unpack_from(">I", data, offset)[0]
    for entry in range(u16(4)):
        offset = 12 + entry * 16
        if data[offset:offset + 4] != b"cmap":
            continue
        base = u32(offset + 8)
        for index in range(u16(base + 2)):
            record = base + 4 + index * 8
            platform, encoding = u16(record), u16(record + 2)
            if platform != 0 and not (platform == 3 and encoding in (1, 10)):
                continue
            table = base + u32(record + 4)
            kind = u16(table)
            if kind == 12:
                for group in range(u32(table + 12)):
                    start, end, glyph = struct.unpack_from(">III", data, table + 16 + group * 12)
                    if start <= code <= end and glyph + code - start:
                        return True
            elif kind == 4 and code <= 0xffff:
                count = u16(table + 6) // 2
                for segment in range(count):
                    end = u16(table + 14 + segment * 2)
                    start = u16(table + 16 + count * 2 + segment * 2)
                    if not start <= code <= end:
                        continue
                    delta = u16(table + 16 + count * 4 + segment * 2)
                    location = table + 16 + count * 6 + segment * 2
                    distance = u16(location)
                    glyph = u16(location + distance + (code - start) * 2) if distance else code
                    if glyph and (glyph + delta) & 0xffff:
                        return True
    return False


def prepare(output, verify_only=False):
    manifest = json.loads((REPO / "guest/fonts/manifest.json").read_text())
    assert manifest["version"] == 1
    output.mkdir(parents=True, exist_ok=True)
    for item in manifest["files"]:
        assert pathlib.Path(item["name"]).name == item["name"]
        target = output / item["name"]
        valid = target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]
        if not valid:
            if verify_only:
                raise RuntimeError(f"Missing or invalid pinned font: {target.name}")
            temporary = target.with_suffix(".partial")
            for attempt in range(4):
                try:
                    with urllib.request.urlopen(item["url"], timeout=55) as response, temporary.open("wb") as stream:
                        while chunk := response.read(262144):
                            stream.write(chunk)
                    if hashlib.sha256(temporary.read_bytes()).hexdigest() != item["sha256"]:
                        raise RuntimeError("SHA-256 mismatch")
                    temporary.replace(target)
                    break
                except Exception:
                    if attempt == 3:
                        raise
        if target.suffix == ".otf":
            data = target.read_bytes()
            missing = [character for character in SAMPLE if not has_glyph(data, ord(character))]
            if missing:
                raise RuntimeError(f"{target.name} lacks required glyphs: {missing}")
        print(f"VERIFIED {target.name} sha256={item['sha256']}", flush=True)
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, default=REPO / ".pi/device-debug/cjk-fonts")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    prepare(args.output, args.verify)
