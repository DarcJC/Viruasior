#define _GNU_SOURCE
#include "uncached_registry.h"
#include <assert.h>
#include <fcntl.h>
#include <sys/mman.h>
int main(void) {
    struct venus_uncached_entry entries[VENUS_UNCACHED_MAX_LEASES] = {0};
    const uint32_t context_a = 1, context_b = 2;
    int memory = memfd_create("memory", MFD_CLOEXEC), pipefd[2];
    assert(memory >= 0 && !pipe(pipefd));
    assert(ftruncate(memory, 65536) == 0);
    assert(venus_uncached_register(entries, context_a, memory, pipefd[1], 65536));
    assert(venus_uncached_find(entries, memory)->bytes == 65536);
    assert(venus_uncached_find(entries, memory)->owner == context_a);
    assert(venus_uncached_owned_bytes(entries, context_a) == 65536);
    assert(venus_uncached_owned_bytes(entries, context_b) == 0);
    int resource = dup(memory);
    assert(resource >= 0 && venus_uncached_retain(entries, resource));
    venus_uncached_release(entries, memory);
    close(memory);
    assert(fcntl(pipefd[1], F_GETFD) >= 0);
    assert(venus_uncached_find(entries, resource)->references == 1);
    struct venus_uncached_snapshot snapshot = {0};
    assert(venus_uncached_snapshot_take(entries, context_a, &snapshot));
    assert(snapshot.count == 1);
    assert(venus_uncached_find(entries, resource)->references == 2);
    venus_uncached_release(entries, resource);
    close(resource);
    /* A sweep keeps its mapping and helper lease alive after resource release. */
    assert(fcntl(pipefd[1], F_GETFD) >= 0);
    *(volatile uint32_t *)snapshot.entries[0]->mapping = 0x12345678;
#ifdef __aarch64__
    assert(venus_uncached_cache_maintain(&snapshot, false));
    assert(venus_uncached_cache_maintain(&snapshot, true));
#endif
    assert(*(volatile uint32_t *)snapshot.entries[0]->mapping == 0x12345678);

    int other = memfd_create("other-owner", MFD_CLOEXEC), other_pipe[2];
    assert(other >= 0 && !pipe(other_pipe));
    assert(!ftruncate(other, 4096));
    assert(venus_uncached_register(entries, context_b, other, other_pipe[1], 4096));
    assert(venus_uncached_owned_bytes(entries, context_b) == 4096);
    struct venus_uncached_entry *other_entry = venus_uncached_find(entries, other);
    /* Simulate saturation after one entry has already been pinned. */
    other_entry->owner = context_a;
    other_entry->references = UINT32_MAX;
    struct venus_uncached_snapshot failed = {0};
    assert(!venus_uncached_snapshot_take(entries, context_a, &failed));
    assert(failed.count == 0);
    assert(snapshot.entries[0]->references == 1);
    assert(other_entry->references == UINT32_MAX);
    other_entry->owner = context_b;
    other_entry->references = 1;
    /* An unrelated context can finish while A's sweep is still in progress. */
    venus_uncached_release(entries, other);
    assert(fcntl(other_pipe[1], F_GETFD) < 0);
    assert(fcntl(pipefd[1], F_GETFD) >= 0);
    close(other); close(other_pipe[0]);

    venus_uncached_snapshot_drop(&snapshot);
    assert(snapshot.count == 0);
    assert(fcntl(pipefd[1], F_GETFD) < 0);
    assert(venus_uncached_owned_bytes(entries, context_a) == 0);
    close(pipefd[0]);
    return 0;
}
