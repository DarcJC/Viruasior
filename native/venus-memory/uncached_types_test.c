#include "uncached_types.h"
#include <assert.h>
int main(void) {
    assert(venus_uncached_alias_flags(11)==7);
    assert(venus_uncached_alias_flags(10)==6);
    assert(!venus_uncached_alias_flags(7));
    assert(!venus_uncached_alias_flags(17));
    assert(!venus_uncached_alias_flags(33));
    uint32_t map[32];
    for(unsigned i=0;i<32;i++)map[i]=i;
    map[22]=1;map[23]=14;
    assert(venus_uncached_expand_types(0x4000,22,24,map)==(0x4000u|(1u<<23)));
    assert(venus_uncached_expand_types(0x4100,22,24,map)==(0x4100u|(1u<<23)));
    assert(venus_uncached_expand_types(2,22,24,map)==(2u|(1u<<22)));
    assert(venus_uncached_expand_types(0,22,24,map)==0);
    assert(venus_uncached_expand_types(0x4000,22,22,map)==0x4000);
    assert(venus_uncached_native_index(23,22,24,map)==14);
    assert(venus_uncached_native_index(8,22,24,map)==8);
    assert(venus_uncached_native_index(24,22,24,map)==UINT32_MAX);
    assert(venus_uncached_native_index(0,22,33,map)==UINT32_MAX);
    map[23]=32;
    assert(venus_uncached_native_index(23,22,24,map)==UINT32_MAX);
    assert(venus_uncached_expand_types(0x4000,22,24,map)==0x4000);
}
