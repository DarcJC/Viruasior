#pragma once
#include <stdint.h>
/* These flags describe the guest CPU mapping of an uncached DMA-BUF. They do
 * not claim that a native cached vkMapMemory mapping became coherent. */
static inline uint32_t venus_uncached_alias_flags(uint32_t native_flags) {
    if((native_flags & 14)!=10 || (native_flags & (16|32)))return 0;
    return (native_flags & ~UINT32_C(8)) | 4;
}
static inline uint32_t venus_uncached_native_index(uint32_t type,uint32_t native_count,
    uint32_t count,const uint32_t map[32]) {
    if(!map || count>32 || native_count>count || type>=count)return UINT32_MAX;
    return type<native_count?type:(map[type]<native_count?map[type]:UINT32_MAX);
}
static inline uint32_t venus_uncached_expand_types(uint32_t bits,uint32_t native_count,
    uint32_t count,const uint32_t map[32]) {
    if(!map || count>32 || native_count>count)return bits;
    for(uint32_t i=native_count;i<count;i++)
        if(map[i]<native_count && (bits&(UINT32_C(1)<<map[i])))bits|=UINT32_C(1)<<i;
    return bits;
}
