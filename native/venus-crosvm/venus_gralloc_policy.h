#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
static inline bool venus_gralloc_buffer_external(uint32_t vendor, uint64_t bytes, uint32_t flags, uint32_t usage, bool already_external, bool importable) {
    return vendor == 0x13b5 && bytes > 0 && bytes <= 64*1024*1024 && !flags &&
        (usage & 0x20) && !already_external && importable;
}
static inline bool venus_gralloc_fd_candidate(const char *name, int64_t bytes, uint64_t requested) {
    return name && (strstr(name, "dmabuf") || strstr(name, "dma_buf")) && requested > 0 &&
        bytes > 0 && (uint64_t)bytes >= requested && bytes <= 64*1024*1024;
}
