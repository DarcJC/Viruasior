#define _GNU_SOURCE
#include "fs_process.h"
#include "fs_scope.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char export_path[512];
    assert(!storage_export_path(export_path,sizeof(export_path),10278,"org.example.virtualmachine.debug"));
    assert(!strcmp(export_path,"/storage/emulated/0/Android/data/org.example.virtualmachine.debug/files/storage/0"));
    assert(!storage_export_path(export_path,sizeof(export_path),1010278,"org.example.virtualmachine.debug"));
    assert(!strcmp(export_path,"/storage/emulated/10/Android/data/org.example.virtualmachine.debug/files/storage/0"));
    assert(storage_export_path(export_path,sizeof(export_path),0,"org.example.vm") == EINVAL);
    assert(storage_export_path(export_path,sizeof(export_path),10278,"../other") == EINVAL);
    assert(storage_export_path(export_path,sizeof(export_path),10278,"org.example.vm;id") == EINVAL);
    if (argc == 2 && (!strcmp(argv[1], "--child") || !strcmp(argv[1], "--child-root"))) {
        assert(!strcmp(getenv("LD_LIBRARY_PATH"),"/apex/com.android.virt/lib64"));
        if (!strcmp(argv[1],"--child-root")) {
            struct statfs info;
            assert(!fstatfs(4,&info) && (unsigned long)info.f_type == NSFS_MAGIC);
            assert(!syscall(SYS_pidfd_send_signal,5,0,NULL,0));
        }
        signal(SIGTERM, SIG_IGN);
        assert(write(3, "FD_PASS", 7) == 7);
        for (;;) pause();
    }
    char first[]="/tmp/viruaisor-exchange-first-XXXXXX", second[]="/tmp/viruaisor-exchange-second-XXXXXX";
    int first_fd=mkstemp(first), second_fd=mkstemp(second);
    assert(first_fd >= 0 && second_fd >= 0);
    assert(write(first_fd,"old",3) == 3 && write(second_fd,"new",3) == 3);
    close(first_fd); close(second_fd);
    assert(!storage_exchange(first,second));
    first_fd=open(first,O_RDONLY); second_fd=open(second,O_RDONLY);
    char content[4]={0};
    assert(read(first_fd,content,3) == 3 && !strcmp(content,"new"));
    assert(read(second_fd,content,3) == 3 && !strcmp(content,"old"));
    close(first_fd); close(second_fd); unlink(first); unlink(second);
    assert(storage_exchange(first,second) == ENOENT);
    int pair[2];
    assert(!socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair));
    uint64_t handle = 0;
    char *command[] = {argv[0], "--child", NULL};
    assert(!storage_fs_spawn(command, pair[1], &handle));
    assert(handle);
    assert(storage_fs_alive(handle) == 1);
    close(pair[1]);
    char response[8] = {0};
    assert(read(pair[0], response, 7) == 7 && !strcmp(response, "FD_PASS"));
    assert(!storage_fs_stop(handle));
    assert(read(pair[0], response, 1) == 0);
    close(pair[0]);
    int file = open("/dev/null", O_RDONLY | O_CLOEXEC);
    assert(storage_fs_spawn(command, file, &handle) == ENOTSOCK);
    close(file);
    char *missing[] = {"/nonexistent/viruaisor-fs-test", NULL};
    assert(!socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair));
    assert(storage_fs_spawn(missing, pair[1], &handle) == ENOENT);
    close(pair[0]); close(pair[1]);
    if (getuid() == 0) {
        int ns = open("/proc/self/ns/mnt",O_RDONLY|O_CLOEXEC);
        int owner = (int)syscall(SYS_pidfd_open,getpid(),0);
        assert(ns >= 0 && owner >= 0);
        assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
        char *root_command[] = {argv[0],"--child-root",NULL};
        assert(!storage_fs_spawn_root(root_command,pair[1],ns,owner,&handle));
        close(pair[1]); close(ns); close(owner);
        memset(response,0,sizeof(response));
        assert(read(pair[0],response,7) == 7 && !strcmp(response,"FD_PASS"));
        assert(!storage_fs_stop(handle));
        close(pair[0]);
    }
    puts("PASS: socket inheritance, termination escalation, invalid FD and exec failure");
}
