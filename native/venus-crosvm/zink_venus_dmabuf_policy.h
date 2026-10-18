#pragma once
#include <stdbool.h>
#include <stdint.h>
static inline bool zink_venus_linear_dmabuf(bool modifiers, bool venus,
                                            uint32_t vendor, uint64_t modifier) {
    return !modifiers && venus && vendor == 0x13b5 &&
           (modifier == 0 || /* DRM_FORMAT_MOD_LINEAR */
            modifier == ((UINT64_C(1) << 56) - 1)); /* DRM_FORMAT_MOD_INVALID */
}
