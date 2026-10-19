#ifndef VIRUAISOR_FS_PROCESS_H
#define VIRUAISOR_FS_PROCESS_H
#include <stdint.h>
int storage_fs_spawn(char *const command[], int socket_fd, uint64_t *handle);
int storage_fs_spawn_root(char *const command[], int socket_fd, int namespace_fd, int client_pidfd, uint64_t *handle);
int storage_fs_stop(uint64_t handle);
int storage_fs_alive(uint64_t handle);
int storage_exchange(const char *first, const char *second);
#endif
