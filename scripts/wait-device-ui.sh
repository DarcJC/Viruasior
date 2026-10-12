#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/c/Tools/Android/platform-tools/adb.exe}"
WANTED="${1:?usage: wait-device-ui.sh <text> [timeout-seconds]}"
TIMEOUT="${2:-600}"
DEADLINE=$((SECONDS + TIMEOUT))
last=""

while (( SECONDS < DEADLINE )); do
  xml="$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
  status="$(grep -oE 'text="(Importing VM bundle|VM starting|VM running|Guest agent ready|VM stopped|VM crashed)"' <<<"$xml" | head -1 || true)"
  detail="$(grep -oE 'text="[^"]+"' <<<"$xml" | sed -n '8,10p' | tr '\n' ' ' || true)"
  current="$status $detail"
  if [[ "$current" != "$last" ]]; then
    printf '%s\n' "$current"
    last="$current"
  fi
  if grep -Fiq "$WANTED" <<<"$xml"; then
    printf 'PASS: found %s\n' "$WANTED"
    exit 0
  fi
  if grep -Fq 'VM crashed' <<<"$xml"; then
    printf 'FAIL: VM crashed before finding %s\n' "$WANTED" >&2
    exit 1
  fi
  sleep 5
done

printf 'FAIL: timed out waiting for %s\n' "$WANTED" >&2
exit 1
