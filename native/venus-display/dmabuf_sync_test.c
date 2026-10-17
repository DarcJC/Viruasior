#include <assert.h>
#include <linux/dma-buf.h>
#include <stdbool.h>
#include <stddef.h>

#include "dmabuf_sync.h"

static unsigned long calls[4];
static unsigned long flags[4];
static size_t count;
static int failure_at = -1;

static int fake_ioctl(int fd, unsigned long request, void *argument) {
    assert(fd == 17);
    calls[count] = request;
    flags[count] = ((struct dma_buf_sync *)argument)->flags;
    return (int)count++ == failure_at ? -1 : 0;
}

int main(void) {
    assert(venus_dmabuf_begin_cpu_read(17, fake_ioctl));
    assert(venus_dmabuf_end_cpu_read(17, fake_ioctl));
    assert(count == 2);
    assert(calls[0] == DMA_BUF_IOCTL_SYNC && calls[1] == DMA_BUF_IOCTL_SYNC);
    assert(flags[0] == (DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ));
    assert(flags[1] == (DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ));

    count = 0;
    failure_at = 0;
    assert(!venus_dmabuf_begin_cpu_read(17, fake_ioctl));
    assert(count == 1);
    return 0;
}
