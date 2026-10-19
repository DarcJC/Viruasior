package pro.darc.viruaisor.gpu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootAccessClient
import pro.darc.viruaisor.root.RootOverlayBootstrap
import pro.darc.viruaisor.root.RootStorageAuthorization
import pro.darc.viruaisor.root.RootStorageService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent fixed-capability connection; it never shares the overlay lease's blocking RPC. */
internal class StorageRootConnection(private val app: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val bootstrap = Executors.newSingleThreadExecutor()
    private val rpc = Executors.newSingleThreadExecutor()
    private val connected = CompletableFuture<IBinder>()
    @Volatile private var closed = false
    private var token: IBinder? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) RootService.unbind(this) else connected.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Storage root helper disconnected")) }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Null storage root helper")) }
    }

    fun bind() {
        check(!closed)
        val root = RootAccessClient().request() as? RootAccess.Granted
            ?: error("Existing app root authorization unavailable; no policy or grant changed")
        main.post {
            if (!closed) runCatching {
                val task = RootService.bindOrTask(Intent(app, RootStorageService::class.java), Executor { main.post(it) }, connection)
                if (task != null) bootstrap.execute {
                    runCatching {
                        Shell.Builder.create().setContext(app)
                            .setCommands(*RootOverlayBootstrap.commands(root.suPath).toTypedArray())
                            .setTimeout(10).build().use { shell -> check(shell.isRoot); shell.execTask(task) }
                    }.onFailure { connected.completeExceptionally(it) }
                }
            }.onFailure { connected.completeExceptionally(it) }
        }
        connected.get(20, TimeUnit.SECONDS)
    }

    fun start(socket: ParcelFileDescriptor, death: IBinder): Int {
        check(!closed && token == null)
        token = death
        return call(RootStorageAuthorization.START, socket) { it.readInt() }
    }
    fun alive(): Boolean = token != null && !closed && call(RootStorageAuthorization.ALIVE) { it.readBoolean() }
    fun stop() {
        if (token != null) {
            call(RootStorageAuthorization.STOP) { Unit }
            token = null
        }
    }

    private fun <T> call(code: Int, socket: ParcelFileDescriptor? = null, read: (Parcel) -> T): T {
        val ownedToken = requireNotNull(token)
        return rpc.submit<T> {
            val request = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(RootStorageAuthorization.DESCRIPTOR)
                if (code == RootStorageAuthorization.START) request.writeTypedObject(requireNotNull(socket), 0)
                request.writeStrongBinder(ownedToken)
                val target = connected.get(2, TimeUnit.SECONDS)
                check(target.isBinderAlive && target.transact(code, request, reply, 0)) { "Storage root helper unavailable" }
                reply.readException()
                read(reply)
            } finally { request.recycle(); reply.recycle() }
        }.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        if (closed) return
        try { stop() }
        finally {
            closed = true
            main.post { runCatching { RootService.unbind(connection) } }
            bootstrap.shutdownNow(); rpc.shutdown()
        }
    }
}
