#!/usr/bin/env python3
"""Replace only a prepared bundle's rootfs, keeping its original ZIP as recovery."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import zipfile


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(8 * 1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def repack(bundle, image):
    bundle, image = bundle.resolve(strict=True), image.resolve(strict=True)
    backup = bundle.with_name(bundle.stem + ".pre-cjk.zip")
    index = 1
    while backup.exists():
        backup = bundle.with_name(bundle.stem + f".pre-cjk.{index}.zip")
        index += 1
    temporary = bundle.with_name(bundle.name + ".cjk.tmp")
    assert not temporary.exists(), "Review the incomplete prepared bundle first"
    with zipfile.ZipFile(bundle) as old:
        manifest = json.loads(old.read("vm.json"))
        name = manifest["rootfs"]
        assert pathlib.PurePosixPath(name).name == name
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3, allowZip64=True) as new:
            for entry in old.infolist():
                if entry.filename == name:
                    new.write(image, name)
                else:
                    with old.open(entry) as source, new.open(entry, "w", force_zip64=True) as destination:
                        shutil.copyfileobj(source, destination, 1024 * 1024)
    with zipfile.ZipFile(temporary) as checked:
        assert checked.testzip() is None
        assert checked.getinfo(name).file_size == image.stat().st_size
    os.replace(bundle, backup)
    os.replace(temporary, bundle)
    values = {name: digest(image), bundle.name: digest(bundle)}
    sums = bundle.parent / "SHA256SUMS"
    lines = sums.read_text().splitlines() if sums.exists() else []
    lines = [line for line in lines if line.split()[-1].lstrip("*") not in values]
    lines += [f"{value}  {key}" for key, value in values.items()]
    sums.write_text("\n".join(lines)+"\n")
    print(f"VERIFIED {bundle} bytes={bundle.stat().st_size} sha256={values[bundle.name]}", flush=True)
    print(f"RECOVERY {backup}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True, type=pathlib.Path)
    parser.add_argument("--image", required=True, type=pathlib.Path)
    args = parser.parse_args()
    repack(args.bundle, args.image)
