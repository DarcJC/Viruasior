#pragma once
#include "uncached_protocol.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-heap.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

struct venus_uncached_lease {int fd;uint64_t bytes,deadline;};
struct venus_uncached_server {
    int listener;
    uid_t uid;
    uint64_t live;
    struct venus_uncached_lease leases[VENUS_UNCACHED_MAX_LEASES];
};
typedef int (*venus_uncached_allocator)(uint64_t);
static uint64_t venus_uncached_now(void) {
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int venus_uncached_heap_allocate(uint64_t bytes) {
    int heap=open("/dev/dma_heap/system-uncached",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(heap<0)return -1;
    struct dma_heap_allocation_data allocation={.len=bytes,.fd_flags=O_RDWR|O_CLOEXEC};
    int result=ioctl(heap,DMA_HEAP_IOCTL_ALLOC,&allocation);
    close(heap);
    return result<0?-1:(int)allocation.fd;
}
static void venus_uncached_server_close(struct venus_uncached_server *s) {
    if(s->listener>=0)close(s->listener);
    s->listener=-1;
    for(unsigned i=0;i<VENUS_UNCACHED_MAX_LEASES;i++) {
        if(s->leases[i].fd>=0)close(s->leases[i].fd);
        s->leases[i].fd=-1;
    }
    s->live=0;
}
static int venus_uncached_server_start(struct venus_uncached_server *s,uid_t uid) {
    memset(s,0,sizeof(*s));s->listener=-1;s->uid=uid;
    for(unsigned i=0;i<VENUS_UNCACHED_MAX_LEASES;i++)s->leases[i].fd=-1;
    int fd=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
    if(fd<0)return -1;
    struct sockaddr_un address;
    socklen_t length=venus_uncached_address(uid,&address);
    if(bind(fd,(void*)&address,length) || listen(fd,16)) {close(fd);return -1;}
    s->listener=fd;
    return 0;
}
static void venus_uncached_drop(struct venus_uncached_server *s,unsigned i) {
    close(s->leases[i].fd);
    s->live-=s->leases[i].bytes;
    s->leases[i]=(struct venus_uncached_lease){.fd=-1};
}
/* Poll the owner, listener and allocation lifetimes together; allocation requests
 * cannot delay VM-owner death handling. The caller supplies its verified pidfd. */
static int venus_uncached_server_poll(struct venus_uncached_server *s,struct pollfd *owner,
    pid_t authenticated_pid,int authenticated_pidfd,int timeout,venus_uncached_allocator allocate) {
    struct pollfd fds[VENUS_UNCACHED_MAX_LEASES+2];
    fds[0]=*owner;fds[1]=(struct pollfd){.fd=s->listener,.events=POLLIN};
    for(unsigned i=0;i<VENUS_UNCACHED_MAX_LEASES;i++)
        fds[i+2]=(struct pollfd){.fd=s->leases[i].fd,.events=POLLIN};
    int result=poll(fds,VENUS_UNCACHED_MAX_LEASES+2,timeout);
    owner->revents=fds[0].revents;
    if(result<0 || owner->revents)return result;
    uint64_t now=venus_uncached_now();
    for(unsigned i=0;i<VENUS_UNCACHED_MAX_LEASES;i++) {
        struct venus_uncached_lease *l=&s->leases[i];
        if(l->fd<0)continue;
        if((fds[i+2].revents&(POLLHUP|POLLERR|POLLNVAL)) || (!l->bytes && now>=l->deadline)) {
            venus_uncached_drop(s,i);continue;
        }
        if(!(fds[i+2].revents&POLLIN))continue;
        if(l->bytes) {venus_uncached_drop(s,i);continue;}
        struct venus_uncached_request request;
        ssize_t n=recv(l->fd,&request,sizeof(request),MSG_DONTWAIT|MSG_TRUNC);
        if(n!=sizeof(request) || !venus_uncached_request_valid(&request) ||
            !venus_uncached_budget_valid(s->live,request.bytes)) {
            venus_uncached_drop(s,i);continue;
        }
        struct pollfd process={.fd=authenticated_pidfd,.events=POLLIN};
        if(authenticated_pidfd<0 || poll(&process,1,0)!=0) {
            venus_uncached_drop(s,i);continue;
        }
        int memory=allocate(request.bytes);
        if(memory<0) {venus_uncached_drop(s,i);continue;}
        struct venus_uncached_reply reply={0,VENUS_UNCACHED_VERSION};
        struct iovec iov={.iov_base=&reply,.iov_len=sizeof(reply)};
        union {struct cmsghdr align;char bytes[CMSG_SPACE(sizeof(int))];} control={0};
        struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SCM_RIGHTS;c->cmsg_len=CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c),&memory,sizeof(memory));
        n=sendmsg(l->fd,&msg,MSG_DONTWAIT|MSG_NOSIGNAL);
        close(memory);
        if(n!=sizeof(reply)) {venus_uncached_drop(s,i);continue;}
        l->bytes=request.bytes;s->live+=request.bytes;
    }
    if((fds[1].revents&POLLIN) && authenticated_pid>0 && authenticated_pidfd>=0) {
        int fd=accept4(s->listener,NULL,NULL,SOCK_CLOEXEC|SOCK_NONBLOCK);
        if(fd>=0) {
            struct ucred peer={0};socklen_t length=sizeof(peer);
            struct pollfd process={.fd=authenticated_pidfd,.events=POLLIN};
            bool okay=!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&length) &&
                peer.uid==s->uid && peer.pid==authenticated_pid && poll(&process,1,0)==0;
            if(okay) for(unsigned i=0;i<VENUS_UNCACHED_MAX_LEASES;i++) {
                if(s->leases[i].fd>=0)continue;
                s->leases[i]=(struct venus_uncached_lease){.fd=fd,.deadline=now+2000};
                fd=-1;break;
            }
            if(fd>=0)close(fd);
        }
    }
    return result;
}
