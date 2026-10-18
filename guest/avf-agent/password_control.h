#ifndef VIRUAISOR_PASSWORD_CONTROL_H
#define VIRUAISOR_PASSWORD_CONTROL_H
#include <stdbool.h>
#include <stddef.h>
#define DROID_PASSWORD_MAX 128
bool decode_droid_password(const char *command, unsigned char password[DROID_PASSWORD_MAX + 1], size_t *length);
int change_droid_password(const unsigned char *password, size_t length);
#endif
