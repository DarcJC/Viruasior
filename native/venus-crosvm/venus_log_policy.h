#pragma once
#include <stdbool.h>
#include <string.h>
static inline bool venus_log_is_verbose(const char *format) {
    static const char *const prefixes[]={
        "VIRUAISOR_AHB_WRITE_COMMAND ", "VIRUAISOR_CACHE_FLUSH ",
        "VIRUAISOR_CACHE_INVALIDATE ", "VIRUAISOR_CONTEXT_SUBMIT ",
        "VIRUAISOR_CACHE_%s ranges=",
        "VIRUAISOR_RING_MONITOR ", "VIRUAISOR_RING_SUBMIT ",
    };
    for(unsigned i=0;i<sizeof(prefixes)/sizeof(prefixes[0]);i++)
        if(!strncmp(format,prefixes[i],strlen(prefixes[i]))) return true;
    return false;
}
