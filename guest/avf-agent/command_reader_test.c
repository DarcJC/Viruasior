#define _POSIX_C_SOURCE 200809L
#include "command_reader.h"
#include <assert.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>

static void delay(int ms) {
    struct timespec t={.tv_sec=ms/1000,.tv_nsec=(ms%1000)*1000000L};
    while(nanosleep(&t,&t)<0 && errno==EINTR) {}
}
static void fragmented(int interval, int timeout, int success) {
    int pair[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,pair)==0);
    pid_t child=fork();assert(child>=0);
    if(!child) {
        close(pair[0]);signal(SIGPIPE,SIG_IGN);
        for(const char *p="PING\n";*p;p++) { delay(interval);if(write(pair[1],p,1)!=1) break; }
        close(pair[1]);_exit(0);
    }
    close(pair[1]);char buffer[16]={0};
    int result=read_command(pair[0],buffer,sizeof(buffer),timeout);
    assert(success ? result==5 && !strcmp(buffer,"PING\n") : result<0);
    close(pair[0]);assert(waitpid(child,NULL,0)==child);
}
int main(void) {
    fragmented(10,500,1);
    /* Each byte arrives within 120 ms, but the whole line takes 300 ms. */
    fragmented(60,120,0);
    int pair[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,pair)==0);
    assert(write(pair[1],"TOO-LONG\n",9)==9);
    char small[4];assert(read_command(pair[0],small,sizeof(small),500)<0);
    close(pair[0]);close(pair[1]);
    puts("command reader: fragmented line, total deadline and capacity passed");
}
