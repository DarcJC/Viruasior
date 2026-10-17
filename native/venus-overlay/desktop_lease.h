#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
static inline bool overlay_desktop_identity(uint32_t expected, uint32_t actual, const char *name, uint64_t caps) {
    return expected % 100000 >= 10000 && expected % 100000 <= 19999 && expected == actual &&
        name && !strcmp(name,"crosvm_default") && caps == 0;
}
static inline bool overlay_desktop_heartbeat(char previous, char next) {
    return (previous == '0' || previous == '2' || previous == '3') &&
        (next == '2' || next == '3') && previous != next;
}
