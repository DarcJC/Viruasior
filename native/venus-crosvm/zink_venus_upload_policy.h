#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

/* Both emulated coherent and explicit non-coherent uploads use whole-DMA-BUF
 * cache maintenance in this paired transport. Keep uploads out of large slabs.
 * External allocations retain their caller's allocation and sharing contract. */
static inline bool zink_venus_private_upload(bool venus, uint32_t vendor,
                                            bool host_visible, bool external,
                                            const char *option) {
    return venus && vendor == 0x13b5 && host_visible &&
           !external && option && strcmp(option, "1") == 0;
}
