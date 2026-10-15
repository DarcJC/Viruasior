#include "vn_cs.h"
#include <assert.h>

int main(void) {
    uint8_t bytes[8] = {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff};
    struct vn_cs_encoder enc = {.data = bytes, .capacity = sizeof(bytes)};
    uint8_t value = 7;
    vn_cs_encoder_write(&enc, 4, &value, 1);
    assert(enc.length == 4 && !enc.fatal);
    assert(bytes[0] == 7 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0);
    assert(!vn_cs_encoder_reserve(&enc, 8));
    vn_cs_encoder_write(&enc, 8, &value, 1);
    assert(enc.fatal && enc.length == 4 && bytes[4] == 0xff);
    struct vn_cs_decoder dec = {.data = bytes, .size = 4};
    uint32_t result = 99;
    vn_cs_decoder_read(&dec, 4, &result, 4);
    assert(result == 7 && dec.offset == 4 && !dec.fatal);
    vn_cs_decoder_read(&dec, 4, &result, 4);
    assert(dec.fatal);
    void *handle = (void *)(uintptr_t)0x12345678;
    assert(vn_cs_handle_load_id((const void **)&handle, VK_OBJECT_TYPE_BUFFER) == 0x12345678);
    vn_cs_handle_store_id(&handle, 42, VK_OBJECT_TYPE_BUFFER);
    assert((uintptr_t)handle == 42);
    return 0;
}
