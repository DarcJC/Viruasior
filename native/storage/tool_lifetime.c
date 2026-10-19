/* Bound offline ext4 tools to the application process that owns the transaction. */
#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>

__attribute__((constructor)) static void storage_tool_lifetime(void) {
    pid_t parent = getppid();
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent)
        _exit(125);
}
