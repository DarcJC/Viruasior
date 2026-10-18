#pragma once
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
static inline bool venus_log_trace_enabled(void) {
    const char *trace = getenv("VIRUAISOR_GRAPHICS_TRACE");
    return trace && !strcmp(trace, "1");
}
static inline bool venus_log_is_verbose(const char *format) {
    static const char *const prefixes[]={
        "VIRUAISOR_AHB_WRITE_COMMAND ", "VIRUAISOR_CACHE_FLUSH ",
        "VIRUAISOR_CACHE_INVALIDATE ", "VIRUAISOR_CONTEXT_SUBMIT ",
        "VIRUAISOR_CACHE_%s ranges=",
        "VIRUAISOR_RING_MONITOR ", "VIRUAISOR_RING_SUBMIT ",
        "VIRUAISOR_AHB_GUEST_LAYOUT_QUERY ", "VIRUAISOR_DMA_BUF_FD ",
        "VIRUAISOR_GRALLOC_FD ", "VIRUAISOR_BUFFER_EXTERNAL_DMA_BUF ",
        "VIRUAISOR_MEMORY_PUBLISH_READY ", "VIRUAISOR_BLOB_EXPORT_READY ",
        "VIRUAISOR_IMAGE_CANDIDATE ", "VIRUAISOR_GRALLOC_IMPORT ",
        "VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW ",
    };
    for(unsigned i=0;i<sizeof(prefixes)/sizeof(prefixes[0]);i++)
        if(!strncmp(format,prefixes[i],strlen(prefixes[i]))) return true;
    return false;
}
