package pro.darc.viruaisor

import java.io.File

/** Installed application identity; source/JNI namespaces and wire interface names are separate. */
internal object AppIdentity {
    const val ACTION_PREFIX = BuildConfig.APPLICATION_ID + ".action."

    fun requirePackageName(value: String): String {
        require(value.length <= 223 && value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "Invalid installed application package name"
        }
        return value
    }

    fun vmProcess(packageName: String) = requirePackageName(packageName) + ":venus_vm"
    fun rootProcess(packageName: String) = requirePackageName(packageName) + ":root:0"

    fun isOverlaySnapshot(filesDir: File, executable: String): Boolean {
        val file = File(executable)
        if (!file.name.matches(Regex("[0-9a-f]{32}\\.snapshot"))) return false
        val directory = File(filesDir, "venus-overlay-lab")
        return file.parentFile == directory || file.canonicalFile.parentFile == directory.canonicalFile
    }
}
