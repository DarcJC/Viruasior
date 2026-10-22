#define _GNU_SOURCE
#include "uncached_registry.h"
#include <assert.h>
#include <fcntl.h>
#include <sys/mman.h>
int main(void) {
    struct venus_uncached_entry entries[VENUS_UNCACHED_MAX_LEASES] = {0};
    int context_a, context_b;
    int memory = memfd_create("memory", MFD_CLOEXEC), pipefd[2];
    assert(memory >= 0 && !pipe(pipefd));
    assert(ftruncate(memory, 65536) == 0);
    assert(venus_uncached_register(entries, &context_a, memory, pipefd[1], 65536));
    assert(venus_uncached_find(entries, memory)->bytes == 65536);
    assert(venus_uncached_find(entries, memory)->owner == &context_a);
    assert(venus_uncached_owned_bytes(entries, &context_a) == 65536);
    assert(venus_uncached_owned_bytes(entries, &context_b) == 0);
    int resource = dup(memory);
    assert(resource >= 0 && venus_uncached_retain(entries, resource));
    venus_uncached_release(entries, memory);
    close(memory);
    assert(fcntl(pipefd[1], F_GETFD) >= 0);
    assert(venus_uncached_find(entries, resource)->references == 1);
    venus_uncached_release(entries, resource);
    assert(fcntl(pipefd[1], F_GETFD) < 0);
    assert(!venus_uncached_find(entries, resource));
    close(resource); close(pipefd[0]);
    return 0;
}
