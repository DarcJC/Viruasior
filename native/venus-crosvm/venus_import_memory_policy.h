#pragma once
#include <stdint.h>

/* Vulkan numeric bits: HOST_VISIBLE=2, HOST_COHERENT=4, HOST_CACHED=8.
 * Preserve type indices/heaps and only remove capabilities the allocator cannot
 * satisfy. Actual allocations must still validate the returned FD's type mask. */
static inline uint32_t venus_import_memory_properties(uint32_t type, uint32_t flags,
                                                       uint32_t uncached, uint32_t cached) {
    if (!(flags & 2)) return flags;
    uint32_t mask = flags & 8 ? cached : uncached;
    if (type >= 32 || !(mask & (UINT32_C(1) << type))) return flags & ~UINT32_C(14);
    return flags;
}
