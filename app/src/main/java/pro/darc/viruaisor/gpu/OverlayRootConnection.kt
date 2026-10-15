package pro.darc.viruaisor.gpu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootAccessClient
import pro.darc.viruaisor.root.RootOverlayAuthorization
import pro.darc.viruaisor.root.RootOverlayBootstrap
import pro.darc.viruaisor.root.RootOverlayService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bootstrap is separate from the fixed-capability RPC. Never use the cached global shell. */
internal class OverlayRootConnection(private val app: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val bootstrap = Executors.newSingleThreadExecutor()
    private val connected = CompletableFuture<IBinder>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
        override fun onServiceDisconnected(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Root helper disconnected")) }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Null root helper")) }
    }
    fun bind() {
        val root = RootAccessClient().request() as? RootAccess.Granted
            ?: error("Existing app root authorization unavailable; no policy or grant changed")
        main.post {
            runCatching {
                val task = RootService.bindOrTask(Intent(app, RootOverlayService::class.java), Executor { main.post(it) }, connection)
                if (task != null) bootstrap.execute {
                    runCatching {
                        Shell.Builder.create().setContext(app)
                            .setCommands(*RootOverlayBootstrap.commands(root.suPath).toTypedArray())
                            .setTimeout(10).build().use { shell ->
                            check(shell.isRoot)
                            shell.execTask(task)
                        }
                    }.onFailure { connected.completeExceptionally(it) }
                }
            }.onFailure { connected.completeExceptionally(it) }
        }
        connected.get(20, TimeUnit.SECONDS)
    }
    fun probe(token: String, mode: String, callback: IBinder): String {
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(RootOverlayAuthorization.DESCRIPTOR)
            request.writeString(token); request.writeString(mode); request.writeStrongBinder(callback)
            check(connected.get().transact(RootOverlayAuthorization.transactionFor(mode), request, reply, 0))
            reply.readException()
            return reply.readString().orEmpty()
        } finally { request.recycle(); reply.recycle() }
    }
    override fun close() {
        main.post { runCatching { RootService.unbind(connection) } }
        bootstrap.shutdownNow()
    }
}
