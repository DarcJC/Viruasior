#define _GNU_SOURCE
#define main overlay_probe_main
#include "overlay_probe.c"
#undef main
#include <assert.h>
#include <sys/wait.h>

static pid_t spawn_child(bool running) {
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        if (running) for (;;) pause();
        _exit(0);
    }
    return child;
}

static int process_handle(pid_t child) {
    int fd = (int)syscall(SYS_pidfd_open, child, 0);
    assert(fd >= 0);
    return fd;
}

int main(void) {
    // Exercise real pidfds and processes: a stopped VM, its idle manager, and an
    // unrelated process. Normal release must preserve the app and the bystander.
    pid_t unrelated = spawn_child(true);
    int unrelated_fd = process_handle(unrelated);
    for (int cycle = 0; cycle < 3; ++cycle) {
        pid_t vm = spawn_child(false), manager = spawn_child(true);
        struct stock_children children = {
            .crosvm_fd = process_handle(vm), .virtmgr_fd = process_handle(manager),
        };
        assert(release_stock_lease(&children));
        struct pollfd exited = { .fd = children.virtmgr_fd, .events = POLLIN };
        assert(poll(&exited, 1, 0) == 1 && (exited.revents & POLLIN));
        struct pollfd alive = { .fd = unrelated_fd, .events = POLLIN };
        assert(poll(&alive, 1, 0) == 0);
        assert(waitpid(vm, NULL, 0) == vm);
        assert(waitpid(manager, NULL, 0) == manager);
        close(children.crosvm_fd); close(children.virtmgr_fd);
    }

    // Releasing a still-running VM must fail without killing it or its manager.
    pid_t vm = spawn_child(true), manager = spawn_child(true);
    struct stock_children children = {
        .crosvm_fd = process_handle(vm), .virtmgr_fd = process_handle(manager),
    };
    assert(!release_stock_lease(&children));
    struct pollfd live[] = {
        { .fd = children.crosvm_fd, .events = POLLIN },
        { .fd = children.virtmgr_fd, .events = POLLIN },
    };
    assert(poll(live, 2, 0) == 0);
    struct stock_children missing = { .crosvm_fd = -1, .virtmgr_fd = -1 };
    assert(!release_stock_lease(&missing));
    kill(vm, SIGKILL); kill(manager, SIGKILL); kill(unrelated, SIGKILL);
    assert(waitpid(vm, NULL, 0) == vm);
    assert(waitpid(manager, NULL, 0) == manager);
    assert(waitpid(unrelated, NULL, 0) == unrelated);
    close(children.crosvm_fd); close(children.virtmgr_fd); close(unrelated_fd);
    return 0;
}
