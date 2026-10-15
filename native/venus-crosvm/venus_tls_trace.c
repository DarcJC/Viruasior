#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

/* Opt-in diagnostic only. Pass through every registration unchanged; do not
 * delete keys, suppress callbacks, or retain an arbitrary caller-selected DSO. */
static int (*real_create)(pthread_key_t *, void (*)(void *));
static pthread_once_t once = PTHREAD_ONCE_INIT;
static void load_create(void) {
    void *symbol = dlsym(RTLD_NEXT, "pthread_key_create");
    _Static_assert(sizeof(symbol) == sizeof(real_create), "Android function pointer ABI");
    memcpy(&real_create, &symbol, sizeof(symbol));
}
__attribute__((visibility("default")))
int pthread_key_create(pthread_key_t *key, void (*destructor)(void *)) {
    pthread_once(&once, load_create);
    if (!real_create) return ENOSYS;
    int result = real_create(key, destructor);
    if (destructor) {
        Dl_info owner = {0};
        dladdr((void *)destructor, &owner);
        __android_log_print(ANDROID_LOG_INFO, "crosvm",
            "VIRUAISOR_TLS_KEY=%u destructor=%p module=%s base=%p offset=%llu",
            result ? UINT32_MAX : *key, (void *)destructor,
            owner.dli_fname ? owner.dli_fname : "unknown", owner.dli_fbase,
            (unsigned long long)((uintptr_t)destructor - (uintptr_t)owner.dli_fbase));
    }
    return result;
}
