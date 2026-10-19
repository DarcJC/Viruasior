package pro.darc.viruaisor.vm

import java.io.File

/** A stopped check must not create/cache AVF's virtmgr before the Venus lease. */
internal class VmProcessQuiescence(
    private val proc: File,
    private val appUid: Int,
    private val owner: (File) -> Int,
    private val sharedBackend: (List<String>) -> Boolean,
) {
    fun requireStopped() {
        val processes = proc.listFiles() ?: error("Cannot inspect VM processes; rootfs is unchanged.")
        for (process in processes) {
            if (process.name.toIntOrNull() == null) continue
            val uid = try { owner(process) } catch (error: Exception) {
                if (!process.exists()) continue
                throw IllegalStateException("Cannot inspect process owner ${process.name}",error)
            }
            if (uid != appUid) continue
            val command = try { File(process,"cmdline").readText().split('\u0000').filter(String::isNotEmpty) }
            catch (error: Exception) {
                if (!process.exists()) continue
                throw IllegalStateException("Cannot inspect owned process ${process.name}; rootfs is unchanged.",error)
            }
            val executable = command.firstOrNull()?.substringAfterLast('/') ?: continue
            check(!(executable.startsWith("crosvm_") || executable == "crosvm" ||
                executable in setOf("libvenus_desktop_crosvm.so","libvenus_crosvm.so")) || sharedBackend(command)) {
                "An application-owned VM still runs (pid=${process.name}); rootfs is unchanged."
            }
        }
    }
}
