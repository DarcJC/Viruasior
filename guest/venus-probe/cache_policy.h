#pragma once
#include <stdint.h>
static inline uint32_t venus_guest_memory_flags(uint32_t flags) {
    /* Paired private renderer implements explicit cache sync. Never fake coherence. */
    return flags;
}
static inline const char *venus_guest_perf_options(void) {
    /* Feedback polling requires coherent shared memory absent on this Mali
     * import path. Use native protocol fence/status queries instead. */
    return "no_fence_feedback,no_semaphore_feedback,no_event_feedback,no_query_feedback";
}
