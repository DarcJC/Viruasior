#include <assert.h>
#include <stddef.h>
#include "venus_ahb_image_size.h"

int main(void)
{
    /* The real 900x600 Wayland window failed its DMA-BUF length validation. */
    struct venus_ahb_image_layout layout = {.row_pitch = 3648, .size = 2188800};
    assert(venus_ahb_image_required_size(900, 600, 1, &layout, 4096) == 2191360);
    assert(venus_ahb_image_required_size(1920, 1200, 1, NULL, 4096) == 9216000);
    assert(venus_ahb_image_required_size(64, 64, 1, NULL, 4096) == 16384);
    layout = (struct venus_ahb_image_layout){.offset = 4096, .row_pitch = 256,
                                          .size = 16384, .array_pitch = 32768};
    assert(venus_ahb_image_required_size(64, 64, 2, &layout, 4096) == 53248);
    assert(venus_ahb_image_required_size(64, 64, 2, &layout, 65536) == 65536);
    layout.size = 20000;
    assert(venus_ahb_image_required_size(64, 64, 1, &layout, 4096) == 24576);
    assert(!venus_ahb_image_required_size(0, 64, 1, NULL, 4096));
    assert(!venus_ahb_image_required_size(64, 0, 1, NULL, 4096));
    assert(!venus_ahb_image_required_size(64, 64, 0, NULL, 4096));
    assert(!venus_ahb_image_required_size(64, 64, 1, NULL, 0));
    assert(!venus_ahb_image_required_size(UINT64_MAX, 1, 1, NULL, 4096));
    assert(!venus_ahb_image_required_size(1, UINT64_MAX, 1, NULL, 4096));
    assert(!venus_ahb_image_required_size(64, 64, UINT64_MAX, NULL, 4096));
    layout.offset = UINT64_MAX;
    assert(!venus_ahb_image_required_size(64, 64, 1, &layout, 4096));
    layout = (struct venus_ahb_image_layout){.size = UINT64_MAX};
    assert(!venus_ahb_image_required_size(1, 1, 1, &layout, 4096));
}
