/*
 * Minimal JNI bridge for Android's public NDK binder service lookup.
 *
 * It is intentionally freestanding so this one-function shim can be built with any AArch64 ELF
 * cross compiler. libbinder_ndk supplies these stable NDK functions at runtime. This avoids calling
 * hidden android.os.ServiceManager Java methods merely to obtain the Android 17 crosvm display
 * service. See scripts/build-native-binder.sh.
 */

typedef struct JNIEnv JNIEnv;
typedef void *jobject;
typedef struct AIBinder AIBinder;

extern AIBinder *AServiceManager_waitForService(const char *instance);
extern jobject AIBinder_toJavaBinder(JNIEnv *env, AIBinder *binder);
extern void AIBinder_decStrong(AIBinder *binder);

__attribute__((visibility("default"))) jobject
Java_pro_darc_viruaisor_display_NativeBinderLookup_waitForVirtualizationService(
        JNIEnv *env,
        jobject receiver) {
    (void)receiver;
    AIBinder *binder = AServiceManager_waitForService("android.system.virtualizationservice");
    if (binder == (AIBinder *)0) return (jobject)0;
    jobject result = AIBinder_toJavaBinder(env, binder);
    AIBinder_decStrong(binder);
    return result;
}
