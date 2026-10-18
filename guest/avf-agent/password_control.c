#define _GNU_SOURCE
#include "password_control.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static int digit(unsigned char c) {
    if(c >= 'A' && c <= 'Z') return c-'A';
    if(c >= 'a' && c <= 'z') return c-'a'+26;
    if(c >= '0' && c <= '9') return c-'0'+52;
    return c=='+' ? 62 : c=='/' ? 63 : -1;
}
bool decode_droid_password(const char *command, unsigned char password[DROID_PASSWORD_MAX + 1], size_t *length) {
    const char *prefix="SET_DROID_PASSWORD ";
    *length=0;
    memset(password,0,DROID_PASSWORD_MAX+1);
    if(strncmp(command,prefix,strlen(prefix))) return false;
    const char *data=command+strlen(prefix);
    size_t size=strlen(data),used=0;
    if(!size || size%4 || size>172) return false;
    for(size_t i=0;i<size;i+=4) {
        int a=digit(data[i]),b=digit(data[i+1]);
        int c=data[i+2]=='=' ? 0 : digit(data[i+2]);
        int d=data[i+3]=='=' ? 0 : digit(data[i+3]);
        bool pad2=data[i+2]=='=',pad1=data[i+3]=='=';
        if(a<0 || b<0 || c<0 || d<0 || (pad2 && !pad1) ||
           ((pad1 || pad2) && i+4!=size) || (pad2 && (b&15)) || (pad1 && !pad2 && (c&3))) goto invalid;
        uint32_t value=((uint32_t)a<<18)|((uint32_t)b<<12)|((uint32_t)c<<6)|(uint32_t)d;
        unsigned count=pad2 ? 1 : pad1 ? 2 : 3;
        if(used+count>DROID_PASSWORD_MAX) goto invalid;
        for(unsigned j=0;j<count;j++) {
            unsigned char byte=(value>>(16-j*8))&255;
            if(!byte || byte=='\n' || byte=='\r') goto invalid;
            password[used++]=byte;
        }
    }
    *length=used;
    return used>0;
invalid:
    explicit_bzero(password,DROID_PASSWORD_MAX+1);
    return false;
}

int change_droid_password(const unsigned char *password,size_t length) {
    if(!length || length>DROID_PASSWORD_MAX || memchr(password,'\n',length) ||
       memchr(password,'\r',length) || memchr(password,0,length)) return -1;
    int input[2];
    if(pipe2(input,O_CLOEXEC)) return -1;
    pid_t child=fork();
    if(child==0) {
        if(dup2(input[0],STDIN_FILENO)<0) _exit(126);
        close(input[0]);close(input[1]);
        int sink=open("/dev/null",O_WRONLY|O_CLOEXEC);
        if(sink<0 || dup2(sink,STDOUT_FILENO)<0 || dup2(sink,STDERR_FILENO)<0) _exit(126);
        close(sink);
        // Fixed guest user and program; credentials never become argv or shell text.
        execl("/usr/bin/timeout","timeout","10","/usr/sbin/chpasswd",(char *)NULL);
        _exit(127);
    }
    close(input[0]);
    if(child<0) {close(input[1]);return -1;}
    unsigned char line[6+DROID_PASSWORD_MAX+1];
    memcpy(line,"droid:",6);memcpy(line+6,password,length);line[6+length]='\n';
    size_t sent=0,total=7+length;
    while(sent<total) {
        ssize_t count=write(input[1],line+sent,total-sent);
        if(count<0 && errno==EINTR) continue;
        if(count<=0) break;
        sent+=(size_t)count;
    }
    explicit_bzero(line,sizeof(line));close(input[1]);
    int status=0;
    while(waitpid(child,&status,0)<0) {if(errno!=EINTR) return -1;}
    return sent==total && WIFEXITED(status) && WEXITSTATUS(status)==0 ? 0 : -1;
}
