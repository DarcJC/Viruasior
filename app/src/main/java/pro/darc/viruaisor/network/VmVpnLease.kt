package pro.darc.viruaisor.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootAccessClient
import pro.darc.viruaisor.root.RootOverlayBootstrap
import pro.darc.viruaisor.root.RootVpnService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VmNetworkState(val vpn: Boolean = true, val message: String = "VPN 共享：启用后断线暂停外网")

object VmNetworkRuntime {
    private val mutable = MutableStateFlow(VmNetworkState())
    val state = mutable.asStateFlow()
    fun initialize(context: Context) {
        val enabled = context.getSharedPreferences("vm-network", Context.MODE_PRIVATE).getBoolean("vpn", true)
        if (enabled != mutable.value.vpn) mutable.value = VmNetworkState(enabled, if (enabled) "等待启动 VPN 共享" else "普通 AVF 网络")
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("vm-network", Context.MODE_PRIVATE).edit().putBoolean("vpn", enabled).apply()
        mutable.value = VmNetworkState(enabled, if (enabled) "等待启动 VPN 共享；VPN 断开时暂停外网" else "普通 AVF 网络")
    }
    internal fun message(value: String) { mutable.value = mutable.value.copy(message = value) }
}

/** The app owns lifecycle and connectivity observation; Root owns only its fixed network lease. */
internal class VmVpnLease(private val app: Context, private val scope: CoroutineScope, private val failed: (Throwable) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val bootstrap = Executors.newSingleThreadExecutor()
    private val rpc = Executors.newSingleThreadExecutor()
    private val connected = CompletableFuture<IBinder>()
    private val owner = Binder()
    private var polling: Job? = null
    private var armed = false
    @Volatile private var closed = false
    @Volatile private var changed = true
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { changed = true }
        override fun onLost(network: android.net.Network) { changed = true }
        override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) { changed = true }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) RootService.unbind(this) else connected.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            connected.completeExceptionally(IllegalStateException("VPN root helper disconnected"))
            if (!closed) failed(IllegalStateException("VPN root helper disconnected; VM must stop"))
        }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Null VPN helper")) }
    }

    fun open() {
        val root = RootAccessClient().request() as? RootAccess.Granted ?: error("VPN 共享需要现有 Root 授权")
        main.post {
            runCatching {
                val task = RootService.bindOrTask(Intent(app, RootVpnService::class.java), java.util.concurrent.Executor { main.post(it) }, connection)
                if (task != null) bootstrap.execute {
                    runCatching {
                        Shell.Builder.create().setContext(app).setCommands(*RootOverlayBootstrap.commands(root.suPath).toTypedArray())
                            .setTimeout(10).build().use { shell -> check(shell.isRoot); shell.execTask(task) }
                    }.onFailure { connected.completeExceptionally(it) }
                }
            }.onFailure { connected.completeExceptionally(it) }
        }
        connected.get(20, TimeUnit.SECONDS)
        armed = true // START may time out after installing the guard; always attempt STOP on cleanup.
        VmNetworkRuntime.message(call(RootVpnService.START))
        cm.registerDefaultNetworkCallback(callback)
        polling = scope.launch {
            while (!closed) {
                delay(if (changed) 250 else 1500)
                changed = false
                runCatching {
                    val status = call(RootVpnService.STATUS)
                    check(!status.startsWith("ERROR:")) { status }
                    VmNetworkRuntime.message(status)
                }.onFailure { error -> if (!closed) { VmNetworkRuntime.message("VPN 共享失败；正在停止 VM：${error.message}"); failed(error) }; return@launch }
            }
        }
    }

    private fun call(code: Int): String = rpc.submit<String> {
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(RootVpnService.DESCRIPTOR); request.writeStrongBinder(owner)
            val target = connected.get(2, TimeUnit.SECONDS)
            check(target.isBinderAlive && target.transact(code, request, reply, 0))
            reply.readException()
            requireNotNull(reply.readString())
        } finally { request.recycle(); reply.recycle() }
    }.get(30, TimeUnit.SECONDS)

    override fun close() {
        if (closed) return
        closed = true; polling?.cancel()
        runCatching { cm.unregisterNetworkCallback(callback) }
        try {
            if (armed) VmNetworkRuntime.message(call(RootVpnService.STOP))
        } catch (failure: Throwable) {
            VmNetworkRuntime.message("VPN 保护规则等待回收：${failure.message}")
            throw failure
        } finally {
            main.post { runCatching { RootService.unbind(connection) } }
            bootstrap.shutdownNow(); rpc.shutdown()
        }
    }
}
