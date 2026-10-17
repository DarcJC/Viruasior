#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
static inline bool venus_bgra_to_rgba(void *pixels,size_t stride,uint32_t width,uint32_t height) {
    if (!pixels || !width || !height || width>4096 || height>4096 || stride<(size_t)width*4 ||
        (height>1 && stride>(SIZE_MAX-(size_t)width*4)/(height-1))) return false;
    for (uint32_t y=0;y<height;++y) for (uint32_t x=0;x<width;++x) {
        uint8_t *pixel=(uint8_t *)pixels+y*stride+x*4;
        uint8_t blue=pixel[0]; pixel[0]=pixel[2]; pixel[2]=blue;
    }
    return true;
}
static inline bool venus_copy_bgra(void *dst, size_t dst_stride, const void *src,
                                   size_t src_stride, uint32_t width, uint32_t height) {
    if (!dst || !src || !width || !height || width > 4096 || height > 4096) return false;
    size_t row = (size_t)width * 4;
    if (dst_stride < row || src_stride < row ||
        (height > 1 && (dst_stride > (SIZE_MAX-row)/(height-1) || src_stride > (SIZE_MAX-row)/(height-1)))) return false;
    for (uint32_t y=0; y<height; ++y)
        memcpy((uint8_t *)dst + y*dst_stride, (const uint8_t *)src + y*src_stride, row);
    return true;
}
