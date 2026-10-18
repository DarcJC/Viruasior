#include "desktop_protocol.h"

#include <assert.h>
#include <stddef.h>
#include <string.h>

static void expect_mapping(const char *command, const char *verb, const char *unit, bool no_block) {
    struct desktop_operation operation;
    assert(parse_desktop_operation(command, &operation));
    assert(strcmp(operation.verb, verb) == 0);
    assert(strcmp(operation.unit, unit) == 0);
    assert(operation.no_block == no_block);
}

int main(void) {
    uint32_t width=0,height=0;
    assert(parse_resize_command("RESIZE 1920 1080", &width, &height));
    assert(width==1920 && height==1080);
    uint32_t rate=0;
    assert(parse_resize_rate_command("RESIZE_RATE 1920 1200 120000", &width, &height, &rate));
    assert(width==1920 && height==1200 && rate==120000);
    assert(parse_resize_rate_command("RESIZE_RATE 3408 2272 60000", &width, &height, &rate));
    const char *bad_rates[]={"RESIZE_RATE 3408 2272 120000", "RESIZE_RATE 1920 1200 0",
        "RESIZE_RATE 1920 1200 120000;reboot", "RESIZE_RATE 1920 1200 999999999999",
        "RESIZE_RATE 4096 4096 60000", "RESIZE_RATE 1920 1200 -60000"};
    for (size_t i=0;i<sizeof(bad_rates)/sizeof(bad_rates[0]);++i)
        assert(!parse_resize_rate_command(bad_rates[i], &width, &height, &rate));
    const char *bad_sizes[]={"RESIZE 0 720", "RESIZE -1 720", "RESIZE 4097 1",
        "RESIZE 4096 4096", "RESIZE 1280 720; reboot", "RESIZE 1280 720 ",
        "RESIZE 1 999999999999999999999", "RESIZE +1 720", "RESIZE 1 2 3"};
    for (size_t i=0;i<sizeof(bad_sizes)/sizeof(bad_sizes[0]);++i)
        assert(!parse_resize_command(bad_sizes[i], &width, &height));
    expect_mapping("START_DESKTOP venus", "start", "avf-plasma.service", false);
    expect_mapping("STOP_DESKTOP venus", "stop", "avf-plasma.service", false);
    expect_mapping("START_DESKTOP 2d", "start", "avf-desktop@2d.service", false);
    expect_mapping("START_DESKTOP gfxstream", "start", "avf-desktop@gfxstream.service", false);
    expect_mapping("STOP_DESKTOP 2d", "stop", "avf-desktop@2d.service", false);
    expect_mapping("STOP_DESKTOP gfxstream", "stop", "avf-desktop@gfxstream.service", false);
    expect_mapping("START_DESKTOP", "start", "avf-desktop@2d.service", false);
    expect_mapping("STOP_DESKTOP", "stop", "avf-desktop@2d.service", false);

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
