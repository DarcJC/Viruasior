#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define VENUS_DRM_FORMAT_ARGB8888 UINT32_C(0x34325241)
#define VENUS_DRM_FORMAT_XRGB8888 UINT32_C(0x34325258)

static inline bool
venus_dmabuf_frame_bytes(uint32_t offset, uint32_t stride, uint32_t width,
                         uint32_t height, uint32_t fourcc,
                         uint64_t *out_bytes)
{
   if (!out_bytes || !width || !height || width > 4096 || height > 4096 ||
       (fourcc != VENUS_DRM_FORMAT_ARGB8888 &&
        fourcc != VENUS_DRM_FORMAT_XRGB8888))
      return false;

   const uint64_t row_bytes = (uint64_t)width * 4;
   if (stride < row_bytes)
      return false;

   const uint64_t last_row = (uint64_t)(height - 1) * stride;
   const uint64_t bytes = (uint64_t)offset + last_row + row_bytes;
   if (bytes > UINT64_C(64) * 1024 * 1024)
      return false;

   *out_bytes = bytes;
   return true;
}
