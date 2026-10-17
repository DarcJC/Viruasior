#include "venus_import_type_policy.h"

#include <assert.h>
#include <stddef.h>

int
main(void)
{
   const struct venus_import_memory_type types[] = {
      { .property_flags = 7, .heap_index = 0 },
      { .property_flags = 11, .heap_index = 0 },
      { .property_flags = 1, .heap_index = 1 },
      { .property_flags = 1, .heap_index = 0 },
   };

   assert(venus_import_native_type(0, false) == 0);
   assert(venus_import_native_type(6, false) == 6);
   assert(venus_import_native_type(0, true) == 0);
   assert(venus_import_native_type(1, true) == 0);
   assert(venus_import_native_type(6, true) == 5);

   /* Keep an already-supported selection. */
   assert(venus_import_select_native_type(0, 1u << 0, 7, types, 4) == 0);
   /* A unique same-heap superset is safe for a device-local-only guest type. */
   assert(venus_import_select_native_type(3, 1u << 1, 1, types, 4) == 1);
   /* Never silently lose the coherent capability promised to the guest. */
   assert(venus_import_select_native_type(0, 1u << 1, 7, types, 4) ==
          VENUS_IMPORT_TYPE_INVALID);
   /* Never cross heaps. */
   assert(venus_import_select_native_type(3, 1u << 2, 1, types, 4) ==
          VENUS_IMPORT_TYPE_INVALID);
   /* Multiple compatible choices are ambiguous without image requirements. */
   assert(venus_import_select_native_type(3, (1u << 0) | (1u << 1), 1,
                                           types, 4) ==
          VENUS_IMPORT_TYPE_INVALID);
   assert(venus_import_select_native_type(4, 1u, 1, types, 4) ==
          VENUS_IMPORT_TYPE_INVALID);
   assert(venus_import_select_native_type(0, 0, 1, types, 4) ==
          VENUS_IMPORT_TYPE_INVALID);
   assert(venus_import_select_native_type(0, 1u, 1, NULL, 4) ==
          VENUS_IMPORT_TYPE_INVALID);

   /* Observed Mali import: guest type 6 maps to native type 5; intersecting
    * FD mask 0x7e02 with image requirements 0x1040 leaves native type 12. */
   struct venus_import_memory_type mali_types[22] = {0};
   for (uint32_t i = 0; i < 21; ++i)
      mali_types[i] = (struct venus_import_memory_type){
         .property_flags = i < 9 ? 7 : 11,
         .heap_index = 0,
      };
   mali_types[21] = (struct venus_import_memory_type){
      .property_flags = 33,
      .heap_index = 1,
   };
   assert(venus_import_select_native_type(5, 0x7e02u & 0x1040u, 1,
                                           mali_types, 22) == 12);

   return 0;
}
