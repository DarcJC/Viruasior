#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=$(mktemp -d /tmp/viruaisor-venus-tests.XXXXXX)
for test in "$repo"/native/venus-overlay/*_test.c "$repo"/native/venus-crosvm/*_test.c "$repo"/native/venus-display/*_test.c "$repo"/guest/venus-probe/*_test.c; do
    output="$work/$(basename "$test" .c)"
    gcc -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
        -fno-omit-frame-pointer "$test" -o "$output"
    "$output"
    printf 'PASS %s\n' "${test#"$repo/"}"
done
for test in "$repo"/native/venus-display/*_test.cpp; do
    output="$work/$(basename "$test" .cpp)"
    g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined \
        -fno-omit-frame-pointer "$test" -o "$output"
    "$output"
    printf 'PASS %s\n' "${test#"$repo/"}"
done
rustc --test "$repo/native/venus-crosvm/venus_init_policy.rs" -o "$work/venus_init_policy"
"$work/venus_init_policy"
printf 'Test artifacts: %s\n' "$work"
