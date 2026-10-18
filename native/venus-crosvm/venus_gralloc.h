#pragma once
#include "venus_gralloc_policy.h"
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>

/* Android 17 platform native_handle ABI, obtained only from the pinned system
 * library, never from a guest message. Unknown versions/layouts fail closed. */
struct venus_native_handle { int version, num_fds, num_ints; int data[]; };
static int (*venus_ahb_allocate)(const AHardwareBuffer_Desc *, AHardwareBuffer **);
static void (*venus_ahb_release)(AHardwareBuffer *);
static const struct venus_native_handle *(*venus_ahb_handle)(const AHardwareBuffer *);
static void venus_gralloc_load(void) {
    void *library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL | RTLD_NODELETE);
    if (!library) return;
    void *allocate = dlsym(library, "AHardwareBuffer_allocate");
    void *release = dlsym(library, "AHardwareBuffer_release");
    void *handle = dlsym(library, "AHardwareBuffer_getNativeHandle");
    _Static_assert(sizeof(venus_ahb_allocate) == sizeof(allocate), "Android function pointer ABI");
    _Static_assert(sizeof(venus_ahb_release) == sizeof(release), "Android function pointer ABI");
    _Static_assert(sizeof(venus_ahb_handle) == sizeof(handle), "Android function pointer ABI");
    memcpy(&venus_ahb_allocate, &allocate, sizeof(allocate));
    memcpy(&venus_ahb_release, &release, sizeof(release));
    memcpy(&venus_ahb_handle, &handle, sizeof(handle));
    /* Process-local system library; keep its callbacks executable until exit. */
}
static int venus_gralloc_export_fd(AHardwareBuffer *buffer, uint64_t minimum_bytes,
                                   bool cached) {
    if (!buffer) return -EINVAL;
    const struct venus_native_handle *handle = venus_ahb_handle(buffer);
    int selected = -1, matches = 0;
    if (handle && handle->version == 12 && handle->num_fds > 0 &&
        handle->num_fds <= 8 && handle->num_ints >= 0 &&
        handle->num_ints <= 256) {
        for (int i = 0; i < handle->num_fds; ++i) {
            char path[80], name[160];
            snprintf(path, sizeof(path), "/proc/self/fd/%d", handle->data[i]);
            ssize_t length = readlink(path, name, sizeof(name)-1);
            if (length < 0) continue;
            name[length] = 0;
            off_t size = lseek(handle->data[i], 0, SEEK_END);
            vkr_log("VIRUAISOR_GRALLOC_FD index=%d name=%s bytes=%lld cached=%d",
                    i, name, (long long)size, cached);
            if (venus_gralloc_fd_candidate(name, size, minimum_bytes)) {
                selected = handle->data[i];
                ++matches;
            }
        }
    }
    if (matches != 1) return -ENOTSUP;
    int fd = fcntl(selected, F_DUPFD_CLOEXEC, 0);
    return fd < 0 ? -errno : fd;
}
static int venus_gralloc_allocate_desc(const AHardwareBuffer_Desc *desc,
                                       uint64_t minimum_bytes, bool cached,
                                       AHardwareBuffer **out) {
    if (!out || !desc || !minimum_bytes || minimum_bytes > 64*1024*1024)
        return -EINVAL;
    static pthread_once_t once = PTHREAD_ONCE_INIT;
    pthread_once(&once, venus_gralloc_load);
    if (!venus_ahb_allocate || !venus_ahb_release || !venus_ahb_handle) return -ENOSYS;
    AHardwareBuffer *buffer = NULL;
    int result = venus_ahb_allocate(desc, &buffer);
    if (result || !buffer) { if (buffer) venus_ahb_release(buffer); return result < 0 ? result : -ENOMEM; }
    int fd = venus_gralloc_export_fd(buffer, minimum_bytes, cached);
    if (fd < 0) { venus_ahb_release(buffer); return fd; }
    *out = buffer;
    return fd;
}
static int venus_gralloc_allocate(uint64_t bytes, bool cached, AHardwareBuffer **out) {
    const AHardwareBuffer_Desc desc = {.width=(uint32_t)bytes,.height=1,.layers=1,
        .format=AHARDWAREBUFFER_FORMAT_BLOB,
        .usage=AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER | (cached ?
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN :
            AHARDWAREBUFFER_USAGE_CPU_READ_RARELY | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY)};
    return venus_gralloc_allocate_desc(&desc, bytes, cached, out);
}
static int venus_gralloc_allocate_rgba_image(uint32_t width, uint32_t height,
                                             uint32_t layers,
                                             AHardwareBuffer **out) {
    if (!width || !height || !layers) return -EINVAL;
    const uint64_t minimum_bytes = (uint64_t)width * height * layers * 4;
    if (minimum_bytes > 64*1024*1024) return -E2BIG;
    const AHardwareBuffer_Desc desc = {
        .width=width, .height=height, .layers=layers,
        .format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
               AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
               AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
               AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
               AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
    };
    return venus_gralloc_allocate_desc(&desc, minimum_bytes, true, out);
}
