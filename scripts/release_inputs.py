#!/usr/bin/env python3
"""Export and restore the four reviewed binary inputs, never a local workspace."""

import argparse
import hashlib
import shutil
import sys
import zipfile
from pathlib import Path

from release_tools import sha256_file


PINS = {
    "vmlinuz": "1008c4aa740113c3bccd1aa72376bcd23602a2f94aa170d51617b7089c647343",
    "initrd.img": "39b4de660fc6d7a965265164bfc4ca71662032d97a7d43ef25a905ead7c2c475",
    "libvenus_crosvm.so": "a4a71002533174408d0acdbfe3d4980482087bc9a29d0428ac423579bce9029b",
    "libvenus_desktop_crosvm.so": "7232890999d06ab07784f9f3a68b06a2ea6a47ec1e8ae98e44fc011379296ff2",
}


def pack_inputs(image_dir: Path, native_dir: Path, archive: Path) -> None:
    files = {name: (native_dir if name.endswith(".so") else image_dir) / name
             for name in PINS}
    for name, source in files.items():
        if sha256_file(source) != PINS[name]:
            raise ValueError(f"SHA-256 differs from reviewed input: {name}")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as package:
        for name, source in files.items():
            # Fixed metadata makes the archive reproducible across host filesystems.
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            package.writestr(info, source.read_bytes())
    digest = sha256_file(archive)
    archive.with_suffix(".zip.sha256").write_text(
        f"{digest}  {archive.name}\n", encoding="utf-8")
    print(f"{digest}  {archive}")


def unpack_inputs(archive: Path, output: Path) -> None:
    if output.exists():
        raise ValueError(f"Input output directory must be new: {output}")
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        if len(names) != len(PINS) or set(names) != set(PINS):
            raise ValueError("Input archive entries must be exactly the four reviewed files")
        # Validate everything before creating output or executing any input.
        for name, expected in PINS.items():
            digest = hashlib.sha256()
            with package.open(name) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != expected:
                raise ValueError(f"SHA-256 differs from reviewed input: {name}")
        output.mkdir(parents=True)
        for name in PINS:
            with package.open(name) as source, (output / name).open("xb") as target:
                shutil.copyfileobj(source, target)


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    pack = commands.add_parser("pack")
    pack.add_argument("image_dir", type=Path)
    pack.add_argument("native_dir", type=Path)
    pack.add_argument("archive", type=Path)
    unpack = commands.add_parser("unpack")
    unpack.add_argument("archive", type=Path)
    unpack.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "pack":
        pack_inputs(args.image_dir, args.native_dir, args.archive)
    else:
        unpack_inputs(args.archive, args.output)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f"Release input validation failed: {error}", file=sys.stderr)
        sys.exit(1)
