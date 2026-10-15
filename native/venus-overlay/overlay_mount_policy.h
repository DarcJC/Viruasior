#pragma once
#include <stdbool.h>
#include <sys/mount.h>

static inline unsigned long overlay_remount_flags(bool stock) {
    /* Only the pinned byte-identical stock profile retains the original APEX's
       SELinux transition semantics. Sentinel mounts keep the extra NOSUID. */
    return MS_BIND | MS_REMOUNT | MS_RDONLY | MS_NODEV | (stock ? 0 : MS_NOSUID);
}
