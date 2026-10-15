#include "venus_cache_sync.h"
#include <assert.h>
struct capture { uint64_t flags[8]; unsigned count; int fail_at; int fail_errno; };
static int record(int fd, uint64_t flags, void *cookie) {
    struct capture *c = cookie; assert(fd == 7 && c->count < 8);
    c->flags[c->count++] = flags;
    if ((int)c->count == c->fail_at) { errno=c->fail_errno; return -1; }
    return 0;
}
int main(void) {
    struct capture c = {0};
    assert(!venus_cache_sync(7, false, record, &c));
    assert(c.count == 2 && c.flags[0] == DMA_BUF_SYNC_WRITE && c.flags[1] == (DMA_BUF_SYNC_WRITE|DMA_BUF_SYNC_END));
    c=(struct capture){0}; assert(!venus_cache_sync(7, true, record, &c));
    assert(c.count == 2 && c.flags[0] == DMA_BUF_SYNC_READ && c.flags[1] == (DMA_BUF_SYNC_READ|DMA_BUF_SYNC_END));
    c=(struct capture){.fail_at=1,.fail_errno=EACCES}; assert(venus_cache_sync(7, false, record, &c) == -1 && c.count == 1);
    c=(struct capture){.fail_at=2,.fail_errno=EIO}; assert(venus_cache_sync(7, false, record, &c) == -1 && c.count == 2);
    c=(struct capture){.fail_at=1,.fail_errno=EINTR}; assert(!venus_cache_sync(7, false, record, &c) && c.count == 3);
    c=(struct capture){.fail_at=2,.fail_errno=EAGAIN}; assert(!venus_cache_sync(7, true, record, &c) && c.count == 3);
    assert(venus_cache_range_valid(4096, 0, UINT64_MAX));
    assert(venus_cache_range_valid(4096, 1024, 3072));
    assert(!venus_cache_range_valid(4096, 4096, 1));
    assert(!venus_cache_range_valid(4096, 1, 4096));
    assert(!venus_cache_range_valid(4096, 0, 0));
    return 0;
}
