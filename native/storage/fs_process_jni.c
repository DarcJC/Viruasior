#include "fs_process.h"
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void fail(JNIEnv *env, int error) {
    char message[192];
    snprintf(message, sizeof(message), "Shared storage process: %s (%d)", strerror(error), error);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}

static jlong spawn(JNIEnv *env, jobjectArray arguments, jint socket_fd, int namespace_fd, int client_pidfd) {
    jsize count = (*env)->GetArrayLength(env, arguments);
    if (count < 2 || count > 16) { fail(env, 22); return 0; }
    char *command[17] = {0};
    jstring strings[16] = {0};
    for (jsize i=0; i<count; ++i) {
        strings[i] = (*env)->GetObjectArrayElement(env, arguments, i);
        if (!strings[i]) break;
        command[i] = (char *)(*env)->GetStringUTFChars(env, strings[i], NULL);
        if (!command[i]) break;
    }
    uint64_t handle = 0;
    int error = (*env)->ExceptionCheck(env) || !command[count-1] ? 22 :
        namespace_fd >= 0 ? storage_fs_spawn_root(command,socket_fd,namespace_fd,client_pidfd,&handle) :
        storage_fs_spawn(command, socket_fd, &handle);
    for (jsize i=0; i<count; ++i) {
        if (command[i]) (*env)->ReleaseStringUTFChars(env, strings[i], command[i]);
        if (strings[i]) (*env)->DeleteLocalRef(env, strings[i]);
    }
    if (error && !(*env)->ExceptionCheck(env)) fail(env, error);
    return (jlong)handle;
}

JNIEXPORT jlong JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_spawn(
        JNIEnv *env, jobject receiver, jobjectArray arguments, jint socket_fd) {
    (void)receiver;
    return spawn(env,arguments,socket_fd,-1,-1);
}

JNIEXPORT jlong JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_spawnRoot(
        JNIEnv *env, jobject receiver, jobjectArray arguments, jint socket_fd, jint namespace_fd, jint client_pidfd) {
    (void)receiver;
    if (getuid() != 0 || namespace_fd < 0 || client_pidfd < 0) { fail(env,EPERM); return 0; }
    return spawn(env,arguments,socket_fd,namespace_fd,client_pidfd);
}

JNIEXPORT jint JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_openPidfd(
        JNIEnv *env, jobject receiver, jint pid) {
    (void)receiver;
    if (getuid() != 0 || pid <= 1) { fail(env,EPERM); return -1; }
    int fd = (int)syscall(SYS_pidfd_open,pid,0);
    if (fd < 0) fail(env,errno);
    return fd;
}

JNIEXPORT void JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_stop(
        JNIEnv *env, jobject receiver, jlong handle) {
    (void)receiver;
    int error = storage_fs_stop((uint64_t)handle);
    if (error) fail(env, error);
}

JNIEXPORT jboolean JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_alive(
        JNIEnv *env, jobject receiver, jlong handle) {
    (void)receiver;
    int result = storage_fs_alive((uint64_t)handle);
    if (result < 0) fail(env,-result);
    return result > 0;
}

JNIEXPORT void JNICALL Java_pro_darc_viruaisor_vm_VmStorageNative_exchange(
        JNIEnv *env, jobject receiver, jstring first, jstring second) {
    (void)receiver;
    const char *a=(*env)->GetStringUTFChars(env,first,NULL);
    if (!a) return;
    const char *b=(*env)->GetStringUTFChars(env,second,NULL);
    int error=b ? storage_exchange(a,b) : 0;
    if (b) (*env)->ReleaseStringUTFChars(env,second,b);
    (*env)->ReleaseStringUTFChars(env,first,a);
    if (error) fail(env,error);
}
