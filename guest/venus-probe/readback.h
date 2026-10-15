#pragma once
#include <stdint.h>
#include <stddef.h>
static inline uint32_t venus_input(uint32_t seed, uint32_t i) { return seed + i * UINT32_C(747796405); }
static inline uint32_t venus_expected(uint32_t seed, uint32_t i) {
    return ((venus_input(seed, i) ^ (UINT32_C(0x9e3779b9) + i * UINT32_C(0x045d9f3b))) * UINT32_C(1664525)) + UINT32_C(1013904223);
}
static inline int venus_check_readback(const uint32_t *words, size_t count, uint32_t seed) {
    for (size_t i = 0; i < count; ++i) if (words[i] != venus_expected(seed, (uint32_t)i)) return 0;
    return 1;
}
