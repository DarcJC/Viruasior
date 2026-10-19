#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "present_clock.h"
int main(void)
{
    struct present_clock clock = {0};
    uint64_t now = 900000000000ull;
    present_clock_set(&clock, now, 8333333);
    uint64_t first = present_clock_msc(&clock, now);
    assert(present_clock_msc(&clock, now + 8333333) == first + 1);
    assert(present_clock_msc(&clock, now + 8333332) == first);
    assert(present_clock_deadline(&clock, first + 1) == now + 8333333);
    assert(present_clock_delay_ms(&clock, now, first + 1) == 9);
    assert(present_clock_delay_ms(&clock, now + 8000000, first + 1) == 1);

    now += 8333333 * 12ull;
    uint64_t before = present_clock_msc(&clock, now);
    present_clock_set(&clock, now, 16666667);
    assert(present_clock_msc(&clock, now) == before);
    assert(present_clock_msc(&clock, now + 16666666) == before);
    assert(present_clock_msc(&clock, now + 16666667) == before + 1);
    assert(present_clock_deadline(&clock, before + 1) == now + 16666667);
    /* A repeated query cannot move a pending frame's deadline. */
    present_clock_set(&clock, now + 1000000, 16666667);
    assert(present_clock_deadline(&clock, before + 1) == now + 16666667);

    now += 16666667 * 9ull;
    before = present_clock_msc(&clock, now);
    present_clock_set(&clock, now, 8333333);
    assert(present_clock_msc(&clock, now) == before);
    assert(present_clock_msc(&clock, now + 8333333) == before + 1);
    assert(present_clock_delay_ms(&clock, now + 8333333, before + 1) == 0);
    assert(present_clock_delay_ms(&clock, now, UINT64_MAX) == INT32_MAX);
    puts("PASS monotonic mode changes, absolute deadlines, no early wake, bounded long waits");
}
