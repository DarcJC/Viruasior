#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/vm_sockets.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <unistd.h>

#include "desktop_protocol.h"

#define DEFAULT_PORT 4050U
#define BACKLOG 8
#define MAX_COMMAND 256

static volatile sig_atomic_t running = 1;

static void stop_server(int signal_number) {
    (void)signal_number;
    running = 0;
}

static int systemctl(const char *verb, const char *unit, bool no_block) {
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        if (unit != NULL) {
            if (no_block) {
                execlp("systemctl", "systemctl", "--no-block", verb, unit, (char *)NULL);
            } else {
                execlp("systemctl", "systemctl", verb, unit, (char *)NULL);
            }
        } else {
            execlp("systemctl", "systemctl", "--no-block", verb, (char *)NULL);
        }
        _exit(127);
    }
    int status;
    while (waitpid(child, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static void reply(int fd, const char *line) {
    size_t length = strlen(line);
    while (length > 0) {
        ssize_t written = write(fd, line, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return;
        line += written;
        length -= (size_t)written;
    }
}

static void trim(char *command) {
    command[strcspn(command, "\r\n")] = '\0';
}

static void display_control(int client, const char *verb, uint32_t width, uint32_t height) {
    int output[2];
    if (pipe2(output,O_CLOEXEC)) { reply(client,"ERROR display-control\n"); return; }
    pid_t child=fork();
    if (child==0) {
        dup2(output[1],STDOUT_FILENO);
        close(output[0]); close(output[1]);
        close(client);
        char w[16],h[16];
        snprintf(w,sizeof(w),"%u",width); snprintf(h,sizeof(h),"%u",height);
        if (strcmp(verb,"QUERY")==0)
            execl("/usr/sbin/runuser","runuser","-u","droid","--","/usr/bin/timeout","10",
                  "/opt/viruaisor/display-control.py","QUERY",(char *)NULL);
        else
            execl("/usr/sbin/runuser","runuser","-u","droid","--","/usr/bin/timeout","10",
                  "/opt/viruaisor/display-control.py","RESIZE",w,h,(char *)NULL);
        _exit(127);
    }
    close(output[1]);
    if (child<0) { close(output[0]); reply(client,"ERROR display-control\n"); return; }
    char response[2048]={0};
    size_t used=0;
    ssize_t count;
    while (used<sizeof(response)-1) {
        count=read(output[0],response+used,sizeof(response)-1-used);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) break;
        used+=(size_t)count;
    }
    close(output[0]);
    int status=0;
    while (waitpid(child,&status,0)<0) { if (errno!=EINTR) { status=-1; break; } }
    if (status==0 && used>0 && response[used-1]=='\n' &&
        strncmp(response,"OK ",3)==0 && strchr(response,'\n')==response+used-1)
        reply(client,response);
    else reply(client,"ERROR display-control\n");
}

static void handle_client(int fd) {
    char command[MAX_COMMAND] = {0};
    reply(fd, "READY\n");

    ssize_t count;
    do {
        count = read(fd, command, sizeof(command) - 1);
    } while (count < 0 && errno == EINTR);
    if (count <= 0) return; /* A readiness-only connection. */
    command[count] = '\0';
    trim(command);
    struct desktop_operation desktop;

    if (strcmp(command, "SHUTDOWN") == 0) {
        reply(fd, "OK\n");
        (void)systemctl("poweroff", NULL, true);
    } else if (parse_desktop_operation(command, &desktop)) {
        if (systemctl(desktop.verb, desktop.unit, desktop.no_block) == 0) reply(fd, "OK\n");
        else reply(fd, "ERROR systemctl\n");
    } else if (strcmp(command, "DISPLAY_MODES") == 0) {
        display_control(fd,"QUERY",0,0);
    } else if (strncmp(command, "RESIZE ", 7) == 0) {
        uint32_t width=0,height=0;
        if (parse_resize_command(command,&width,&height)) display_control(fd,"RESIZE",width,height);
        else reply(fd,"ERROR invalid-resolution\n");
    } else {
        reply(fd, "ERROR unknown-command\n");
    }
}

static uint32_t parse_port(int argc, char **argv) {
    if (argc == 1) return DEFAULT_PORT;
    if (argc != 3 || strcmp(argv[1], "--port") != 0) {
        fprintf(stderr, "usage: %s [--port PORT]\n", argv[0]);
        exit(EXIT_FAILURE);
    }
    char *end = NULL;
    unsigned long value = strtoul(argv[2], &end, 10);
    if (end == argv[2] || *end != '\0' || value < 1024 || value > UINT32_MAX) {
        fprintf(stderr, "invalid vsock port: %s\n", argv[2]);
        exit(EXIT_FAILURE);
    }
    return (uint32_t)value;
}

int main(int argc, char **argv) {
    uint32_t port = parse_port(argc, argv);
    struct sigaction stop_action = {.sa_handler = stop_server};
    sigemptyset(&stop_action.sa_mask);
    sigaction(SIGTERM, &stop_action, NULL);
    sigaction(SIGINT, &stop_action, NULL);
    signal(SIGPIPE, SIG_IGN);
    /* Every child is reaped synchronously by systemctl(); SIG_IGN would make waitpid fail. */

    int server = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) {
        perror("socket(AF_VSOCK)");
        return EXIT_FAILURE;
    }

    struct sockaddr_vm address = {
        .svm_family = AF_VSOCK,
        .svm_port = port,
        .svm_cid = VMADDR_CID_ANY,
    };
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) < 0) {
        perror("bind(AF_VSOCK)");
        close(server);
        return EXIT_FAILURE;
    }
    if (listen(server, BACKLOG) < 0) {
        perror("listen(AF_VSOCK)");
        close(server);
        return EXIT_FAILURE;
    }

    while (running) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            perror("accept4(AF_VSOCK)");
            break;
        }
        handle_client(client);
        close(client);
    }

    close(server);
    return running ? EXIT_FAILURE : EXIT_SUCCESS;
}
