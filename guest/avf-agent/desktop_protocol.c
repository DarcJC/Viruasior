#include "desktop_protocol.h"

#include <stddef.h>
#include <string.h>

struct desktop_mapping {
    const char *command;
    const char *verb;
    const char *unit;
    bool no_block;
};

static const struct desktop_mapping mappings[] = {
    {"START_DESKTOP venus", "start", "avf-plasma.service", false},
    {"STOP_DESKTOP venus", "stop", "avf-plasma.service", false},
    {"START_DESKTOP", "start", "avf-desktop@2d.service", false},
    {"START_DESKTOP 2d", "start", "avf-desktop@2d.service", false},
    {"START_DESKTOP gfxstream", "start", "avf-desktop@gfxstream.service", false},
    {"STOP_DESKTOP", "stop", "avf-desktop@2d.service", false},
    {"STOP_DESKTOP 2d", "stop", "avf-desktop@2d.service", false},
    {"STOP_DESKTOP gfxstream", "stop", "avf-desktop@gfxstream.service", false},
};

bool parse_desktop_operation(const char *command, struct desktop_operation *operation) {
    if (command == NULL || operation == NULL) return false;
    for (size_t index = 0; index < sizeof(mappings) / sizeof(mappings[0]); ++index) {
        if (strcmp(command, mappings[index].command) == 0) {
            operation->verb = mappings[index].verb;
            operation->unit = mappings[index].unit;
            operation->no_block = mappings[index].no_block;
            return true;
        }
    }
    return false;
}
