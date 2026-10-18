#pragma once
#include <errno.h>
#include <stddef.h>
#include <poll.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>

/* A command is complete only after LF; timeout applies to the whole command. */
static inline int read_command(int fd, char *buffer, size_t capacity, int timeout_ms) {
    struct timespec now;
    if(timeout_ms<=0 || clock_gettime(CLOCK_MONOTONIC,&now)) return -1;
    const int64_t deadline=(int64_t)now.tv_sec*1000+now.tv_nsec/1000000+timeout_ms;
    size_t used=0;
    while(used+1<capacity) {
        if(clock_gettime(CLOCK_MONOTONIC,&now)) return -1;
        int64_t left=deadline-((int64_t)now.tv_sec*1000+now.tv_nsec/1000000);
        if(left<=0) return -1;
        struct pollfd pending={.fd=fd,.events=POLLIN};
        int ready=poll(&pending,1,(int)left);
        if(ready<0 && errno==EINTR) continue;
        if(ready<=0) return -1;
        ssize_t count=read(fd,buffer+used,1);
        if(count<0 && errno==EINTR) continue;
        if(count<=0) return -1;
        if(buffer[used++]=='\n') { buffer[used]='\0';return (int)used; }
    }
    return -1;
}
