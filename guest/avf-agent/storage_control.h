#ifndef VIRUAISOR_STORAGE_CONTROL_H
#define VIRUAISOR_STORAGE_CONTROL_H
#include <stdbool.h>
#include <stdio.h>
bool storage_mount_matches(FILE *mounts);
int storage_status(char *output, size_t capacity);
#endif
