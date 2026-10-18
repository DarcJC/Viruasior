#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

/* Whole-DMA-BUF cache maintenance must not flush unrelated slab occupants.
 * This is an opt-in for the paired non-coherent Venus/Mali transport only. */
static inline bool zink_venus_private_upload(bool venus, uint32_t vendor,
                                            bool host_visible, bool coherent,
                                            bool external, const char *option) {
    return venus && vendor == 0x13b5 && host_visible && !coherent &&
           !external && option && strcmp(option, "1") == 0;
}
