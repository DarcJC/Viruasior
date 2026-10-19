#define _GNU_SOURCE
#include "fs_process.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <linux/fs.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int storage_exchange(const char *first, const char *second) {
    if (!first || !second || !*first || !*second) return EINVAL;
    return syscall(SYS_renameat2,AT_FDCWD,first,AT_FDCWD,second,RENAME_EXCHANGE) ? errno : 0;
}

static int await_exit(int fd, int timeout_ms) {
    struct pollfd descriptor = {.fd=fd, .events=POLLIN};
    int result;
    do { result = poll(&descriptor, 1, timeout_ms); } while (result < 0 && errno == EINTR);
    if (result < 0) return errno;
    if (!result) return ETIMEDOUT;
    return descriptor.revents & POLLIN ? 0 : EIO;
}

int storage_fs_alive(uint64_t handle) {
    int error = await_exit((int)(handle >> 32), 0);
    return error == ETIMEDOUT ? 1 : error ? -error : 0;
}

static int spawn_fds(char *const command[], int socket_fd, int namespace_fd, int client_pidfd, uint64_t *handle) {
    if (!command || !command[0] || !handle) return EINVAL;
    *handle = 0;
    int type; socklen_t size = sizeof(type);
    if (getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &type, &size)) return errno;
    if (type != SOCK_STREAM) return EPROTOTYPE;
    struct sockaddr_storage peer; size = sizeof(peer);
    if (getpeername(socket_fd, (struct sockaddr *)&peer, &size)) return errno;
    if (peer.ss_family != AF_UNIX) return EAFNOSUPPORT;
    posix_spawn_file_actions_t actions;
    int error = posix_spawn_file_actions_init(&actions);
    if (error) return error;
    posix_spawnattr_t attributes;
    int attr_error = posix_spawnattr_init(&attributes);
    if (attr_error) { posix_spawn_file_actions_destroy(&actions); return attr_error; }
#ifdef POSIX_SPAWN_CLOEXEC_DEFAULT
    // Android marks every inherited descriptor CLOEXEC before applying our three dup actions.
    error = posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT);
#endif
    if (!error) error = posix_spawn_file_actions_addopen(&actions,0,"/dev/null",O_RDONLY,0);
    const int originals[] = {socket_fd,namespace_fd,client_pidfd};
    int copies[] = {-1,-1,-1};
    for (int i=0; i<3 && !error; ++i) {
        if (originals[i] < 0) continue;
        copies[i] = fcntl(originals[i], F_DUPFD_CLOEXEC, 6);
        if (copies[i] < 0) error = errno;
        else error = posix_spawn_file_actions_adddup2(&actions,copies[i],3+i);
    }
    size_t env_count = 0;
    while (environ[env_count]) ++env_count;
    char **child_env = calloc(env_count + 2, sizeof(char *));
    if (!child_env) error = ENOMEM;
    size_t copied = 0;
    if (child_env) {
        for (size_t i=0; i<env_count; ++i) {
            if (strncmp(environ[i], "LD_LIBRARY_PATH=", 16) && strncmp(environ[i], "LD_PRELOAD=", 11))
                child_env[copied++] = environ[i];
        }
        // This executable's libcap/minijail dependencies belong to the AVF APEX.
        child_env[copied] = "LD_LIBRARY_PATH=/apex/com.android.virt/lib64";
    }
    pid_t child = -1;
    if (!error) error = posix_spawn(&child, command[0], &actions, &attributes, command, child_env);
    for (int i=0; i<3; ++i) if (copies[i] >= 0) close(copies[i]);
    free(child_env);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attributes);
    if (error) return error;
    int pidfd = (int)syscall(SYS_pidfd_open, child, 0);
    if (pidfd < 0) {
        error = errno;
        // A direct, unreaped child cannot reuse its PID before this wait.
        kill(child, SIGKILL);
        while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
        return error;
    }
    *handle = ((uint64_t)(uint32_t)pidfd << 32) | (uint32_t)child;
    return 0;
}

int storage_fs_spawn(char *const command[], int socket_fd, uint64_t *handle) {
    return spawn_fds(command,socket_fd,-1,-1,handle);
}

int storage_fs_spawn_root(char *const command[], int socket_fd, int namespace_fd, int client_pidfd, uint64_t *handle) {
    if (getuid() != 0) return EPERM;
    struct statfs info;
    if (fstatfs(namespace_fd,&info)) return errno;
    if ((unsigned long)info.f_type != NSFS_MAGIC) return EINVAL;
    if (syscall(SYS_pidfd_send_signal,client_pidfd,0,NULL,0)) return errno;
    return spawn_fds(command,socket_fd,namespace_fd,client_pidfd,handle);
}

int storage_fs_stop(uint64_t handle) {
    int pidfd = (int)(handle >> 32);
    pid_t child = (pid_t)(uint32_t)handle;
    if (pidfd < 0 || child <= 1) return EINVAL;
    int error = await_exit(pidfd, 0);
    if (error == ETIMEDOUT) {
        if (syscall(SYS_pidfd_send_signal, pidfd, SIGTERM, NULL, 0) && errno != ESRCH) return errno;
        error = await_exit(pidfd, 2000);
    }
    if (error == ETIMEDOUT) {
        if (syscall(SYS_pidfd_send_signal, pidfd, SIGKILL, NULL, 0) && errno != ESRCH) return errno;
        error = await_exit(pidfd, 5000);
    }
    if (error) return error;
    int status;
    pid_t result;
    do { result = waitpid(child, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0 && errno != ECHILD) return errno;
    close(pidfd);
    return 0;
}
