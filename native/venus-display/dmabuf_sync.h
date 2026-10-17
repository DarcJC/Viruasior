#pragma once

#include <linux/dma-buf.h>
#include <stdbool.h>

typedef int (*venus_dmabuf_ioctl_fn)(int, unsigned long, void *);

static inline bool venus_dmabuf_sync(int fd, unsigned long phase,
                                     venus_dmabuf_ioctl_fn call) {
    struct dma_buf_sync request = {
        .flags = phase | DMA_BUF_SYNC_READ,
    };
    return call(fd, DMA_BUF_IOCTL_SYNC, &request) == 0;
}

static inline bool venus_dmabuf_begin_cpu_read(int fd,
                                               venus_dmabuf_ioctl_fn call) {
    return venus_dmabuf_sync(fd, DMA_BUF_SYNC_START, call);
}

static inline bool venus_dmabuf_end_cpu_read(int fd,
                                             venus_dmabuf_ioctl_fn call) {
    return venus_dmabuf_sync(fd, DMA_BUF_SYNC_END, call);
}
