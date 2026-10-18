#pragma once
#include <stdbool.h>
#include <stdint.h>

static inline bool venus_ahb_bind_allowed(bool external_image,
                                         bool imported_ahb, uint64_t offset)
{
    return !external_image || (imported_ahb && offset == 0);
}
