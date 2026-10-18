#include <assert.h>
#include "zink_venus_dmabuf_policy.h"
int main(void) {
    const uint64_t invalid=(UINT64_C(1)<<56)-1;
    assert(zink_venus_linear_dmabuf(false,true,0x13b5,invalid));
    assert(zink_venus_linear_dmabuf(false,true,0x13b5,0));
    assert(!zink_venus_linear_dmabuf(false,true,0x13b5,1));
    assert(!zink_venus_linear_dmabuf(true,true,0x13b5,0));
    assert(!zink_venus_linear_dmabuf(false,false,0x13b5,0));
    assert(!zink_venus_linear_dmabuf(false,true,0x8086,0));
}
