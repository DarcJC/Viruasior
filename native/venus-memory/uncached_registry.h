#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include "uncached_protocol.h"

/* Caller serializes access. Entries own the lease, while allocations and
 * exported resources each own a reference and a DMA-BUF fd with this identity. */
struct venus_uncached_entry {
    dev_t device;
    ino_t inode;
    uint32_t references;
    /* Renderer IDs never repeat during the renderer process lifetime. Unlike
     * context addresses, they remain valid identities for exported resources. */
    uint32_t owner;
    int lease;
    void *mapping;
    size_t bytes;
};
static inline struct venus_uncached_entry *venus_uncached_find(
    struct venus_uncached_entry *entries, int fd) {
    struct stat st;
    if (fd < 0 || fstat(fd, &st)) return NULL;
    for (unsigned i = 0; i < VENUS_UNCACHED_MAX_LEASES; ++i)
        if (entries[i].references && entries[i].device == st.st_dev &&
            entries[i].inode == st.st_ino) return &entries[i];
    return NULL;
}
static inline bool venus_uncached_register(
    struct venus_uncached_entry *entries, uint32_t owner, int fd, int lease, size_t bytes) {
    struct stat st;
    if (!owner || lease < 0 || !bytes || bytes > VENUS_UNCACHED_MAX_ALLOCATION ||
        fstat(fd, &st) || venus_uncached_find(entries, fd)) return false;
    for (unsigned i = 0; i < VENUS_UNCACHED_MAX_LEASES; ++i) {
        if (!entries[i].references) {
            void *mapping = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (mapping == MAP_FAILED) return false;
            entries[i] = (struct venus_uncached_entry){
                .device=st.st_dev, .inode=st.st_ino, .references=1, .owner=owner,
                .lease=lease, .mapping=mapping, .bytes=bytes};
            return true;
        }
    }
    return false;
}
static inline bool venus_uncached_retain(struct venus_uncached_entry *entries, int fd) {
    struct venus_uncached_entry *entry = venus_uncached_find(entries, fd);
    if (!entry) return true;
    if (entry->references == UINT32_MAX) return false;
    ++entry->references;
    return true;
}
static inline void venus_uncached_entry_release(struct venus_uncached_entry *entry) {
    if (--entry->references == 0) {
        munmap(entry->mapping, entry->bytes);
        close(entry->lease);
        *entry = (struct venus_uncached_entry){0};
    }
}
static inline void venus_uncached_release(struct venus_uncached_entry *entries, int fd) {
    struct venus_uncached_entry *entry = venus_uncached_find(entries, fd);
    if (entry) venus_uncached_entry_release(entry);
}

static inline size_t venus_uncached_owned_bytes(
    const struct venus_uncached_entry *entries, uint32_t owner) {
    size_t bytes = 0;
    for (unsigned i = 0; i < VENUS_UNCACHED_MAX_LEASES; ++i)
        if (entries[i].references && entries[i].owner == owner)
            bytes += entries[i].bytes;
    return bytes;
}

/* Take/drop require the registry mutex. Each reference pins the mapping and
 * helper lease, so cache maintenance can run without that global mutex. The
 * caller serializes sweeps for the same owner separately. A snapshot must be
 * dropped before it is reused. Entries' mapping and bytes stay immutable while
 * any reference exists; the sweep must not read their mutable reference count. */
struct venus_uncached_snapshot {
    struct venus_uncached_entry *entries[VENUS_UNCACHED_MAX_LEASES];
    unsigned count;
};
static inline void venus_uncached_snapshot_drop(struct venus_uncached_snapshot *snapshot) {
    while (snapshot->count)
        venus_uncached_entry_release(snapshot->entries[--snapshot->count]);
}
static inline bool venus_uncached_snapshot_take(
    struct venus_uncached_entry *entries, uint32_t owner,
    struct venus_uncached_snapshot *snapshot) {
    if (!owner || snapshot->count) return false;
    for (unsigned i = 0; i < VENUS_UNCACHED_MAX_LEASES; ++i) {
        if (!entries[i].references || entries[i].owner != owner) continue;
        if (entries[i].references == UINT32_MAX) {
            venus_uncached_snapshot_drop(snapshot);
            return false;
        }
        ++entries[i].references;
        snapshot->entries[snapshot->count++] = &entries[i];
    }
    return true;
}

/* A submit cleans guest CPU writes before GPU reads; a completed fence cleans
 * and invalidates guest aliases before its result is returned to the guest. */
static inline bool venus_uncached_cache_maintain(
    const struct venus_uncached_snapshot *snapshot, bool completed) {
#ifdef __aarch64__
    uint64_t ctr;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    size_t line = (size_t)1 << (((ctr >> 16) & 15) + 2);
    for (unsigned i = 0; i < snapshot->count; ++i) {
        uintptr_t start = (uintptr_t)snapshot->entries[i]->mapping;
        uintptr_t end = start + snapshot->entries[i]->bytes;
        if (end < start) return false;
        for (uintptr_t address = start; address < end; address += line) {
            if (completed)
                __asm__ volatile("dc civac, %0" :: "r"(address) : "memory");
            else
                __asm__ volatile("dc cvac, %0" :: "r"(address) : "memory");
        }
    }
    __asm__ volatile("dsb sy" ::: "memory");
    return true;
#else
    (void)snapshot; (void)completed;
    return false;
#endif
}
