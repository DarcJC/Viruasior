#include "cache_policy.h"
#include <string.h>
#include <assert.h>
int main(void) {
    assert(strstr(venus_guest_perf_options(), "no_fence_feedback"));
    assert(strstr(venus_guest_perf_options(), "no_semaphore_feedback"));
    assert(strstr(venus_guest_perf_options(), "no_event_feedback"));
    assert(strstr(venus_guest_perf_options(), "no_query_feedback"));
    assert(venus_guest_memory_flags(11) == 11); /* visible+cached stays noncoherent */
    assert(!(venus_guest_memory_flags(11) & 4));
    assert(venus_guest_memory_flags(7) == 7);
    assert(venus_guest_memory_flags(1) == 1);
    return 0;
}
