#include <assert.h>
#include <stdint.h>
#include "venus_ahb_registry.h"

int main(void) {
    struct venus_ahb_registry_entry entries[2] = {0};
    int first = 1, second = 2, replacement = 3;

    assert(venus_ahb_registry_add(entries, 2, 11, 21, &first));
    assert(venus_ahb_registry_find(entries, 2, 11, 21) == &first);
    assert(venus_ahb_registry_add(entries, 2, 12, 22, &second));
    assert(!venus_ahb_registry_add(entries, 2, 13, 23, &replacement));
    assert(!venus_ahb_registry_remove(entries, 2, 11, 21, &replacement));
    assert(venus_ahb_registry_remove(entries, 2, 11, 21, &first));
    assert(venus_ahb_registry_find(entries, 2, 11, 21) == 0);
    assert(venus_ahb_registry_add(entries, 2, 13, 23, &replacement));
    assert(venus_ahb_registry_find(entries, 2, 13, 23) == &replacement);

    /* Exporting memory and its DMA-BUF resource own independent references.
     * Destroying the exporter must not invalidate a still-live resource. */
    assert(venus_ahb_registry_add(entries, 2, 13, 23, &replacement));
    assert(venus_ahb_registry_remove(entries, 2, 13, 23, &replacement));
    assert(venus_ahb_registry_find(entries, 2, 13, 23) == &replacement);
    assert(!venus_ahb_registry_add(entries, 2, 13, 23, &first));
    assert(!venus_ahb_registry_remove(entries, 2, 13, 23, &first));
    assert(venus_ahb_registry_remove(entries, 2, 13, 23, &replacement));
    assert(venus_ahb_registry_find(entries, 2, 13, 23) == NULL);
    assert(!venus_ahb_registry_remove(entries, 2, 13, 23, &replacement));
    /* A freed slot may be reused; neither the old inode nor old buffer matches. */
    assert(venus_ahb_registry_add(entries, 2, 13, 24, &first));
    assert(venus_ahb_registry_find(entries, 2, 13, 23) == NULL);
    assert(venus_ahb_registry_remove(entries, 2, 13, 24, &first));
    entries[0] = (struct venus_ahb_registry_entry){13, 25, &first, UINT32_MAX};
    assert(!venus_ahb_registry_add(entries, 2, 13, 25, &first));
    assert(entries[0].references == UINT32_MAX);
    return 0;
}
