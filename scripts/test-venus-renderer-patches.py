#!/usr/bin/env python3
"""Replay the desktop renderer patch stack on its pinned upstream source."""

import argparse
import io
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile


REVISION = "07a5bc398dfffdc36ad20a3476348753aa10a322"
REPO = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("renderer", type=Path, help="Existing pinned virglrenderer checkout")
    parser.add_argument(
        "--compare-working-tree", action="store_true",
        help="Also require replayed files to match the existing patched checkout",
    )
    args = parser.parse_args()
    source = args.renderer.resolve()
    revision = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()
    if revision != REVISION:
        parser.error(f"Expected renderer {REVISION}, got {revision}")

    patches = [
        REPO / "native/venus-host/0001-destroy-object-mutex-after-objects.patch",
        REPO / "native/venus-crosvm/renderer-android-runtime.patch",
    ]
    # These calls are ordered exactly as in the display + import-filter build.
    # Replay from upstream, without the build script's already-applied shortcut.
    script = REPO / "scripts/build-venus-crosvm-candidate.sh"
    for line in script.read_text().splitlines():
        if line.strip().startswith("apply_renderer_patch "):
            name = shlex.split(line)[1]
            patches.append(REPO / "native/venus-crosvm" / f"{name}.patch")

    archive = subprocess.check_output(["git", "-C", str(source), "archive", "HEAD"])
    with tempfile.TemporaryDirectory(prefix="viruaisor-renderer-patches-") as directory:
        target = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(target, filter="data")
        touched = set()
        for patch in patches:
            subprocess.run(["git", "apply", str(patch)], cwd=target, check=True)
            for line in patch.read_text().splitlines():
                if line.startswith("+++ b/"):
                    touched.add(line[6:])
        if args.compare_working_tree:
            different = [
                path for path in sorted(touched)
                if (target / path).read_bytes() != (source / path).read_bytes()
            ]
            if different:
                raise SystemExit("Patched checkout differs: " + ", ".join(different))
            print(f"PASS: {len(touched)} patched source files match the working tree")
        print(f"PASS: {len(patches)} renderer patches apply to clean upstream {REVISION}")


if __name__ == "__main__":
    main()
