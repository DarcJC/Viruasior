#include <assert.h>
#include "overlay_mount_policy.h"

int main(void) {
    unsigned long sentinel = overlay_remount_flags(false);
    unsigned long stock = overlay_remount_flags(true);
    const unsigned long required = MS_BIND | MS_REMOUNT | MS_RDONLY | MS_NODEV;
    assert((sentinel & required) == required);
    assert((stock & required) == required);
    assert(sentinel & MS_NOSUID);
    assert(!(stock & MS_NOSUID));
    assert(!(stock & MS_NOEXEC));
    assert((sentinel ^ stock) == MS_NOSUID);
    return 0;
}
