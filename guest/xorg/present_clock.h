#ifndef VIRUAISOR_PRESENT_CLOCK_H
#define VIRUAISOR_PRESENT_CLOCK_H

#include <stdint.h>

/* A single-output Present clock for a driver without a usable DRM MSC.
 * Call set with a nonzero period before reading, using one monotonic clock. */
struct present_clock {
    uint64_t epoch_ns;
    uint64_t epoch_msc;
    uint64_t period_ns;
};

static inline uint64_t
present_clock_msc(const struct present_clock *clock, uint64_t now)
{
    return clock->epoch_msc + (now - clock->epoch_ns) / clock->period_ns;
}

static inline void
present_clock_set(struct present_clock *clock, uint64_t now, uint64_t period)
{
    /* Reading the current mode must not postpone an already queued frame. */
    if (clock->period_ns == period)
        return;
    uint64_t msc = clock->period_ns ? present_clock_msc(clock, now) : 0;
    clock->epoch_ns = now;
    clock->epoch_msc = msc;
    clock->period_ns = period;
}

static inline uint64_t
present_clock_deadline(const struct present_clock *clock, uint64_t msc)
{
    if (msc < clock->epoch_msc)
        return clock->epoch_ns;
    __uint128_t target = (__uint128_t) clock->epoch_ns +
        (__uint128_t) (msc - clock->epoch_msc) * clock->period_ns;
    return target > UINT64_MAX ? UINT64_MAX : (uint64_t) target;
}

static inline uint32_t
present_clock_delay_ms(const struct present_clock *clock, uint64_t now, uint64_t msc)
{
    uint64_t target = present_clock_deadline(clock, msc);
    if (target <= now)
        return 0;
    uint64_t delta = target - now;
    uint64_t ms = delta / 1000000 + (delta % 1000000 != 0);
    /* Xorg compares timer deadlines with signed 32-bit differences. The
     * callback rechecks MSC, so very distant targets can safely wait again. */
    return ms > INT32_MAX ? INT32_MAX : (uint32_t) ms;
}

#endif
