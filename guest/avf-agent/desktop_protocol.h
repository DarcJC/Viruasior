#ifndef VIRUAISOR_DESKTOP_PROTOCOL_H
#define VIRUAISOR_DESKTOP_PROTOCOL_H

#include <stdbool.h>
#include <stdint.h>

struct desktop_operation {
    const char *verb;
    const char *unit;
    bool no_block;
};

bool parse_desktop_operation(const char *command, struct desktop_operation *operation);
bool parse_resize_command(const char *command, uint32_t *width, uint32_t *height);
bool parse_resize_rate_command(const char *command, uint32_t *width, uint32_t *height, uint32_t *millihz);

#endif
