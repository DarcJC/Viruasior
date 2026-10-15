#define _GNU_SOURCE
#include "overlay_wait.h"
#include <assert.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static long long millis(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
int main(void) {
    pid_t a = fork(); assert(a >= 0);
    if (!a) { usleep(80000); _exit(0); }
    pid_t b = fork(); assert(b >= 0);
    if (!b) { usleep(300000); _exit(0); }
    int x = syscall(SYS_pidfd_open, a, 0), y = syscall(SYS_pidfd_open, b, 0);
    assert(x >= 0 && y >= 0);
    struct pollfd fds[] = {{.fd=x,.events=POLLIN},{.fd=y,.events=POLLIN}};
    long long started = millis();
    assert(overlay_wait_terminated(fds, 2, 1000));
    assert(millis() - started >= 250);
    assert(waitpid(a, NULL, 0) == a && waitpid(b, NULL, 0) == b);
    close(x); close(y);
    struct pollfd absent = {.fd=-1,.events=POLLIN};
    assert(overlay_wait_terminated(&absent, 1, 10));
    return 0;
}
