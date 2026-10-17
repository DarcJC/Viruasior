#pragma once

#include <stdbool.h>
#include <stdint.h>

#define VENUS_IMPORT_TYPE_INVALID UINT32_MAX

struct venus_import_memory_type {
   uint32_t property_flags;
   uint32_t heap_index;
};

/* Convert the guest-visible index back to the native Vulkan index used by
 * vkAllocateMemory.  Virglrenderer inserts one synthetic type at index zero
 * when mem_type_spoof is enabled; both guest indices zero and one map to the
 * native type zero. */
static inline uint32_t
venus_import_native_type(uint32_t guest_type, bool mem_type_spoof)
{
   return mem_type_spoof && guest_type > 0 ? guest_type - 1 : guest_type;
}

/* Select a native type accepted by the imported DMA-BUF while preserving the
 * heap and every property promised by the guest-visible type.  A remap is
 * allowed only when exactly one compatible native type exists.  Ambiguity,
 * out-of-range input, or capability loss fails closed. */
static inline uint32_t
venus_import_select_native_type(
   uint32_t selected_native_type,
   uint32_t compatible_type_bits,
   uint32_t required_property_flags,
   const struct venus_import_memory_type *native_types,
   uint32_t native_type_count)
{
   if (!native_types || selected_native_type >= native_type_count ||
       selected_native_type >= 32 || !compatible_type_bits)
      return VENUS_IMPORT_TYPE_INVALID;

   if (compatible_type_bits & (UINT32_C(1) << selected_native_type))
      return selected_native_type;

   const uint32_t selected_heap = native_types[selected_native_type].heap_index;
   uint32_t candidate = VENUS_IMPORT_TYPE_INVALID;
   for (uint32_t i = 0; i < native_type_count && i < 32; ++i) {
      if (!(compatible_type_bits & (UINT32_C(1) << i)))
         continue;
      if (native_types[i].heap_index != selected_heap)
         continue;
      if ((native_types[i].property_flags & required_property_flags) !=
          required_property_flags)
         continue;
      if (candidate != VENUS_IMPORT_TYPE_INVALID)
         return VENUS_IMPORT_TYPE_INVALID;
      candidate = i;
   }

   return candidate;
}
