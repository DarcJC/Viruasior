#!/usr/bin/env python3
"""Offline CJK update with a retained original. No Android root or system-file writes."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))
from importlib import import_module
prepare = import_module("prepare-cjk-fonts").prepare


class Local:
    def __init__(self, image):
        self.image = str(image.resolve(strict=True))
        self.base = str(image.resolve().parent)
        self.debugfs = shutil.which("debugfs")
        self.checker = shutil.which("e2fsck")
        self.copier = str(REPO / ".pi/device-debug/cjk-copy-host")
        self.run(["cc", "-O2", str(REPO / "native/storage/copy_image.c"), "-o", self.copier])
        assert self.debugfs and self.checker

    def run(self, args, data=None, allowed=(0,), raw=False):
        result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1800)
        if result.returncode not in allowed:
            raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr.decode(errors='replace')[-1000:]}")
        return result.stdout if raw else (result.stdout + result.stderr).decode(errors="replace")

    def write(self, path, data):
        pathlib.Path(path).write_bytes(data)

    def read(self, path):
        return pathlib.Path(path).read_bytes()

    def stopped(self):
        pass  # A local build image has no AVF owner; it must not be mounted by the caller.


class Device:
    def __init__(self, adb, package):
        assert re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package)
        self.adb, self.package = adb, package
        manifest = json.loads(self.run(["cat", "files/vms/default/vm.json"]))
        name = manifest["rootfs"]
        assert re.fullmatch(r"[A-Za-z0-9_-]+", name), "Expected one raw guest partition file"
        self.base = "files/vms/default"
        self.image = self.base + "/" + name
        apk = self.host(["shell", "pm", "path", package]).decode().strip().removeprefix("package:")
        assert re.fullmatch(r"/data/app/[A-Za-z0-9_~=/.-]+/base\.apk", apk)
        native = str(pathlib.PurePosixPath(apk).parent / "lib/arm64")
        self.debugfs = native + "/librootfs_debugfs.so"
        self.checker = native + "/librootfs_e2fsck.so"
        self.copier = native + "/librootfs_copy.so"

    def host(self, args, data=None):
        # adb joins shell arguments again on the device. Preserve each nested
        # command argument (notably su -c) instead of relying on host quoting.
        if args and args[0] == "shell":
            options = ["shell"]
            remaining = args[1:]
            while remaining and remaining[0] in ("-T", "-n", "-t"):
                options.append(remaining[0]); remaining = remaining[1:]
            args = options + ([shlex.join(remaining)] if len(remaining) > 1 else remaining)
        result = subprocess.run([self.adb] + args, input=data,
            stdin=subprocess.DEVNULL if data is None else None,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1800)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace")[-1000:])
        return result.stdout

    def run(self, args, data=None, allowed=(0,), raw=False):
        command = shlex.join(["run-as", self.package] + args)
        # shell-v2 returns the remote exit status and separates stderr; exec-out
        # reports adb's transport success even when e2fsck/cat itself failed.
        result = subprocess.run([self.adb, "shell", "-T", command], input=data,
            stdin=subprocess.DEVNULL if data is None else None,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1800)
        if result.returncode not in allowed:
            raise RuntimeError(f"{args[0]} failed ({result.returncode}): {(result.stdout+result.stderr).decode(errors='replace')[-1000:]}")
        return result.stdout if raw else (result.stdout + result.stderr).decode(errors="replace").replace("\r\n", "\n")

    def write(self, path, data):
        # exec-out only forwards stdout. shell-v2 without a PTY also forwards
        # binary stdin and its EOF, which is required for finite file writes.
        # Windows adb stdin can treat a font's 0x1a byte as text EOF. ASCII
        # framing avoids that conversion; the guest payload is hash-verified.
        command = shlex.join(["run-as", self.package, "sh", "-c", "base64 -d > " + shlex.quote(path)])
        self.host(["shell", "-T", command], data=base64.b64encode(data)+b"\n")

    def read(self, path):
        return self.run(["cat", path], raw=True)

    def stopped(self):
        rows = self.host(["shell", "ps", "-A"]).decode()
        if re.search(r"crosvm_|virtmgr_|libvenus_desktop_crosvm\.so", rows):
            raise RuntimeError("Stop the VM and its storage workers before touching its image")


def exists(target, path):
    return target.run(["sh", "-c", "test -e " + shlex.quote(path) + " && printf yes"], allowed=(0, 1)).strip() == "yes"


def update(target, action):
    folder = target.base + "/.cjk-update"
    marker = target.base + "/.guest-maintenance"
    journal = folder + "/journal.json"
    original, candidate = folder + "/original", folder + "/candidate"
    target.stopped()
    for path in (target.image, folder, marker):
        target.run(["sh", "-c", "test ! -L " + shlex.quote(path)])
    record = json.loads(target.read(journal)) if exists(target, journal) else None
    if record:
        assert record["version"] == 1 and record["image"] == target.image
        if action == "refresh":
            assert record["phase"] in ("switched", "verified")
            assert not exists(target, candidate)
        if action == "apply":
            if record["phase"] != "restored":
                raise RuntimeError("An update/recovery record exists; confirm or recover it explicitly")
            expected = {"journal.json", "candidate", "original"} | {f"{kind}-{index}" for kind in ("payload", "verify") for index in range(6)}
            names = set(target.run(["ls", "-1", folder]).splitlines())
            assert names <= expected, "Unexpected recovery files; refusing cleanup"
            for name in sorted(names):
                target.run(["rm", "-f", folder + "/" + name])
            target.run(["rmdir", folder])
            record = None
    elif action != "apply":
        raise RuntimeError("No image update record exists")
    if action == "confirm":
        assert record["phase"] == "switched"
        target.run([target.checker, "-f", "-n", target.image])
        record["phase"] = "verified"
        target.write(journal, json.dumps(record).encode())
        # Preserve the backup for user-directed rollback; never delete it merely to finish a test.
        print("VERIFIED image; recovery original retained at " + original)
        return
    if action == "recover":
        target.run(["mkdir", "-p", marker])
        if record["phase"] in ("ready", "switched", "verified"):
            target.run([target.checker, "-f", "-n", original])
            target.run(["mv", "-f", original, target.image])
        target.run(["rm", "-f", candidate])
        record["phase"] = "restored"
        target.write(journal, json.dumps(record).encode())
        target.run(["sync", target.base])
        target.run(["rmdir", marker])
        print("RESTORED original guest image")
        return
    manifest = prepare(REPO / ".pi/device-debug/cjk-fonts", verify_only=True)
    target.run(["mkdir", marker])
    target.stopped()  # Marker prevents VmService startup before this second owner check.
    if action != "refresh":
        target.run(["mkdir", folder])
    record = {"version": 1, "image": target.image, "phase": "refreshing" if action == "refresh" else "copying", "fonts": manifest}
    target.write(journal, json.dumps(record).encode())
    if action != "refresh":
        print(target.run([target.copier, target.image, original]), flush=True)
    target.run([target.checker, "-f", "-n", original])
    print(target.run([target.copier, target.image if action == "refresh" else original, candidate]), flush=True)
    target.run([target.checker, "-f", "-p", candidate], allowed=(0, 1, 2))
    payload = [(REPO / ".pi/device-debug/cjk-fonts" / item["name"], "/usr/local/share/fonts/viruaisor/" + item["name"], "0100644") for item in manifest["files"]]
    payload += [(REPO / "guest/avf-agent/avf-agent", "/usr/local/sbin/avf-agent", "0100755"),
                (REPO / "guest/avf-agent/guest-check.py", "/opt/viruaisor/guest-check.py", "0100644")]
    def debug(command):
        return target.run([target.debugfs, "-w", "-R", command, candidate])
    for path in ("/usr/local/share", "/usr/local/share/fonts", "/usr/local/share/fonts/viruaisor", "/opt/viruaisor"):
        if "Inode:" not in target.run([target.debugfs, "-R", "stat " + path, candidate]):
            debug("mkdir " + path)
        assert "Inode:" in target.run([target.debugfs, "-R", "stat " + path, candidate])
    for index, (source, destination, mode) in enumerate(payload):
        data = source.read_bytes()
        staged = folder + f"/payload-{index}"
        verify = folder + f"/verify-{index}"
        target.write(staged, data)
        if "Inode:" in target.run([target.debugfs, "-R", "stat " + destination, candidate]):
            debug("rm " + destination)
        debug("write " + staged + " " + destination)
        for field, value in (("mode", mode), ("uid", "0"), ("gid", "0")):
            debug(f"set_inode_field {destination} {field} {value}")
        target.run([target.debugfs, "-R", "dump " + destination + " " + verify, candidate])
        assert target.run(["sha256sum", verify]).split()[0] == hashlib.sha256(data).hexdigest(), destination
        target.run(["rm", "-f", staged, verify])
        print("INSTALLED and hash-verified " + destination, flush=True)
    target.run([target.checker, "-f", "-n", candidate])
    assert target.run(["stat", "-c", "%s", candidate]).strip() == target.run(["stat", "-c", "%s", original]).strip()
    record["phase"] = "ready"; target.write(journal, json.dumps(record).encode())
    target.stopped()
    target.run(["mv", "-f", candidate, target.image])
    target.run(["sync", target.base])
    record["phase"] = "switched"; target.write(journal, json.dumps(record).encode())
    target.run(["sync", folder])
    target.run(["rmdir", marker])
    print("ACTIVATED guest fonts and fixed diagnostics; original image retained", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    destination = parser.add_mutually_exclusive_group(required=True)
    destination.add_argument("--device", action="store_true")
    destination.add_argument("--image", type=pathlib.Path)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--package", default="pro.darc.viruaisor")
    parser.add_argument("--action", choices=("apply", "refresh", "confirm", "recover"), default="apply")
    args = parser.parse_args()
    update(Device(args.adb, args.package) if args.device else Local(args.image), args.action)
