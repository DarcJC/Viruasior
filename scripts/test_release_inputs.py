import hashlib
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import release_inputs


class ReleaseInputsTest(unittest.TestCase):
    def test_round_trip_exports_only_reviewed_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "vmlinuz").write_bytes(b"reviewed kernel")
            (source / "private.key").write_text("must not be exported")
            pins = {"vmlinuz": hashlib.sha256(b"reviewed kernel").hexdigest()}
            archive = root / "inputs.zip"
            with patch.object(release_inputs, "PINS", pins):
                release_inputs.pack_inputs(source, source, archive)
                release_inputs.unpack_inputs(archive, root / "out")
            self.assertEqual((root / "out/vmlinuz").read_bytes(), b"reviewed kernel")
            self.assertEqual(zipfile.ZipFile(archive).namelist(), ["vmlinuz"])

    def test_rejects_changed_input_before_writing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "inputs.zip"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("vmlinuz", b"unreviewed kernel")
            with patch.object(release_inputs, "PINS", {"vmlinuz": "0" * 64}):
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    release_inputs.unpack_inputs(archive, root / "out")
            self.assertFalse((root / "out").exists())

    def test_rejects_extra_paths_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for extra in ("../escaped", "private.key", "vmlinuz"):
                with self.subTest(extra=extra):
                    archive = root / "inputs.zip"
                    with warnings.catch_warnings(), zipfile.ZipFile(archive, "w") as package:
                        warnings.filterwarnings("ignore", message="Duplicate name:")
                        package.writestr("vmlinuz", b"kernel")
                        package.writestr(extra, b"extra")
                    with patch.object(release_inputs, "PINS", {"vmlinuz": "0" * 64}):
                        with self.assertRaisesRegex(ValueError, "entries"):
                            release_inputs.unpack_inputs(archive, root / "out")


if __name__ == "__main__":
    unittest.main()
