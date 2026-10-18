#pragma once
#include <stdint.h>

struct venus_ahb_image_layout {
    uint64_t offset, row_pitch, size, array_pitch;
};

/* Bound every advertised guest row/layer, not just tightly packed pixels.
 * Return zero on invalid dimensions or overflow instead of wrapping a size. */
static inline uint64_t venus_ahb_image_required_size(
    uint64_t width, uint64_t height, uint64_t layers,
    const struct venus_ahb_image_layout *layout, uint64_t alignment)
{
    uint64_t row, size, preceding;
    if (!width || !height || !layers || !alignment ||
        __builtin_mul_overflow(width, UINT64_C(4), &row)) return 0;
    if (layout && layout->row_pitch > row) row = layout->row_pitch;
    if (__builtin_mul_overflow(row, height, &size)) return 0;
    if (layout && layout->size > size) size = layout->size;
    uint64_t stride = size;
    if (layout && layout->array_pitch > stride) stride = layout->array_pitch;
    if (__builtin_mul_overflow(layers - 1, stride, &preceding) ||
        __builtin_add_overflow(size, preceding, &size) ||
        (layout && __builtin_add_overflow(size, layout->offset, &size))) return 0;
    const uint64_t remainder = size % alignment;
    if (remainder && __builtin_add_overflow(size, alignment - remainder, &size)) return 0;
    return size;
}
