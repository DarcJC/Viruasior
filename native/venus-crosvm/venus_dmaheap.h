#pragma once
#include <stdint.h>
#include <stdbool.h>
static inline uint64_t venus_heap_allocation_size(uint64_t requested, uint64_t page) {
    if (!requested || requested > UINT64_C(64)*1024*1024 || page < 4096 || page > 65536 || (page & (page-1))) return 0;
    return (requested + page - 1) & ~(page - 1);
}
static inline bool venus_heap_memory_type_valid(uint32_t type, uint32_t bits) {
    return type < 32 && (bits & (UINT32_C(1) << type));
}
