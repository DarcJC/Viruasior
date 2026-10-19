package pro.darc.viruaisor.vm

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import pro.darc.viruaisor.gpu.StorageRootConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns only the fixed, UID-dropped FS leaf behind the authenticated root bridge. */
internal class VmSharedStorageBackend(context: Context, private val lost: () -> Unit) : AutoCloseable {
    private val app = context.applicationContext
    private val root = StorageRootConnection(app)
    private val death = Binder()
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it,"shared-storage-0") }
    @Volatile private var pid = 0
    private var monitor: ScheduledFuture<*>? = null
    private var closed = false
    @Volatile private var closing = false

    @Synchronized fun start(socket: ParcelFileDescriptor) {
        check(pid == 0 && !closed)
        try {
            worker.submit<Int> {
                check(!closing) { "Storage startup cancelled" }
                root.bind()
                check(!closing) { "Storage startup cancelled before spawning" }
                root.start(socket,death).also { pid=it }
            }.get(30,TimeUnit.SECONDS)
        } catch (error: Throwable) {
            closing=true
            throw error
        }
        Log.i(TAG,"STORAGE_BACKEND_STARTED pid=$pid owner=${Process.myPid()} uid=${Process.myUid()} transport=fd bridge=uid-dropped")
        monitor = worker.scheduleAtFixedRate({
            if (!closing && !runCatching { root.alive() }.getOrDefault(false)) {
                monitor?.cancel(false)
                lost()
            }
        },1,1,TimeUnit.SECONDS)
    }

    @Synchronized override fun close() {
        if (closed) return
        closing=true
        monitor?.cancel(false)
        val cleanup=worker.submit {
            root.close()
            Log.i(TAG,"STORAGE_BACKEND_STOPPED pid=$pid")
            pid=0
        }
        try { cleanup.get(12,TimeUnit.SECONDS) }
        finally {
            closed = true
            // Let an already queued bind/cleanup finish; a cancelled start cannot spawn afterward.
            worker.shutdown()
        }
    }
    companion object { private const val TAG="ViruaisorStorage" }
}

internal object VmStorageNative {
    init { System.loadLibrary("viruaisor_storage") }
    external fun spawn(command: Array<String>, socket: Int): Long
    external fun spawnRoot(command: Array<String>, socket: Int, namespace: Int, clientPidfd: Int): Long
    external fun openPidfd(pid: Int): Int
    external fun alive(handle: Long): Boolean
    external fun stop(handle: Long)
    external fun exchange(first: String, second: String)
}
