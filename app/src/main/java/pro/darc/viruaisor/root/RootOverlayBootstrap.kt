package pro.darc.viruaisor.root

/** Fixed bootstrap only; never exposed as a Binder shell capability. */
internal object RootOverlayBootstrap {
    fun commands(suPath: String): List<String> {
        if (suPath !in setOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")) {
            throw SecurityException("Unexpected root executable")
        }
        // Unshare BEFORE libsu starts Java threads, not after entering the app's namespace.
        // No --mount-master and no global mount or propagation change here.
        return listOf(suPath, "-c", "exec /system/bin/unshare -m /system/bin/sh")
    }
}
