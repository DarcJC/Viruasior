#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Mali external images can require more backing after a DMA-BUF crosses a
 * process boundary. Keep the guest-visible blob unchanged while reserving a
 * full GPU page for app-private renderer exports. */
static inline uint64_t
venus_dma_buf_export_allocation_size(uint64_t requested, uint64_t granule)
{
   if (!requested || !granule)
      return 0;
   const uint64_t remainder = requested % granule;
   if (!remainder)
      return requested;
   const uint64_t padding = granule - remainder;
   if (requested > UINT64_MAX - padding)
      return 0;
   return requested + padding;
}

/* Some Android Vulkan drivers export valid DMA-BUFs whose llseek operation is
 * unsupported or reports zero. In that case the Vulkan allocation size is the
 * only size bound available to the app-private renderer. A positive kernel
 * size remains authoritative and must never be overridden by the fallback.
 */
static inline bool
venus_dma_buf_blob_size_valid(int64_t probed_size,
                              uint64_t allocation_size,
                              uint64_t blob_size,
                              uint64_t page_size)
{
   if (!blob_size)
      return false;
   if (probed_size > 0)
      return (uint64_t)probed_size >= blob_size;
   if (!page_size)
      return false;

   const uint64_t remainder = allocation_size % page_size;
   if (!remainder)
      return allocation_size >= blob_size;

   const uint64_t padding = page_size - remainder;
   if (allocation_size > UINT64_MAX - padding)
      return false;
   return allocation_size + padding >= blob_size;
}
