package pro.darc.viruaisor.display

import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** Internal lab guard, not proof that the global display Binder belongs to this app's VM. */
internal class CrosvmProcessGuard(
    private val proc: File = File("/proc"),
    private val readExecutable: (File) -> String? = { process ->
        try {
            Files.readSymbolicLink(File(process, "exe").toPath()).toString()
        } catch (_: NoSuchFileException) {
            // Kernel threads have no executable; processes may also disappear during enumeration.
            null
        }
    },
) {
    fun requireSingle(): File {
        val processes = proc.listFiles() ?: error("Cannot inspect active VM processes")
        val crosvm = processes.filter { process ->
            if (process.name.toIntOrNull()?.let { it > 0 } != true) return@filter false
            val executable = try {
                readExecutable(process)
            } catch (error: Exception) {
                // A visibility failure must not silently hide a competing VM.
                throw IllegalStateException("Cannot inspect executable for pid=${process.name}", error)
            }
            executable != null && AVF_CROSVM.matches(executable)
        }
        check(crosvm.size == 1) {
            "Root display experiment requires exactly one crosvm; found ${crosvm.size}"
        }
        return crosvm.single()
    }

    private companion object {
        // AVF renames argv[0]/comm to crosvm_<VM name> (comm may also be truncated). The kernel's
        // executable link is stable across those names. Count old APEX instances still running
        // after an update as well; unknown binaries must not impersonate an AVF VM by name alone.
        val AVF_CROSVM = Regex("^/apex/com\\.android\\.virt(?:@[0-9]+)?/bin/crosvm(?: \\(deleted\\))?$")
    }
}
