#pragma once
#include "uncached_protocol.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

/* Retain the returned lease until the allocation and all exported resources die. */
static inline int venus_uncached_allocate_for_uid(uid_t uid, uint64_t bytes, int *lease) {
    struct venus_uncached_request request={VENUS_UNCACHED_VERSION,0,bytes};
    if(!lease || !venus_uncached_request_valid(&request)) {errno=EINVAL;return -1;}
    *lease=-1;
    int connection=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0);
    if(connection<0)return -1;
    struct timeval timeout={.tv_sec=2};
    setsockopt(connection,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    setsockopt(connection,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    struct sockaddr_un address;
    socklen_t length=venus_uncached_address(uid,&address);
    struct ucred peer={0};socklen_t peer_length=sizeof(peer);
    int fd=-1;
    if(connect(connection,(void*)&address,length) ||
       getsockopt(connection,SOL_SOCKET,SO_PEERCRED,&peer,&peer_length) || peer.uid!=0)
        goto fail;
    if(send(connection,&request,sizeof(request),MSG_NOSIGNAL)!=sizeof(request))goto fail;
    struct venus_uncached_reply reply={0};
    struct iovec iov={.iov_base=&reply,.iov_len=sizeof(reply)};
    union {struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int)*4)];} control={0};
    struct msghdr message={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
    ssize_t received=recvmsg(connection,&message,MSG_CMSG_CLOEXEC);
    unsigned descriptors=0;
    for(struct cmsghdr *c=CMSG_FIRSTHDR(&message);c;c=CMSG_NXTHDR(&message,c)) {
        if(c->cmsg_level!=SOL_SOCKET || c->cmsg_type!=SCM_RIGHTS || c->cmsg_len<CMSG_LEN(0))continue;
        size_t count=(c->cmsg_len-CMSG_LEN(0))/sizeof(int);
        for(size_t i=0;i<count;i++) {
            int item;memcpy(&item,(char*)CMSG_DATA(c)+i*sizeof(int),sizeof(item));
            if(descriptors++==0)fd=item;else close(item);
        }
    }
    if(received!=sizeof(reply) || (message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)) ||
       reply.version!=VENUS_UNCACHED_VERSION || reply.error || descriptors!=1 ||
       fd<0 || lseek(fd,0,SEEK_END)!=(off_t)bytes)goto fail;
    *lease=connection;
    return fd;
fail:
    if(fd>=0)close(fd);
    close(connection);
    errno=EIO;
    return -1;
}
static inline int venus_uncached_allocate(uint64_t bytes,int *lease) {
    return venus_uncached_allocate_for_uid(getuid(),bytes,lease);
}
