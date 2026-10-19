#define _GNU_SOURCE
#include "storage_control.h"
#include <inttypes.h>
#include <mntent.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>

bool storage_mount_matches(FILE *mounts) {
    struct mntent *entry;
    while ((entry = getmntent(mounts))) {
        if (!strcmp(entry->mnt_dir, "/mnt/shared") &&
            !strcmp(entry->mnt_fsname, "viruaisor-storage-0") &&
            !strcmp(entry->mnt_type, "virtiofs")) return true;
    }
    return false;
}

static bool mounted(void) {
    FILE *mounts = fopen("/proc/self/mounts", "re");
    if (!mounts) return false;
    bool result = storage_mount_matches(mounts);
    fclose(mounts);
    return result;
}

int storage_status(char *output, size_t capacity) {
    struct stat root;
    if (stat("/", &root)) return -1;
    char path[128];
    snprintf(path, sizeof(path), "/sys/dev/block/%u:%u/size", major(root.st_dev), minor(root.st_dev));
    FILE *size = fopen(path, "re");
    if (!size) return -1;
    uint64_t sectors = 0;
    int scanned = fscanf(size, "%" SCNu64, &sectors);
    fclose(size);
    if (scanned != 1 || !sectors || sectors > UINT64_MAX / 512) return -1;
    int written = snprintf(output, capacity, "OK STORAGE %" PRIu64 " %d\n", sectors * 512, mounted() ? 1 : 0);
    return written > 0 && (size_t)written < capacity ? 0 : -1;
}
