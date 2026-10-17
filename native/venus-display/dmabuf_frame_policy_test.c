#include "dmabuf_frame_policy.h"

#include <assert.h>
#include <stdint.h>

int
main(void)
{
   uint64_t bytes = 0;
   assert(venus_dmabuf_frame_bytes(0, 5120, 1280, 720, 0x34325241u,
                                   &bytes));
   assert(bytes == UINT64_C(3686400));
   assert(venus_dmabuf_frame_bytes(4096, 5120, 1280, 720,
                                   0x34325258u, &bytes));
   assert(bytes == UINT64_C(3690496));

   assert(!venus_dmabuf_frame_bytes(0, 5119, 1280, 720,
                                    0x34325241u, &bytes));
   assert(!venus_dmabuf_frame_bytes(0, 5120, 1280, 720,
                                    0x34324241u, &bytes));
   assert(!venus_dmabuf_frame_bytes(0, 5120, 0, 720,
                                    0x34325241u, &bytes));
   assert(!venus_dmabuf_frame_bytes(0, 5120, 1280, 0,
                                    0x34325241u, &bytes));
   assert(!venus_dmabuf_frame_bytes(0, UINT32_MAX, 4096, 4096,
                                    0x34325241u, NULL));
   return 0;
}
