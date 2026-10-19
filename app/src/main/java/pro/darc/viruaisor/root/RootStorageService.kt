package pro.darc.viruaisor.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import pro.darc.viruaisor.vm.VmStorageNative
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Fixed FS leaf only. No filesystem path, executable, UID or namespace is caller-selected. */
class RootStorageService : RootService() {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "storage-root-owner") }
    private data class Session(val uid: Int, val pid: Int, val token: IBinder,
                               val death: IBinder.DeathRecipient, val handle: Long)
    private var session: Session? = null // Confined to worker; it also owns the native child's lifetime.

    override fun onCreate() {
        check(Process.myUid() == 0 && packageName == pro.darc.viruaisor.BuildConfig.APPLICATION_ID)
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = getCallingUid()
            val pid = getCallingPid()
            RootStorageAuthorization.enforce(applicationInfo.uid, uid, pid, code)
            require(reply != null && flags and IBinder.FLAG_ONEWAY == 0)
            data.enforceInterface(RootStorageAuthorization.DESCRIPTOR)
            val socket = if (code == RootStorageAuthorization.START) {
                requireNotNull(data.readTypedObject(ParcelFileDescriptor.CREATOR))
            } else null
            try {
                val token = requireNotNull(data.readStrongBinder())
                data.enforceNoDataAvail()
                val identity = clearCallingIdentity()
                try {
                    when (code) {
                        RootStorageAuthorization.START -> {
                            // The queued operation owns its duplicate even when the Binder caller times out.
                            val ownedSocket = ParcelFileDescriptor.dup(requireNotNull(socket).fileDescriptor)
                            val pending = try { worker.submit<Int> { ownedSocket.use { start(uid, pid, token, it) } } }
                            catch (error: Throwable) { ownedSocket.close(); throw error }
                            val child = pending.get(8, TimeUnit.SECONDS)
                            reply.writeNoException(); reply.writeInt(child)
                        }
                        RootStorageAuthorization.ALIVE -> {
                            val alive = worker.submit<Boolean> {
                                val owned = owned(uid, pid, token)
                                owned != null && VmStorageNative.alive(owned.handle)
                            }.get(5, TimeUnit.SECONDS)
                            reply.writeNoException(); reply.writeBoolean(alive)
                        }
                        RootStorageAuthorization.STOP -> {
                            worker.submit { owned(uid, pid, token)?.let { stop() } }.get(8, TimeUnit.SECONDS)
                            reply.writeNoException()
                        }
                    }
                } finally { restoreCallingIdentity(identity) }
            } catch (error: Exception) {
                // Binder marshals RuntimeException, while Future.get wraps failures in a checked exception.
                val cause = (error as? ExecutionException)?.cause ?: error
                throw (cause as? RuntimeException ?: IllegalStateException("Storage root request failed: ${cause.message}", cause))
            } finally { socket?.close() }
            return true
        }
    }

    private fun owned(uid: Int, pid: Int, token: IBinder): Session? = session?.also {
        RootStorageAuthorization.enforceOwner(it.uid, it.pid, it.token, uid, pid, token)
    }

    private fun start(uid: Int, pid: Int, token: IBinder, socket: ParcelFileDescriptor): Int {
        check(session == null) { "A storage backend is already owned" }
        check(token.isBinderAlive)
        check(Os.fstat(socket.fileDescriptor).st_mode and OsConstants.S_IFMT == OsConstants.S_IFSOCK)
        val executableDirectory = File(applicationInfo.nativeLibraryDir)
        val directoryStat = Os.lstat(executableDirectory.path)
        check(directoryStat.st_uid == 1000 && directoryStat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR &&
            directoryStat.st_mode and 0x12 == 0 && executableDirectory.canonicalFile == executableDirectory.absoluteFile.normalize()) {
            "Storage executable directory is not the installer-owned immutable package directory"
        }
        val launcher = File(executableDirectory, "libviruaisor_storage_launcher.so")
        val launcherStat = Os.lstat(launcher.path)
        check(launcherStat.st_uid in setOf(0, 1000) && launcherStat.st_mode in setOf(0x81ed, 0x816d)) { "Storage launcher is not installer-owned" }
        val candidate = File(executableDirectory, RootOverlayArtifactPolicy.DESKTOP_LIBRARY)
        val candidateStat = Os.lstat(candidate.path)
        check(candidateStat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG)
        FileInputStream(candidate).use { input ->
            val stat = Os.fstat(input.fd)
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
            RootOverlayArtifactPolicy.enforceDesktopCandidate(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                stat.st_size, stat.st_uid, stat.st_mode)
        }
        val directory = pro.darc.viruaisor.vm.VmSharedStorage.privilegedDirectory(packageName, uid)
        val sharedStat = Os.lstat(directory.path)
        check(sharedStat.st_uid == uid && sharedStat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR) {
            "Storage directory ownership mismatch: expected=$uid actual=${sharedStat.st_uid} mode=${sharedStat.st_mode}"
        }
        val death = IBinder.DeathRecipient { runCatching { worker.execute { if (session?.token == token) stop() } } }
        token.linkToDeath(death, 0)
        var childHandle = 0L
        try {
            ParcelFileDescriptor.adoptFd(VmStorageNative.openPidfd(pid)).use { client ->
                RootStorageAuthorization.verifyProcess(File("/proc/$pid/status").readText(), File("/proc/$pid/cmdline").readText(), uid, packageName)
                ParcelFileDescriptor.open(File("/proc/$pid/ns/mnt"), ParcelFileDescriptor.MODE_READ_ONLY).use { namespace ->
                    val command = arrayOf(launcher.path, uid.toString(), Process.myPid().toString(), candidate.path, directory.path, packageName)
                    childHandle = VmStorageNative.spawnRoot(command, socket.fd, namespace.fd, client.fd)
                }
            }
            session = Session(uid, pid, token, death, childHandle)
            check(token.isBinderAlive && VmStorageNative.alive(childHandle)) { "Storage owner or child exited during startup" }
            Log.i(TAG, "STORAGE_ROOT_STARTED pid=${childHandle.toInt()} client=$pid uid=$uid")
            return childHandle.toInt()
        } catch (error: Throwable) {
            if (childHandle != 0L) VmStorageNative.stop(childHandle)
            session = null
            token.unlinkToDeath(death, 0)
            throw error
        }
    }

    private fun stop() {
        val current = session ?: return
        VmStorageNative.stop(current.handle)
        session = null
        current.token.unlinkToDeath(current.death, 0)
        Log.i(TAG, "STORAGE_ROOT_STOPPED pid=${current.handle.toInt()} client=${current.pid}")
    }

    override fun onUnbind(intent: Intent): Boolean {
        worker.execute { stop() }
        return false
    }
    override fun onDestroy() {
        try { worker.submit { stop() }.get(8, TimeUnit.SECONDS) }
        catch (error: Exception) { Log.e(TAG, "Storage root cleanup failed", error) }
        finally { worker.shutdown() }
    }
    private companion object { const val TAG = "ViruaisorStorageRoot" }
}
