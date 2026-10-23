#include <assert.h>
#include "zink_venus_upload_policy.h"
int main(void) {
    assert(zink_venus_private_upload(true,0x13b5,true,false,"1"));
    assert(!zink_venus_private_upload(true,0x13b5,true,false,NULL));
    assert(!zink_venus_private_upload(true,0x13b5,true,false,"0"));
    assert(!zink_venus_private_upload(true,0x13b5,true,false,"yes"));
    assert(!zink_venus_private_upload(false,0x13b5,true,false,"1"));
    assert(!zink_venus_private_upload(true,0x8086,true,false,"1"));
    assert(!zink_venus_private_upload(true,0x13b5,false,false,"1"));
    assert(!zink_venus_private_upload(true,0x13b5,true,true,"1"));
}
