#pragma once
#include <cstdint>
#include <cstddef>
#include <memory>
#include <new>
#include "frame_copy.h"
class ScanoutBuffer {
    std::unique_ptr<uint8_t[]> pixels;
    uint32_t width=0,height=0;
    bool ready=false;
public:
    bool configure(uint32_t w,uint32_t h) {
        if (!w || !h || w>4096 || h>4096) return false;
        if (w==width && h==height) return true;
        std::unique_ptr<uint8_t[]> next(new(std::nothrow) uint8_t[(size_t)w*h*4]());
        if (!next) return false;
        pixels=std::move(next); width=w; height=h; ready=false;
        return true;
    }
    uint8_t *data() { return pixels.get(); }
    void finish() { ready=pixels!=nullptr; }
    bool available() const { return ready; }
    bool copyRgba(void *destination,size_t stride) const {
        return ready && venus_copy_bgra(destination,stride,pixels.get(),(size_t)width*4,width,height) &&
            venus_bgra_to_rgba(destination,stride,width,height);
    }
};
