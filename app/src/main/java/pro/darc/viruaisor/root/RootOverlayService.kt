package pro.darc.viruaisor.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.system.Os
import android.system.StructStat
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Only sentinel or pinned stock-copy leases. VM startup remains in the ordinary app. */
class RootOverlayService : RootService() {
    private lateinit var authorization: RootOverlayAuthorization
    private val operation = Any()
    @Volatile private var child: java.lang.Process? = null

    override fun onCreate() {
        check(Process.myUid() == 0 && packageName == "pro.darc.viruaisor")
        authorization = RootOverlayAuthorization(applicationInfo.uid)
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            authorization.enforce(getCallingUid(), code) // Before deserializing anything.
            require(reply != null && flags and IBinder.FLAG_ONEWAY == 0)
            val caller = getCallingPid() // Never accept a caller-selected PID.
            data.enforceInterface(RootOverlayAuthorization.DESCRIPTOR)
            val token = requireNotNull(data.readString())
            val mode = requireNotNull(data.readString())
            val callback = requireNotNull(data.readStrongBinder())
            data.enforceNoDataAvail()
            require(RootOverlayAuthorization.validRequest(token, mode) && callback.isBinderAlive)
            require(code == RootOverlayAuthorization.transactionFor(mode)) { "Capability/profile mismatch" }
            val identity = clearCallingIdentity()
            try {
                val result = synchronized(operation) { probe(caller, token, mode, callback) }
                reply.writeNoException()
                reply.writeString(result)
            } finally {
                restoreCallingIdentity(identity)
            }
            return true
        }
    }

    private fun probe(caller: Int, token: String, mode: String, callback: IBinder): String {
        val executable = File(applicationInfo.nativeLibraryDir, "libvenus_overlay_probe.so")
        check(executable.isFile && executable.canExecute())
        val desktop = mode == "venus-desktop"
        val candidate = mode == "venus-exec" || desktop
        val stock = mode == "stock-exec" || candidate
        val source = if (stock) readArtifact(File(RootOverlayArtifactPolicy.CROSVM_PATH)) else null
        if (source != null) {
            RootOverlayArtifactPolicy.enforce(source.hash, readArtifact(File(RootOverlayArtifactPolicy.VIRTMGR_PATH)).hash)
            Log.i(TAG, "STOCK_SOURCE_PINNED sha256=${source.hash} dev=${source.stat.st_dev} ino=${source.stat.st_ino}")
        }
        val arguments = mutableListOf(executable.path, caller.toString(), applicationInfo.uid.toString(), token, mode)
        source?.let { arguments.add(it.stat.st_dev.toString()); arguments.add(it.stat.st_ino.toString()) }
        val input = if (candidate) File(applicationInfo.nativeLibraryDir, if (desktop) RootOverlayArtifactPolicy.DESKTOP_LIBRARY else RootOverlayArtifactPolicy.CANDIDATE_LIBRARY) else File(RootOverlayArtifactPolicy.CROSVM_PATH)
        if (candidate) {
            val directory = Os.lstat(applicationInfo.nativeLibraryDir)
            check(directory.st_uid == 1000 && directory.st_mode and 0x12 == 0)
            val artifact = readArtifact(input)
            if (desktop) RootOverlayArtifactPolicy.enforceDesktopCandidate(artifact.hash, artifact.stat.st_size, artifact.stat.st_uid, artifact.stat.st_mode)
            else RootOverlayArtifactPolicy.enforceCandidate(artifact.hash, artifact.stat.st_size, artifact.stat.st_uid, artifact.stat.st_mode)
            Log.i(TAG, "CANDIDATE_PINNED sha256=${artifact.hash} size=${artifact.stat.st_size}")
            arguments.addAll(listOf(artifact.stat.st_dev.toString(), artifact.stat.st_ino.toString(), artifact.stat.st_size.toString()))
        }
        val builder = ProcessBuilder(arguments).redirectErrorStream(true)
        if (stock) builder.redirectInput(input)
        val process = builder.start()
        child = process
        val output = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                val limited = line.take(1024)
                synchronized(output) { if (output.length < 16 * 1024) output.appendLine(limited) }
                Log.i(TAG, limited)
                if (limited.startsWith("MOUNT_READY") || limited.startsWith("STOCK_EXEC_OBSERVED")) {
                    val outside = File(filesDir, "venus-overlay-lab/$token.target").readText().trim()
                    val originalStock = if (source != null) {
                        val current = readArtifact(File(RootOverlayArtifactPolicy.CROSVM_PATH))
                        check(current.hash == source.hash && current.stat.st_dev == source.stat.st_dev && current.stat.st_ino == source.stat.st_ino)
                        " root_stock_original=true root_source_dev=${source.stat.st_dev} root_source_ino=${source.stat.st_ino}"
                    } else ""
                    val status = "$limited root_outside=$outside$originalStock"
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeInterfaceToken(RootOverlayAuthorization.DESCRIPTOR)
                        parcel.writeString(status)
                        callback.transact(if (limited.startsWith("MOUNT_READY")) 1 else 2, parcel, null, IBinder.FLAG_ONEWAY)
                    } catch (error: Exception) {
                        Log.i(TAG, "Owner callback gone; native pidfd/lease still owns rollback: $error")
                    } finally { parcel.recycle() }
                }
            } }
        }, "venus-overlay-native-output").apply { isDaemon = true; start() }
        try {
            process.outputStream.close()
            check(process.waitFor(if (desktop) 86405 else if (stock) 25 else 15, TimeUnit.SECONDS)) { "Native lease exceeded the profile deadline" }
            reader.join(1000)
            check(!reader.isAlive) { "Native output did not close" }
            return "native_exit=${process.exitValue()}\n${synchronized(output) { output.toString() }}"
        } finally {
            process.destroyForcibly()
            child = null
        }
    }

    override fun onDestroy() {
        child?.let(RootOverlayChildCleanup::finish)
        Log.i(TAG, "One-shot root service destroyed")
    }

    private data class Artifact(val hash: String, val stat: StructStat)
    private fun readArtifact(file: File): Artifact = FileInputStream(file).use { input ->
        val stat = Os.fstat(input.fd)
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        Artifact(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, stat)
    }

    private companion object { const val TAG = "ViruaisorOverlayRoot" }
}
