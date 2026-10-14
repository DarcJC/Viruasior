#include "desktop_protocol.h"

#include <assert.h>
#include <stddef.h>
#include <string.h>

static void expect_mapping(const char *command, const char *verb, const char *unit) {
    struct desktop_operation operation;
    assert(parse_desktop_operation(command, &operation));
    assert(strcmp(operation.verb, verb) == 0);
    assert(strcmp(operation.unit, unit) == 0);
}

int main(void) {
    expect_mapping("START_DESKTOP 2d", "start", "avf-desktop@2d.service");
    expect_mapping("START_DESKTOP gfxstream", "start", "avf-desktop@gfxstream.service");
    expect_mapping("STOP_DESKTOP 2d", "stop", "avf-desktop@2d.service");
    expect_mapping("STOP_DESKTOP gfxstream", "stop", "avf-desktop@gfxstream.service");
    expect_mapping("START_DESKTOP", "start", "avf-desktop@2d.service");
    expect_mapping("STOP_DESKTOP", "stop", "avf-desktop@2d.service");

    const char *invalid[] = {
        "START_DESKTOP virgl",
        "START_DESKTOP gfxstream --now",
        "START_DESKTOP gfxstream;poweroff",
        "STOP_DESKTOP ",
        "start_desktop gfxstream",
        NULL,
    };
    for (size_t index = 0; invalid[index] != NULL; ++index) {
        struct desktop_operation operation;
        assert(!parse_desktop_operation(invalid[index], &operation));
    }
    return 0;
}
