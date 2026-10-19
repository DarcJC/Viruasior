#define _GNU_SOURCE
#include "fs_scope.h"
#include <errno.h>
#include <grp.h>
#include <linux/capability.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

extern char **environ;

static void refuse(const char *gate) {
    fprintf(stderr,"STORAGE_LAUNCH_REFUSED gate=%s errno=%d\n",gate,errno);
    _exit(126);
}

int main(int argc, char **argv) {
    if (getuid() != 0 || argc != 6) return 64;
    char *end;
    unsigned long parsed = strtoul(argv[1],&end,10);
    if (*end || parsed > 2147483647 || parsed % 100000 < 10000 || parsed % 100000 > 19999) return 64;
    uid_t uid = (uid_t)parsed;
    long parent = strtol(argv[2],&end,10);
    if (*end || parent <= 1 || parent > 2147483647 || getppid() != parent) return 64;
    char expected[512];
    if (storage_export_path(expected,sizeof(expected),uid,argv[5])) return 64;
    if (strcmp(argv[4],expected)) return 64;
    const char *base = strrchr(argv[3],'/');
    if (!base || strcmp(base,"/libvenus_desktop_crosvm.so") || strncmp(argv[3],"/data/app/",10)) return 64;
    if (setns(4,CLONE_NEWNS)) refuse("application-mount-namespace");
    close(4);
    if (chdir("/")) refuse("cwd");
    // /storage is 0710 shell:everybody on this device. Retain only its traverse group.
    const gid_t traversal_group=9997;
    if (prctl(PR_SET_KEEPCAPS,0) || setgroups(1,&traversal_group) || setresgid(uid,uid,uid) || setresuid(uid,uid,uid)) refuse("application-ids");
    struct __user_cap_header_struct header = {.version=_LINUX_CAPABILITY_VERSION_3,.pid=0};
    struct __user_cap_data_struct capabilities[2] = {{0},{0}};
    if (syscall(SYS_capset,&header,capabilities) || prctl(PR_CAP_AMBIENT,PR_CAP_AMBIENT_CLEAR_ALL,0,0,0) ||
        prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) || prctl(PR_SET_PDEATHSIG,SIGKILL) || getppid() != parent) refuse("unprivileged-lifetime");
    char cfg[192], owner[64];
    // Android and Linux both mutate this directory: neither data nor dentries may be cached.
    snprintf(cfg,sizeof(cfg),"cache=never,timeout=0,negative_timeout=0,writeback=false,ugid_map='1000 984 %u %u 18 /'",uid,uid);
    snprintf(owner,sizeof(owner),"--app-storage-owner-pid=%ld",parent);
    char shared[sizeof(expected)+16];
    snprintf(shared,sizeof(shared),"--shared-dir=%s",expected);
    fprintf(stderr,"STORAGE_LAUNCH uid=%u gid=%u caps=0 namespace=application scope=storage/0\n",getuid(),getgid());
    char *command[] = {argv[3],"device","fs","--fd=3","--tag=viruaisor-storage-0",shared,"--cfg",cfg,
        "--disable-sandbox","--skip-pivot-root=true",owner,"--app-storage-client-fd=5",NULL};
    execve(argv[3],command,environ);
    refuse("pinned-backend-exec");
}
