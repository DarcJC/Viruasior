#pragma once
#include <stdbool.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static inline bool overlay_same_bytes(int source, int copy) {
    struct stat a, b;
    if (fstat(source, &a) || fstat(copy, &b) || !S_ISREG(a.st_mode) || !S_ISREG(b.st_mode) ||
        a.st_size <= 0 || a.st_size != b.st_size || a.st_size > 64 * 1024 * 1024) return false;
    unsigned char original[65536], copied[65536];
    for (off_t offset = 0; offset < a.st_size;) {
        size_t count = (size_t)(a.st_size - offset);
        if (count > sizeof(original)) count = sizeof(original);
        ssize_t n = pread(source, original, count, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n != (ssize_t)count) return false;
        ssize_t m;
        do { m = pread(copy, copied, count, offset); } while (m < 0 && errno == EINTR);
        if (m != n || memcmp(original, copied, count)) return false;
        offset += n;
    }
    return true;
}
static inline bool overlay_copy_identical(int source, int copy) {
    struct stat a, b;
    if (source == copy || fstat(source, &a) || fstat(copy, &b) || !S_ISREG(a.st_mode) ||
        !S_ISREG(b.st_mode) || a.st_size <= 0 || a.st_size > 64 * 1024 * 1024 || b.st_size != 0) return false;
    unsigned char buffer[65536];
    for (off_t offset = 0; offset < a.st_size;) {
        size_t count = (size_t)(a.st_size - offset);
        if (count > sizeof(buffer)) count = sizeof(buffer);
        ssize_t n = pread(source, buffer, count, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n != (ssize_t)count) return false;
        for (ssize_t done = 0; done < n;) {
            ssize_t written = pwrite(copy, buffer + done, (size_t)(n - done), offset + done);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) return false;
            done += written;
        }
        offset += n;
    }
    return overlay_same_bytes(source, copy);
}
static inline bool overlay_snapshot_readonly(int *fd) {
    if (!fd || *fd < 0) return false;
    char path[80]; snprintf(path, sizeof(path), "/proc/self/fd/%d", *fd);
    // Open the pinned inode, not its app-writable directory entry (no close/reopen TOCTOU).
    int readonly = open(path, O_RDONLY | O_CLOEXEC);
    if (readonly < 0) return false;
    struct stat original, sealed;
    if (fstat(*fd, &original) || fstat(readonly, &sealed) || original.st_dev != sealed.st_dev ||
        original.st_ino != sealed.st_ino || (fcntl(readonly, F_GETFL) & O_ACCMODE) != O_RDONLY) {
        close(readonly); return false;
    }
    close(*fd); *fd = readonly;
    return true;
}
