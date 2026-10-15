#define _GNU_SOURCE
#include "overlay_copy.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/wait.h>

int main(void) {
    char name[] = "/tmp/viruaisor-overlay-exec-XXXXXX";
    int source = open("/bin/true", O_RDONLY | O_CLOEXEC), copy = mkstemp(name);
    assert(source >= 0 && copy >= 0 && !unlink(name));
    assert(overlay_copy_identical(source, copy) && !fchmod(copy, 0700));
    char *argv[] = { "overlay-true", NULL }, *env[] = { NULL };
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) {
        fexecve(copy, argv, env);
        _exit(errno == ETXTBSY ? 0 : 1);
    }
    int status;
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(overlay_snapshot_readonly(&copy));
    assert((fcntl(copy, F_GETFL) & O_ACCMODE) == O_RDONLY);
    pid = fork();
    assert(pid >= 0);
    if (!pid) { fexecve(copy, argv, env); _exit(2); }
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(source); close(copy);
    return 0;
}
