#include "venus_dmaheap.h"
#include <assert.h>
int main(void) {
    assert(venus_heap_allocation_size(1024, 4096) == 4096);
    assert(venus_heap_allocation_size(4097, 4096) == 8192);
    assert(venus_heap_allocation_size(4096, 16384) == 16384);
    assert(!venus_heap_allocation_size(0, 4096));
    assert(!venus_heap_allocation_size(UINT64_MAX, 4096));
    assert(!venus_heap_allocation_size(64*1024*1024+1, 4096));
    assert(!venus_heap_allocation_size(1, 0));
    assert(!venus_heap_allocation_size(1, 4095));
    assert(venus_heap_memory_type_valid(15, 1u << 15));
    assert(!venus_heap_memory_type_valid(9, 1u << 15));
    assert(!venus_heap_memory_type_valid(32, UINT32_MAX));
    return 0;
}
