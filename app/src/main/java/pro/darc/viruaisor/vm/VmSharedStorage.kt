package pro.darc.viruaisor.vm

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

internal object VmSharedStorage {
    const val TAG = "viruaisor-storage-0"
    const val GUEST_UID = 1000
    const val GUEST_GID = 984 // Paired Manjaro image: droid's primary users group.
    const val MASK = 18 // 022, supplied as a decimal number to AVF.
    const val FD_MARKER = "viruaisor.storage_fd=1"
    const val MOUNT_ARGUMENT = "systemd.mount-extra=viruaisor-storage-0:/mnt/shared:virtiofs:rw,nodev,nosuid,nofail,x-systemd.device-timeout=10s"

    fun directory(context: Context): File = File(requireNotNull(context.getExternalFilesDir(null)) {
        "Application external storage is unavailable."
    }, "storage/0")

    /** Root observers cannot call the UID-checked external-files Context API. */
    fun privilegedDirectory(packageName: String, appUid: Int): File {
        require(appUid > 0 && appUid % 100000 in 10000..19999) { "Expected application UID" }
        val installedPackage = pro.darc.viruaisor.AppIdentity.requirePackageName(packageName)
        return File("/storage/emulated/${appUid / 100000}/Android/data/$installedPackage/files/storage/0")
    }

    fun socket(context: Context) = socketPath(context.filesDir)

    internal fun socketPath(filesDir: File) = File(filesDir.canonicalFile, "shared/0.sock")

    internal fun privateBackendCommand(executable: String, uid: Int, directory: String, ownerPid: Int,
                                       delegated: Boolean = false): List<String> {
        require(uid > 0 && ownerPid > 1)
        return listOf(executable, "device", "fs", "--fd=3", "--tag=$TAG", "--shared-dir=$directory", "--cfg",
            "cache=never,timeout=0,negative_timeout=0,writeback=false,ugid_map='$GUEST_UID $GUEST_GID $uid $uid $MASK /'", "--disable-sandbox", "--skip-pivot-root=true",
            "--app-storage-owner-pid=$ownerPid") + if(delegated) listOf("--app-storage-client-fd=5") else emptyList()
    }

    fun privateExecutable(context: Context) = File(context.applicationInfo.nativeLibraryDir,"libvenus_desktop_crosvm.so").path

    fun prepare(context: Context) {
        val directory = directory(context)
        check(directory.canonicalFile == directory.absoluteFile.normalize()) { "Shared storage must not be a symbolic link." }
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create storage/0." }
        val socket = socket(context)
        check(socket.parentFile!!.mkdirs() || socket.parentFile!!.isDirectory)
        check(socket.canonicalFile == socket.absoluteFile.normalize()) { "Unsafe shared storage socket." }
        check(backends(context).isEmpty()) { "Previous virtio-fs backend is still running; retry after VM cleanup." }
        if (socket.exists()) {
            check(Os.lstat(socket.path).st_mode and OsConstants.S_IFMT == OsConstants.S_IFSOCK) { "Unexpected shared socket file." }
            check(socket.delete()) { "Cannot remove the stopped virtio-fs socket." }
        }
    }

    fun backends(context: Context): List<File> {
        val uid = context.applicationInfo.uid
        val directory = directory(context).path
        val socket = socket(context).path
        return File("/proc").listFiles().orEmpty().filter { process ->
            process.name.toIntOrNull() != null && runCatching {
                ownedBy(process, uid) && isBackendCommand(command(process), uid, directory, socket, privateExecutable(context))
            }.getOrDefault(false)
        }
    }

    fun ownedBy(process: File, uid: Int): Boolean {
        val lines = File(process, "status").readLines()
        val ids = lines.first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))
        return ids.size == 4 && ids.all { it.toIntOrNull() == uid } &&
            lines.first { it.startsWith("CapEff:") }.substringAfter(':').trim().toULong(16) == 0uL
    }

    fun command(process: File) = File(process, "cmdline").readText().split('\u0000').filter { it.isNotEmpty() }

    fun isBackendCommand(command: List<String>, uid: Int, directory: String, socket: String,
                         privateExecutable: String? = null): Boolean {
        if (privateExecutable != null) {
            val delegated=command.lastOrNull() == "--app-storage-client-fd=5"
            val owner = command.getOrNull(10)?.takeIf { it.startsWith("--app-storage-owner-pid=") }
                ?.substringAfter('=')?.toIntOrNull()
            if (owner != null && owner > 1 && command == privateBackendCommand(privateExecutable,uid,directory,owner,delegated)) return true
        }
        return command == listOf("/apex/com.android.virt/bin/crosvm", "device", "fs",
            "--socket=$socket", "--tag=$TAG", "--shared-dir=$directory", "--cfg",
            "ugid_map='$GUEST_UID $GUEST_GID $uid $uid $MASK /'", "--disable-sandbox", "--skip-pivot-root=true")
    }
}
