#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct venus_ahb_registry_entry {
    uint64_t device;
    uint64_t inode;
    void *buffer;
};

static inline bool venus_ahb_registry_add(
    struct venus_ahb_registry_entry *entries, size_t count,
    uint64_t device, uint64_t inode, void *buffer) {
    if (!entries || !count || !buffer) return false;
    for (size_t i = 0; i < count; ++i) {
        if (entries[i].buffer && entries[i].device == device &&
            entries[i].inode == inode)
            return entries[i].buffer == buffer;
    }
    for (size_t i = 0; i < count; ++i) {
        if (!entries[i].buffer) {
            entries[i] = (struct venus_ahb_registry_entry){device, inode, buffer};
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
            entries[i] = (struct venus_ahb_registry_entry){0};
            return true;
        }
    }
    return false;
}
