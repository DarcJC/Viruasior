#include "frame_copy.h"
#include <assert.h>
#include <string.h>
int main(void) {
    uint32_t src[] = {1,2,99,3,4,99}, dst[] = {8,8,8,8,8,8,8,8};
    assert(venus_copy_bgra(dst, 16, src, 12, 2, 2));
    assert(dst[0] == 1 && dst[1] == 2 && dst[2] == 8 && dst[4] == 3 && dst[5] == 4 && dst[7] == 8);
    uint32_t before[8]; memcpy(before, dst, sizeof(dst));
    assert(!venus_copy_bgra(dst, 4, src, 12, 2, 2));
    assert(!venus_copy_bgra(dst, 16, src, 4, 2, 2));
    assert(!venus_copy_bgra(NULL, 16, src, 12, 2, 2));
    assert(!venus_copy_bgra(dst, 16, src, 12, UINT32_MAX, 2));
    assert(!venus_copy_bgra(dst, 16, src, 12, 2, 0));
    assert(!venus_copy_bgra(dst, SIZE_MAX, src, SIZE_MAX, 2, 2));
    assert(!memcmp(before, dst, sizeof(dst)));
    uint32_t colors[]={0xff112233,0x80445566,0x12345678,0xffaabbcc,0xff010203,0x87654321};
    assert(venus_bgra_to_rgba(colors,12,2,2));
    assert(colors[0] == 0xff332211 && colors[1] == 0x80665544);
    assert(colors[3] == 0xffccbbaa && colors[4] == 0xff030201);
    assert(colors[2] == 0x12345678 && colors[5] == 0x87654321);
    assert(!venus_bgra_to_rgba(colors,4,2,2));
    return 0;
}
