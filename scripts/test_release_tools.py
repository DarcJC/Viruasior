import json
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from release_tools import NATIVE_NAMES, parse_version, verify_apk, verify_bundle


class ReleaseToolsTest(unittest.TestCase):
    def test_tag_requires_exact_three_component_version(self):
        self.assertEqual(parse_version("v1.2.3"), ("1.2.3", 1002004))
        for tag in ("v1.2", "v1.2.3.4", "v1.2.3-rc1", "v01.2.3", "1.2.3"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                parse_version(tag)

    def test_version_code_rejects_ambiguous_or_overflowing_components(self):
        for tag in ("v1.1000.0", "v1.0.1000", "v2148.0.0"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                parse_version(tag)

    def test_bundle_requires_importable_root_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "bundle.zip"
            manifest = {"version": 1, "kernel": "vmlinuz", "initrd": "initrd.img",
                        "rootfs": "root_part", "gpuProfile": "venus"}
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("vm.json", json.dumps(manifest))
                bundle.writestr("vmlinuz", b"kernel")
                bundle.writestr("initrd.img", b"initrd")
                bundle.writestr("root_part", b"rootfs")
            verify_bundle(archive)
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("nested/vm.json", json.dumps(manifest))
                bundle.writestr("vmlinuz", b"kernel")
                bundle.writestr("initrd.img", b"initrd")
                bundle.writestr("root_part", b"rootfs")
            with self.assertRaises(ValueError):
                verify_bundle(archive)

    def test_apk_rejects_a_different_crosvm_even_when_it_is_arm64(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            native_dir = root / "native"
            native_dir.mkdir()
            elf = b"\x7fELF\x02\x01" + b"\0" * 12 + b"\xb7\0" + b"body"
            for name in NATIVE_NAMES:
                (native_dir / name).write_bytes(elf)
            digest = hashlib.sha256(elf).hexdigest()
            policy = root / "RootOverlayArtifactPolicy.kt"
            policy.write_text(
                f'const val CANDIDATE_SHA256 = "{digest}"\n'
                f'const val CANDIDATE_BYTES = {len(elf)}L\n'
                f'const val DESKTOP_SHA256 = "{digest}"\n'
                f'const val DESKTOP_BYTES = {len(elf)}L\n', encoding="utf-8")
            apk = root / "app.apk"
            def make_apk(desktop):
                with zipfile.ZipFile(apk, "w") as package:
                    for name in NATIVE_NAMES:
                        package.writestr("lib/arm64-v8a/" + name,
                                         desktop if name == "libvenus_desktop_crosvm.so" else elf)
                    package.writestr("assets/third_party/rootfs-tools-notices.txt", "notice")
                    package.writestr("assets/third_party/venus-host-notices.txt", "notice")
            make_apk(elf)
            verify_apk(apk, native_dir, policy)
            make_apk(elf + b"changed")
            with self.assertRaises(ValueError):
                verify_apk(apk, native_dir, policy)

    def test_bundle_rejects_corrupt_image_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "bundle.zip"
            manifest = {"version": 1, "kernel": "vmlinuz", "initrd": "initrd.img",
                        "rootfs": "root_part", "gpuProfile": "venus"}
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("vm.json", json.dumps(manifest))
                bundle.writestr("vmlinuz", b"kernel")
                bundle.writestr("initrd.img", b"initrd")
                bundle.writestr("root_part", b"rootfs")
            data = archive.read_bytes()
            offset = data.rfind(b"rootfs")
            archive.write_bytes(data[:offset] + b"bootos" + data[offset + 6:])
            with self.assertRaises(ValueError):
                verify_bundle(archive)


if __name__ == "__main__":
    unittest.main()
