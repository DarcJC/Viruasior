#include <assert.h>
#include "venus_log_policy.h"
int main(void) {
    assert(venus_log_is_verbose("VIRUAISOR_AHB_WRITE_COMMAND kind=%s"));
    assert(venus_log_is_verbose("VIRUAISOR_CACHE_FLUSH ranges=%u"));
    assert(venus_log_is_verbose("VIRUAISOR_CACHE_%s ranges=%u"));
    assert(!venus_log_is_verbose("VIRUAISOR_CACHE_FLUSH_FAILED error=%d"));
    assert(!venus_log_is_verbose("VIRUAISOR_DISPLAY_SYNC_GPU_FAILED ctx=%u"));
    assert(!venus_log_is_verbose("VIRUAISOR_DISPLAY_SYNC_READY ctx=%u rings=%u"));
    assert(!venus_log_is_verbose("allocation failed"));
}
