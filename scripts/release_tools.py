#!/usr/bin/env python3
"""Validate the exact assets uploaded by the tag release workflow."""

import argparse
import hashlib
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path


NATIVE_NAMES = (
    "libviruaisor_native.so", "libviruaisor_storage.so",
    "libviruaisor_storage_launcher.so", "libvenus_host_probe.so",
    "libvenus_overlay_probe.so", "libvenus_crosvm.so",
    "libvenus_desktop_crosvm.so", "librootfs_e2fsck.so",
    "librootfs_resize2fs.so", "librootfs_debugfs.so", "librootfs_copy.so",
)
MAX_ASSET_BYTES = 2 * 1024 * 1024 * 1024 - 1


def parse_version(tag: str) -> tuple[str, int]:
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Release tag must be exactly v<major>.<minor>.<patch>")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch + 1
    if minor > 999 or patch > 999 or code > 2_147_483_647:
        raise ValueError("Version components exceed the Android versionCode range")
    return ".".join(match.groups()), code


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _policy_pin(policy: str, field: str) -> str:
    match = re.search(rf'\b{field} = (?:"([a-f0-9]{{64}})"|([0-9]+)L)', policy)
    if not match:
        raise ValueError(f"Missing {field} in runtime policy")
    return match.group(1) or match.group(2)


def verify_apk(apk: Path, native_dir: Path, policy_path: Path) -> None:
    policy = policy_path.read_text(encoding="utf-8")
    with zipfile.ZipFile(apk) as package:
        names = package.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK has duplicate entries")
        for name in NATIVE_NAMES:
            source = native_dir / name
            if not source.is_file():
                raise ValueError(f"Missing built native artifact: {name}")
            entry = "lib/arm64-v8a/" + name
            if entry not in names:
                raise ValueError(f"APK does not contain {entry}")
            with package.open(entry) as stream:
                data = stream.read()
            if data[:6] != b"\x7fELF\x02\x01" or data[18:20] != b"\xb7\x00":
                raise ValueError(f"Not a 64-bit AArch64 ELF: {name}")
            digest = hashlib.sha256(data).hexdigest()
            if digest != sha256_file(source):
                raise ValueError(f"APK differs from built native artifact: {name}")
            prefix = ("DESKTOP" if name == "libvenus_desktop_crosvm.so" else
                      "CANDIDATE" if name == "libvenus_crosvm.so" else None)
            if prefix and (digest != _policy_pin(policy, prefix + "_SHA256") or
                           len(data) != int(_policy_pin(policy, prefix + "_BYTES"))):
                raise ValueError(f"APK executable differs from reviewed policy pin: {name}")
        for entry in ("assets/third_party/rootfs-tools-notices.txt",
                      "assets/third_party/venus-host-notices.txt"):
            if entry not in names or package.getinfo(entry).file_size == 0:
                raise ValueError(f"APK lacks required third-party notices: {entry}")


def verify_bundle(archive: Path) -> None:
    if archive.stat().st_size > MAX_ASSET_BYTES:
        raise ValueError("VM bundle exceeds GitHub's 2 GiB per-asset limit")
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        if len(names) != len(set(names)) or set(names) != {
            "vm.json", "vmlinuz", "initrd.img", "root_part"
        }:
            raise ValueError("VM bundle must contain exactly four root entries")
        manifest = json.loads(package.read("vm.json"))
        if (manifest.get("version") != 1 or manifest.get("kernel") != "vmlinuz" or
                manifest.get("initrd") != "initrd.img" or
                manifest.get("rootfs") != "root_part" or
                manifest.get("gpuProfile") != "venus"):
            raise ValueError("VM manifest does not match the Venus bundle contract")
        for name in ("vmlinuz", "initrd.img", "root_part"):
            if package.getinfo(name).file_size == 0:
                raise ValueError(f"Empty VM bundle input: {name}")
        if damaged := package.testzip():
            raise ValueError(f"VM bundle member failed CRC validation: {damaged}")


def stage_release(tag: str, apk: Path, archive: Path, native_dir: Path,
                  policy: Path, destination: Path) -> None:
    parse_version(tag)
    verify_apk(apk, native_dir, policy)
    verify_bundle(archive)
    destination.mkdir(parents=True, exist_ok=True)
    assets = (
        (apk, destination / f"Viruaisor-{tag}.apk"),
        (archive, destination / f"Viruaisor-{tag}-manjaro-plasma-venus.zip"),
    )
    for source, target in assets:
        if source.stat().st_size > MAX_ASSET_BYTES:
            raise ValueError(f"Release asset exceeds 2 GiB: {source}")
        shutil.copyfile(source, target)
    (destination / "SHA256SUMS").write_text(
        "".join(f"{sha256_file(target)}  {target.name}\n" for _, target in assets),
        encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    version = subparsers.add_parser("version")
    version.add_argument("tag")
    stage = subparsers.add_parser("stage")
    stage.add_argument("tag")
    stage.add_argument("apk", type=Path)
    stage.add_argument("bundle", type=Path)
    stage.add_argument("native_dir", type=Path)
    stage.add_argument("policy", type=Path)
    stage.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    if args.command == "version":
        name, code = parse_version(args.tag)
        print(f"versionName={name}\nversionCode={code}")
    else:
        stage_release(args.tag, args.apk, args.bundle, args.native_dir,
                      args.policy, args.output_dir)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f"Release validation failed: {error}", file=sys.stderr)
        sys.exit(1)
