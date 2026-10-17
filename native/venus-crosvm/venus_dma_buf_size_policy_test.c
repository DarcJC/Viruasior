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

   assert(venus_dma_buf_blob_size_valid(8192, 8192, 4096, 16384));
   assert(!venus_dma_buf_blob_size_valid(2048, 8192, 4096, 16384));

   assert(venus_dma_buf_blob_size_valid(-1, 4096, 4096, 16384));
   assert(venus_dma_buf_blob_size_valid(0, 8192, 4096, 16384));
   assert(venus_dma_buf_blob_size_valid(-1, 33000, 45056, 16384));
   assert(!venus_dma_buf_blob_size_valid(-1, 32000, 45056, 16384));
   assert(!venus_dma_buf_blob_size_valid(0, 2048, 20000, 16384));
   assert(!venus_dma_buf_blob_size_valid(-1, 4096, 4096, 0));
   assert(!venus_dma_buf_blob_size_valid(-1, 4096, 0, 16384));
   assert(!venus_dma_buf_blob_size_valid(-1, UINT64_MAX - 1, UINT64_MAX, 16384));
   return 0;
}
