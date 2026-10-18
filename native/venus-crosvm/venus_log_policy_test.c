#include <assert.h>
#include "venus_log_policy.h"
int main(void) {
    assert(venus_log_is_verbose("VIRUAISOR_AHB_WRITE_COMMAND kind=%s"));
    assert(venus_log_is_verbose("VIRUAISOR_CACHE_FLUSH ranges=%u"));
    assert(venus_log_is_verbose("VIRUAISOR_CACHE_%s ranges=%u"));
    assert(venus_log_is_verbose("VIRUAISOR_AHB_GUEST_LAYOUT_QUERY api=v2 row=%u"));
    assert(venus_log_is_verbose("VIRUAISOR_GRALLOC_FD index=%d"));
    assert(venus_log_is_verbose("VIRUAISOR_MEMORY_PUBLISH_READY object=%u"));
    assert(venus_log_is_verbose("VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW guest=%u"));
    assert(!venus_log_is_verbose("VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW_FAILED vk=%d"));
    assert(!venus_log_is_verbose("VIRUAISOR_GRALLOC_IMPORT_FAILED vk=%d"));
    assert(!venus_log_is_verbose("VIRUAISOR_BLOB_EXPORT_REJECT res=%u"));
    assert(!venus_log_is_verbose("VIRUAISOR_CACHE_FLUSH_FAILED error=%d"));
    assert(!venus_log_is_verbose("VIRUAISOR_DISPLAY_SYNC_GPU_FAILED ctx=%u"));
    assert(!venus_log_is_verbose("VIRUAISOR_DISPLAY_SYNC_READY ctx=%u rings=%u"));
    assert(!venus_log_is_verbose("allocation failed"));
}
