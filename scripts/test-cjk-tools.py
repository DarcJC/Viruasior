#!/usr/bin/env python3
"""Real sparse-file and ext4 update/recovery fixtures; Linux build host only."""
import hashlib
import importlib
import json
import pathlib
import subprocess
import tempfile
import unittest

tools = importlib.import_module("update-guest-cjk")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ImageToolsTest(unittest.TestCase):
    def test_sparse_copy_preserves_bytes_holes_and_existing_destination(self):
        with tempfile.TemporaryDirectory(prefix="viruaisor-copy-test-") as folder:
            folder = pathlib.Path(folder)
            binary = folder / "copy"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", str(tools.REPO / "native/storage/copy_image.c"), "-o", str(binary)], check=True)
            source, destination = folder / "source", folder / "copy.img"
            with source.open("wb") as stream:
                stream.write(b"header")
                stream.seek(8*1024*1024)
                stream.write(b"payload")
                stream.truncate(32*1024*1024)
            subprocess.run([binary, source, destination], check=True)
            self.assertEqual(digest(source), digest(destination))
            self.assertLess(destination.stat().st_blocks*512, destination.stat().st_size//4)
            self.assertNotEqual(0, subprocess.run([binary, source, destination], capture_output=True).returncode)
            self.assertEqual(digest(source), digest(destination))
            link = folder / "link"
            link.symlink_to(source)
            self.assertNotEqual(0, subprocess.run([binary, link, folder / "unsafe"], capture_output=True).returncode)

    def test_real_ext4_update_refresh_confirm_and_recovery_preserve_user_file(self):
        with tempfile.TemporaryDirectory(prefix="viruaisor-ext4-test-") as folder:
            folder = pathlib.Path(folder)
            root = folder / "root"
            for directory in ("usr/local/sbin", "usr/local/share/fonts", "opt", "home/droid"):
                (root / directory).mkdir(parents=True, exist_ok=True)
            (root / "home/droid/keep.txt").write_text("retained user document\n")
            image = folder / "root_part"
            with image.open("wb") as stream:
                stream.truncate(160*1024*1024)
            subprocess.run(["mke2fs", "-q", "-F", "-t", "ext4", "-d", root, image], check=True)
            original = digest(image)
            target = tools.Local(image)
            tools.update(target, "apply")
            self.assertEqual(original, digest(folder / ".cjk-update/original"))
            self.assertFalse((folder / ".guest-maintenance").exists())
            tools.update(target, "refresh")
            tools.update(target, "confirm")
            self.assertEqual("verified", json.loads((folder / ".cjk-update/journal.json").read_text())["phase"])
            keep = folder / "keep"
            target.run([target.debugfs, "-R", f"dump /home/droid/keep.txt {keep}", target.image])
            self.assertEqual("retained user document\n", keep.read_text())
            tools.update(target, "recover")
            self.assertEqual(original, digest(image))


if __name__ == "__main__":
    unittest.main()
