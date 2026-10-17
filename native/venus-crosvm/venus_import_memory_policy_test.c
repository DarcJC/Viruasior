#include "venus_import_memory_policy.h"
#include <assert.h>

int main(void) {
    /* Actual device failure: coherent device-local type 8 is not in 0x7e02. */
    assert(venus_import_memory_properties(8, 7, 0x7e02, 0xfc05) == 1);
    assert(venus_import_memory_properties(9, 7, 0x7e02, 0xfc05) == 7);
    assert(venus_import_memory_properties(1, 6, 0x7e02, 0xfc05) == 6);
    assert(venus_import_memory_properties(15, 11, 0x7e02, 0xfc05) == 11);
    assert(venus_import_memory_properties(8, 11, 0x7e02, 0xfc05) == 1);
    assert(venus_import_memory_properties(31, 6, 0, 0) == 0);
    assert(venus_import_memory_properties(31, 6, 0x80000000u, 0) == 6);
    assert(venus_import_memory_properties(32, 6, 0xffffffffu, 0) == 0);
    /* Native-only/protected/lazy memory stays native-only. */
    assert(venus_import_memory_properties(0, 1, 0, 0) == 1);
    assert(venus_import_memory_properties(3, 17, 0, 0) == 17);
    assert(venus_import_memory_properties(22, 33, 0, 0) == 33);
    for (unsigned type = 0; type < 32; ++type)
        for (unsigned flags = 0; flags < 64; ++flags) {
            unsigned result = venus_import_memory_properties(type, flags, 0x7e02, 0xfc05);
            assert((result & ~flags) == 0); /* never invent capabilities */
            if (result & 2)
                assert(((flags & 8 ? 0xfc05u : 0x7e02u) & (1u << type)) != 0);
        }
    return 0;
}
