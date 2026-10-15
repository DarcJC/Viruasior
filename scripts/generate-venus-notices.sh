#!/usr/bin/env bash
# Mechanical extraction of upstream copyright/license headers for the packaged worker.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_WORK_DIR:-"$repo/.pi/device-debug/venus-host-build"}
renderer="$work/virglrenderer"
output="$repo/app/src/main/assets/third_party/venus-host-notices.txt"
mkdir -p "$(dirname "$output")"
printf 'Viruaisor Venus worker third-party notices (includes unused support headers)\n' > "$output"
printf 'virglrenderer 07a5bc398dfffdc36ad20a3476348753aa10a322\n' >> "$output"
printf 'venus-protocol ca19b6358d7cc491bc3e4de76f04c6700876a8fa\n\n' >> "$output"
sed -n '1,$p' "$renderer/COPYING" >> "$output"
printf '\nVenus protocol: Copyright 2020 Google LLC; SPDX-License-Identifier: MIT\n' >> "$output"
while IFS= read -r -d '' file; do
    printf '\n--- %s ---\n' "${file#"$renderer/"}" >> "$output"
    awk '/^[[:space:]]*\/\*/ { in_comment=1 } in_comment { print; if (index($0,"*/")) exit }' "$file" >> "$output"
done < <(find "$renderer/src/mesa" "$renderer/src/venus" "$renderer/src/proxy" "$renderer/server" \
    -type f \( -name '*.c' -o -name '*.h' \) -print0 | sort -z)
for file in "$renderer"/src/*.c "$renderer"/src/*.h; do
    printf '\n--- %s ---\n' "${file#"$renderer/"}" >> "$output"
    awk '/^[[:space:]]*\/\*/ { in_comment=1 } in_comment { print; if (index($0,"*/")) exit }' "$file" >> "$output"
done
