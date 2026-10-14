#ifndef VIRUAISOR_DESKTOP_PROTOCOL_H
#define VIRUAISOR_DESKTOP_PROTOCOL_H

#include <stdbool.h>

struct desktop_operation {
    const char *verb;
    const char *unit;
    bool no_block;
};

bool parse_desktop_operation(const char *command, struct desktop_operation *operation);

#endif
