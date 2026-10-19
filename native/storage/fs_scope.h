#ifndef VIRUAISOR_STORAGE_SCOPE_H
#define VIRUAISOR_STORAGE_SCOPE_H
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>

static inline int storage_export_path(char *output, size_t capacity, uid_t uid, const char *package) {
    if (!package || uid % 100000 < 10000 || uid % 100000 > 19999 || strlen(package) > 223) return EINVAL;
    bool start = true, dot = false;
    for (const unsigned char *p = (const unsigned char *)package; *p; ++p) {
        bool letter = (*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z');
        if (start) { if (!letter) return EINVAL; start = false; }
        else if (*p == '.') { start = true; dot = true; }
        else if (!letter && !(*p >= '0' && *p <= '9') && *p != '_') return EINVAL;
    }
    if (start || !dot) return EINVAL;
    int written = snprintf(output,capacity,"/storage/emulated/%u/Android/data/%s/files/storage/0",uid/100000,package);
    return written < 0 || (size_t)written >= capacity ? ENAMETOOLONG : 0;
}
#endif
