#include "venus_dma_buf_size_policy.h"

#include <assert.h>

int
main(void)
{
   assert(venus_dma_buf_export_allocation_size(44800, 65536) == 65536);
   assert(venus_dma_buf_export_allocation_size(45376, 65536) == 65536);
   assert(venus_dma_buf_export_allocation_size(3735552, 65536) == 3735552);
   assert(venus_dma_buf_export_allocation_size(0, 65536) == 0);
   assert(venus_dma_buf_export_allocation_size(4096, 0) == 0);
   assert(venus_dma_buf_export_allocation_size(UINT64_MAX - 1, 65536) == 0);

   assert(venus_dma_buf_blob_size_valid(8192, 8192, 4096, 16384, false));
   assert(!venus_dma_buf_blob_size_valid(2048, 8192, 4096, 16384, false));

   /* Android gralloc exposes the pixel plane length while Vulkan reports the
    * same retained AHB allocation at that logical size. The kernel mapping is
    * backed to Mali's 64 KiB granule. */
   assert(venus_dma_buf_blob_size_valid(
      3686400, 3686400, 3735552, 65536, true));
   assert(venus_dma_buf_blob_size_valid(
      225280, 225280, 262144, 65536, true));
   assert(!venus_dma_buf_blob_size_valid(
      3686400, 3735552, 3735552, 65536, false));
   assert(!venus_dma_buf_blob_size_valid(
      3600000, 3735552, 3735552, 65536, true));
   assert(!venus_dma_buf_blob_size_valid(
      0, 0, 3735552, 65536, true));

   assert(venus_dma_buf_blob_size_valid(-1, 4096, 4096, 16384, false));
   assert(venus_dma_buf_blob_size_valid(0, 8192, 4096, 16384, false));
   assert(venus_dma_buf_blob_size_valid(-1, 33000, 45056, 16384, false));
   assert(!venus_dma_buf_blob_size_valid(-1, 32000, 45056, 16384, false));
   assert(!venus_dma_buf_blob_size_valid(0, 2048, 20000, 16384, false));
   assert(!venus_dma_buf_blob_size_valid(-1, 4096, 4096, 0, false));
   assert(!venus_dma_buf_blob_size_valid(-1, 4096, 0, 16384, false));
   assert(!venus_dma_buf_blob_size_valid(
      -1, UINT64_MAX - 1, UINT64_MAX, 16384, false));
   return 0;
}
