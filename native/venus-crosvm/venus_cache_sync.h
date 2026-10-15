#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <errno.h>
#include <linux/dma-buf.h>
typedef int (*venus_sync_call)(int, uint64_t, void *);
static inline int venus_cache_sync(int fd, bool invalidate, venus_sync_call call, void *cookie) {
    if (fd < 0 || !call) { errno=EINVAL; return -1; }
    const uint64_t access = invalidate ? DMA_BUF_SYNC_READ : DMA_BUF_SYNC_WRITE;
    for (unsigned end = 0; end < 2; ++end) {
        int result;
        do { result=call(fd, access | (end ? DMA_BUF_SYNC_END : DMA_BUF_SYNC_START), cookie); }
        while (result < 0 && (errno == EINTR || errno == EAGAIN));
        if (result) return result;
    }
    return 0;
}
static inline bool venus_cache_range_valid(uint64_t allocation, uint64_t offset, uint64_t size) {
    return allocation && offset < allocation && size && (size == UINT64_MAX || size <= allocation-offset);
}
