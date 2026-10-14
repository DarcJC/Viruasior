#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

mkdir -p "$fixture/bin" "$fixture/home" "$fixture/run" "$fixture/icd"
touch "$fixture/icd/gfxstream_vk_icd.json" "$fixture/renderD128"
: > "$fixture/groups"
: > "$fixture/calls"

cat > "$fixture/bin/id" <<'SH'
#!/bin/sh
set -eu
if [ "$1" = -u ]; then printf '1000\n'; exit 0; fi
if [ "$1" = -nG ]; then
    if grep -qx render "$PROFILE_GROUPS"; then printf 'users render\n'; else printf 'users renderer\n'; fi
    exit 0
fi
exit 64
SH
cat > "$fixture/bin/getent" <<'SH'
#!/bin/sh
set -eu
[ "$1" = group ] && [ "$2" = render ] && [ "${PROFILE_RENDER_GROUP:-present}" = present ]
SH
cat > "$fixture/bin/usermod" <<'SH'
#!/bin/sh
set -eu
[ "$1" = -a ] && [ "$2" = -G ] && [ "$3" = render ] && [ "$4" = droid ]
printf 'render\n' > "$PROFILE_GROUPS"
printf 'usermod\n' >> "$PROFILE_CALLS"
SH
cat > "$fixture/bin/chown" <<'SH'
#!/bin/sh
set -eu
printf 'chown %s\n' "$*" >> "$PROFILE_CALLS"
SH
cat > "$fixture/bin/systemctl" <<'SH'
#!/bin/sh
set -eu
if [ "$1" = is-active ]; then exit 1; fi
printf 'systemctl %s\n' "$*" >> "$PROFILE_CALLS"
SH
cat > "$fixture/bin/systemd-run" <<'SH'
#!/bin/sh
set -eu
printf 'systemd-run %s\n' "$*" >> "$PROFILE_CALLS"
SH
cat > "$fixture/bin/runuser" <<'SH'
#!/bin/sh
set -eu
printf 'runuser %s\n' "$*" >> "$PROFILE_CALLS"
SH
chmod +x "$fixture/bin/"*

export PROFILE_GROUPS="$fixture/groups"
export PROFILE_CALLS="$fixture/calls"
export VIRUAISOR_ID="$fixture/bin/id"
export VIRUAISOR_GETENT="$fixture/bin/getent"
export VIRUAISOR_USERMOD="$fixture/bin/usermod"
export VIRUAISOR_CHOWN="$fixture/bin/chown"
export VIRUAISOR_SYSTEMCTL="$fixture/bin/systemctl"
export VIRUAISOR_SYSTEMD_RUN="$fixture/bin/systemd-run"
export VIRUAISOR_RUNUSER="$fixture/bin/runuser"
export VIRUAISOR_RUNTIME_DIR="$fixture/run"
export VIRUAISOR_WESTON_ENV="$fixture/home/weston.env"
export VIRUAISOR_GFXSTREAM_ICD="$fixture/icd/gfxstream_vk_icd.json"
export VIRUAISOR_RENDER_NODE="$fixture/renderD128"

helper="$repo/guest/avf-agent/avf-desktop"

grep -Fxq 'TimeoutStartSec=5' "$repo/docs/guest/avf-desktop@.service"

"$helper" start 2d
cat > "$fixture/expected-2d" <<'EOF'
MESA_LOADER_DRIVER_OVERRIDE=zink
LIBGL_ALWAYS_SOFTWARE=1
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
EOF
cmp "$fixture/expected-2d" "$fixture/home/weston.env"
! grep -q '^usermod$' "$fixture/calls"

: > "$fixture/calls"
"$helper" stop
"$helper" start gfxstream
cat > "$fixture/expected-gfxstream" <<EOF
MESA_LOADER_DRIVER_OVERRIDE=zink
VK_DRIVER_FILES=$fixture/icd/gfxstream_vk_icd.json
VK_ICD_FILENAMES=$fixture/icd/gfxstream_vk_icd.json
EOF
cmp "$fixture/expected-gfxstream" "$fixture/home/weston.env"
[ "$(grep -c '^usermod$' "$fixture/calls")" -eq 1 ]

: > "$fixture/calls"
"$helper" stop
"$helper" start gfxstream
! grep -q '^usermod$' "$fixture/calls"

if PROFILE_RENDER_GROUP=missing "$helper" start gfxstream 2>/dev/null; then
    echo 'FAIL: missing render group was accepted' >&2
    exit 1
fi
rm -f "$fixture/icd/gfxstream_vk_icd.json"
if "$helper" start gfxstream 2>/dev/null; then
    echo 'FAIL: missing gfxstream ICD was accepted' >&2
    exit 1
fi
if "$helper" start invalid 2>/dev/null; then
    echo 'FAIL: invalid profile was accepted' >&2
    exit 1
fi

echo 'PASS: guest desktop profiles are explicit and fail closed'
