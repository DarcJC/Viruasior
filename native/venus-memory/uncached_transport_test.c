#define _GNU_SOURCE
#include "uncached_client.h"
#include "uncached_server.h"
#include <assert.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>

static unsigned allocations;
static int test_allocate(uint64_t bytes) {
    ++allocations;
    int fd=memfd_create("uncached-transport-test",MFD_CLOEXEC);
    assert(fd>=0 && !ftruncate(fd,(off_t)bytes));
    return fd;
}
static void round_trip(bool wrong_peer,bool exhausted) {
    struct venus_uncached_server server;
    assert(!venus_uncached_server_start(&server,getuid()));
    if(exhausted)server.live=VENUS_UNCACHED_MAX_LIVE;
    int ready[2],finish[2];assert(!pipe(ready) && !pipe(finish));
    pid_t child=fork();assert(child>=0);
    if(!child) {
        close(server.listener);close(ready[0]);close(finish[1]);
        int lease=-1;
        int memory=venus_uncached_allocate(65536,&lease);
        if(wrong_peer || exhausted) {_exit(memory<0 && lease<0?0:11);}
        if(memory<0 || lease<0)_exit(12);
        uint32_t *mapping=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_SHARED,memory,0);
        if(mapping==MAP_FAILED)_exit(13);
        mapping[0]=0x12345678;
        if(write(ready[1],"y",1)!=1)_exit(14);
        char stop;if(read(finish[0],&stop,1)!=1)_exit(15);
        munmap(mapping,65536);close(memory);close(lease);
        _exit(0);
    }
    close(ready[1]);close(finish[0]);
    int pidfd=(int)syscall(SYS_pidfd_open,child,0);assert(pidfd>=0);
    struct pollfd owner={.fd=-1,.events=POLLIN};
    bool saw_live=false;
    uint64_t deadline=venus_uncached_now()+4000;
    while(venus_uncached_now()<deadline) {
        assert(venus_uncached_server_poll(&server,&owner,wrong_peer?getpid():child,pidfd,10,test_allocate)>=0);
        if(!wrong_peer && !exhausted && !saw_live && server.live==65536) {
            char value;assert(read(ready[0],&value,1)==1 && value=='y');
            saw_live=true;assert(write(finish[1],"x",1)==1);
        }
        struct pollfd ended={.fd=pidfd,.events=POLLIN};
        if(poll(&ended,1,0)==1)break;
    }
    int status=0;assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(venus_uncached_server_poll(&server,&owner,child,pidfd,10,test_allocate)>=0);
    if(!wrong_peer && !exhausted)assert(saw_live && server.live==0);
    close(pidfd);close(ready[0]);close(finish[1]);venus_uncached_server_close(&server);
}
int main(void) {
    /* Client verifies UID 0; run in the root WSL build environment. */
    assert(!getuid());
    (void)venus_uncached_heap_allocate;
    round_trip(false,false);assert(allocations==1);
    round_trip(true,false);assert(allocations==1);
    round_trip(false,true);assert(allocations==1);
    puts("PASS: SCM_RIGHTS, allocation lease cleanup, exact peer PID and quota");
    return 0;
}
