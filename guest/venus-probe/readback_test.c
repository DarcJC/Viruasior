#include "readback.h"
#include <assert.h>
int main(void) {
    const uint32_t seeds[] = {7, 0x12345678, 0xfedcba98};
    uint32_t words[256];
    for (size_t s = 0; s < 3; ++s) {
        for (size_t i = 0; i < 256; ++i) words[i] = venus_input(seeds[s], (uint32_t)i);
        assert(!venus_check_readback(words, 256, seeds[s]));
        for (size_t i = 0; i < 256; ++i) words[i] = venus_expected(seeds[s], (uint32_t)i);
        assert(venus_check_readback(words, 256, seeds[s]));
        assert(!venus_check_readback(words, 256, seeds[(s+1)%3]));
        words[255] ^= 1;
        assert(!venus_check_readback(words, 256, seeds[s]));
    }
    return 0;
}
