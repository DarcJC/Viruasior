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
    return 0;
}
