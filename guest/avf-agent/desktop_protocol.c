#include "desktop_protocol.h"

#include <stddef.h>
#include <string.h>
#include <ctype.h>

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

bool parse_resize_command(const char *command, uint32_t *width, uint32_t *height) {
    if (!command || !width || !height || strncmp(command,"RESIZE ",7)) return false;
    const char *cursor=command+7;
    uint32_t values[2]={0};
    for (unsigned i=0;i<2;++i) {
        if (!isdigit((unsigned char)*cursor)) return false;
        unsigned digits=0;
        while (isdigit((unsigned char)*cursor)) {
            if (++digits>4) return false;
            values[i]=values[i]*10+(uint32_t)(*cursor++-'0');
        }
        if (!values[i] || values[i]>4096) return false;
        if (i==0) { if (*cursor++!=' ') return false; }
        else if (*cursor) return false;
    }
    if ((uint64_t)values[0]*values[1]>8388608) return false;
    *width=values[0]; *height=values[1];
    return true;
}

bool parse_resize_rate_command(const char *command, uint32_t *width, uint32_t *height, uint32_t *millihz) {
    if (!command || !width || !height || !millihz || strncmp(command,"RESIZE_RATE ",12)) return false;
    const char *cursor=command+12;
    uint32_t values[3]={0};
    for (unsigned i=0;i<3;++i) {
        if (!isdigit((unsigned char)*cursor)) return false;
        unsigned digits=0;
        while (isdigit((unsigned char)*cursor)) {
            if (++digits>(i==2 ? 6U : 4U)) return false;
            values[i]=values[i]*10+(uint32_t)(*cursor++-'0');
        }
        if (!values[i] || values[i]>(i==2 ? 120250U : 4096U)) return false;
        if (i<2) { if (*cursor++!=' ') return false; }
        else if (*cursor) return false;
    }
    uint64_t pixels=(uint64_t)values[0]*values[1];
    if (pixels>8388608 || values[2]<10000 || (pixels>4194304 && values[2]>60250)) return false;
    *width=values[0]; *height=values[1]; *millihz=values[2];
    return true;
}
