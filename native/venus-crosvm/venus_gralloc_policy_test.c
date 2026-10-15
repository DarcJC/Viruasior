#include "venus_gralloc_policy.h"
#include <assert.h>
int main(void) {
    assert(venus_gralloc_buffer_external(0x13b5, 1024, 0, 0x20, false, true));
    assert(!venus_gralloc_buffer_external(0x13b5, 1024, 0, 0x20, true, true));
    assert(!venus_gralloc_buffer_external(0x13b5, 1024, 0, 0x20, false, false));
    assert(!venus_gralloc_buffer_external(0x13b5, 1024, 1, 0x20, false, true));
    assert(!venus_gralloc_buffer_external(0x13b5, 128*1024*1024, 0, 0x20, false, true));
    assert(!venus_gralloc_buffer_external(0x1234, 1024, 0, 0x20, false, true));
    assert(!venus_gralloc_buffer_external(0x13b5, 1024, 0, 0x10, false, true));
    assert(venus_gralloc_fd_candidate("/dmabuf:123", 4096, 4096));
    assert(venus_gralloc_fd_candidate("anon_inode:dmabuf", 8192, 4096));
    assert(!venus_gralloc_fd_candidate("/memfd:metadata", 4096, 4096));
    assert(!venus_gralloc_fd_candidate("/dmabuf:123", 1024, 4096));
    assert(!venus_gralloc_fd_candidate("/dmabuf:123", -1, 4096));
    assert(!venus_gralloc_fd_candidate("/dmabuf:123", 128*1024*1024, 4096));
    assert(!venus_gralloc_fd_candidate(NULL, 4096, 4096));
    return 0;
}
