#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}/crosvm/devices/src/virtio/fs
temporary=$(mktemp -d /tmp/viruaisor-fsetid-test.XXXXXX)
trap 'rm -rf -- "$temporary"' EXIT
python3 - "$source/passthrough.rs" "$temporary/scoped.rs" <<'PY'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
start = source.index('struct ScopedFsetid')
end = source.index('\nfn ebadf()', start)
pathlib.Path(sys.argv[2]).write_text(source[start:end])
PY
# Use the existing host runtime; this local link alias installs no package or system library.
host_cap=$(ldconfig -p | awk '/libcap\.so\.2 / {print $NF; exit}')
[[ -f "$host_cap" ]]
ln -s "$host_cap" "$temporary/libcap.so"
VIRUAISOR_CAPS_SOURCE="$source/caps.rs" VIRUAISOR_SCOPED_SOURCE="$temporary/scoped.rs" \
    rustc --edition=2021 --test "$repo/native/storage/scoped_fsetid_test.rs" -L native="$temporary" \
        -o "$temporary/test"
if [[ $(id -u) == 0 ]]; then
    effective_result=0
    "$temporary/test" --test-threads=1 --skip without_permitted_capabilities || effective_result=$?
    chmod 755 "$temporary"
    unprivileged_result=0
    setpriv --reuid=65534 --regid=65534 --clear-groups --bounding-set=-all --inh-caps=-all --ambient-caps=-all \
        "$temporary/test" --exact without_permitted_capabilities_no_restore_is_attempted || unprivileged_result=$?
    [[ $effective_result == 0 && $unprivileged_result == 0 ]]
else
    "$temporary/test" --exact without_permitted_capabilities_no_restore_is_attempted
fi
