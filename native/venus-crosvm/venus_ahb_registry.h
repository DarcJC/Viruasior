#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct venus_ahb_registry_entry {
    uint64_t device;
    uint64_t inode;
    void *buffer;
    uint32_t references;
};

static inline bool venus_ahb_registry_add(
    struct venus_ahb_registry_entry *entries, size_t count,
    uint64_t device, uint64_t inode, void *buffer) {
    if (!entries || !count || !buffer) return false;
    for (size_t i = 0; i < count; ++i) {
        if (entries[i].buffer && entries[i].device == device &&
            entries[i].inode == inode) {
            if (entries[i].buffer != buffer || entries[i].references == UINT32_MAX)
                return false;
            entries[i].references++;
            return true;
        }
    }
    for (size_t i = 0; i < count; ++i) {
        if (!entries[i].buffer) {
            entries[i] = (struct venus_ahb_registry_entry){device, inode, buffer, 1};
            return true;
        }
    }
    return false;
}

static inline void *venus_ahb_registry_find(
    const struct venus_ahb_registry_entry *entries, size_t count,
    uint64_t device, uint64_t inode) {
    if (!entries) return NULL;
    for (size_t i = 0; i < count; ++i) {
        if (entries[i].buffer && entries[i].device == device &&
            entries[i].inode == inode)
            return entries[i].buffer;
    }
    return NULL;
}

static inline bool venus_ahb_registry_remove(
    struct venus_ahb_registry_entry *entries, size_t count,
    uint64_t device, uint64_t inode, const void *buffer) {
    if (!entries || !buffer) return false;
    for (size_t i = 0; i < count; ++i) {
        if (entries[i].buffer == buffer && entries[i].device == device &&
            entries[i].inode == inode) {
            if (--entries[i].references == 0)
                entries[i] = (struct venus_ahb_registry_entry){0};
            return true;
        }
    }
    return false;
}

#ifdef __ANDROID__
/* Retain the AHB and its registry entry for a resource's owned DMA-BUF fd.
 * A successful lookup of a non-AHB fd returns true with *buffer == NULL.
 * Release before closing that same fd. */
bool viruaisor_ahb_resource_acquire(int fd, void **buffer);
void viruaisor_ahb_resource_release(int fd, void *buffer);
#endif
