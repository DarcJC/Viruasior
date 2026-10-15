#pragma once
#include <stdbool.h>
#include <poll.h>
#include <errno.h>
#include <time.h>

static inline bool overlay_wait_terminated(struct pollfd *fds, unsigned count, int timeout_ms) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return false;
    long long deadline = (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000 + timeout_ms;
    for (;;) {
        bool pending = false;
        for (unsigned i = 0; i < count; ++i) if (fds[i].fd >= 0) pending = true;
        if (!pending) return true;
        if (clock_gettime(CLOCK_MONOTONIC, &t)) return false;
        long long remaining = deadline - ((long long)t.tv_sec * 1000 + t.tv_nsec / 1000000);
        if (remaining <= 0) return false;
        int result = poll(fds, count, (int)remaining);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) return false;
        for (unsigned i = 0; i < count; ++i) {
            if (fds[i].revents & (POLLERR | POLLNVAL)) return false;
            if (fds[i].revents & (POLLIN | POLLHUP)) fds[i].fd = -1;
        }
    }
}
