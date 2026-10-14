package pro.darc.viruaisor.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.Surface
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootRuntime
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Opt-in display-only broker. Root exists only while a live VM and an Android Surface need it. */
class RootAvfSurfaceBackend(context: Context) : DisplayBackend {
    override val name = "Root Direct Surface (experimental)"
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> main.post(command); Unit }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "viruaisor-root-display-client").apply { isDaemon = true }
    }
    private val sessionToken = Binder()
    private val mutableStatus = MutableStateFlow("Waiting for a live VM and Surface")
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    // These lifecycle fields are confined to the main looper; RPCs only use captured snapshots.
    private var target: DisplayTarget? = null
    private var onAttached: (() -> Unit)? = null
    private var running = false
    private var closed = false
    private var generation = 0L
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null

    fun setRunning(running: Boolean) = main.post {
        if (closed || this.running == running) return@post
        this.running = running
        if (running) connectIfNeeded() else disconnect()
    }

    override fun attach(target: DisplayTarget, onAttached: () -> Unit) {
        main.post {
            if (closed) return@post
            disconnect()
            this.target = target
            this.onAttached = onAttached
            generation++
            connectIfNeeded()
        }
    }

    override fun detach(surface: Surface) {
        main.post {
            if (target?.surface !== surface) return@post
            target = null
            onAttached = null
            generation++
            disconnect()
        }
    }

    fun close() = main.post {
        closed = true
        running = false
        target = null
        onAttached = null
        generation++
        disconnect()
        worker.shutdown()
    }

    private fun connectIfNeeded() {
        val expectedTarget = target ?: return
        if (!running || closed || connection != null || !expectedTarget.surface.isValid) return
        if (expectedTarget.parent?.isValid != true) {
            mutableStatus.value = "Root display attach failed: invalid SurfaceView parent"
            return
        }
        val root = RootRuntime.state.value as? RootAccess.Granted ?: run {
            mutableStatus.value = "Root authorization required"
            return
        }
        val expectedGeneration = generation
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this || closed || !running || target !== expectedTarget) {
                    safeUnbind(this)
                    return
                }
                binder = service
                worker.execute {
                    runCatching { rpc(service, RootDisplayAuthorization.ATTACH, expectedTarget) }
                        .onSuccess { identity -> main.post {
                            if (connection === this && generation == expectedGeneration && target === expectedTarget && running) {
                                mutableStatus.value = "Root Direct Surface attached ($identity)"
                                Log.i(TAG, mutableStatus.value)
                                onAttached?.invoke()
                            }
                        } }
                        .onFailure { error -> main.post {
                            if (connection === this) fail("Root display attach failed", error)
                        } }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) {
                    connection = null
                    binder = null
                    mutableStatus.value = "Root display helper disconnected; stop/start the VM to retry"
                    Log.w(TAG, mutableStatus.value)
                }
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection === this) fail("Root display helper refused binding", null)
            }
        }
        connection = conn
        mutableStatus.value = "Starting display-only root helper…"
        try {
            // Use a dedicated verified root shell solely for bootstrap, then close it. No cached
            // general-purpose root shell and no daemon/root-service registration in ServiceManager.
            val builder = Shell.Builder.create().setContext(app).setCommands(root.suPath).setTimeout(10)
            val task = RootService.bindOrTask(Intent(app, RootDisplayService::class.java), mainExecutor, conn)
            if (task != null) worker.execute {
                runCatching {
                    builder.build().use { shell ->
                        check(shell.isRoot) { "Display bootstrap shell is not root" }
                        shell.execTask(task)
                    }
                }.onFailure { error -> main.post {
                    if (connection === conn) fail("Root display bootstrap failed", error)
                } }
            }
            main.postDelayed({
                if (connection === conn && binder == null) fail("Root display binding timed out; reopen the app before retrying", null)
            }, BIND_TIMEOUT_MS)
        } catch (error: Exception) {
            fail("Root display bootstrap failed", error)
        }
    }

    private fun fail(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        disconnect()
        mutableStatus.value = "$message${error?.message?.let { ": $it" }.orEmpty()}"
    }

    private fun disconnect() {
        val oldConnection = connection
        val oldBinder = binder
        connection = null
        binder = null
        mutableStatus.value = "Waiting for a live VM and Surface"
        if (oldConnection != null) {
            // Never block the UI on Binder cleanup. The helper's watchdog bounds stuck RPCs.
            worker.execute {
                if (oldBinder != null) runCatching { rpc(oldBinder, RootDisplayAuthorization.DETACH) }
                    .onFailure { Log.w(TAG, "Root display detach failed", it) }
                main.post { safeUnbind(oldConnection) }
            }
        }
    }

    private fun safeUnbind(connection: ServiceConnection) {
        runCatching { RootService.unbind(connection) }
            .onFailure { Log.w(TAG, "Root display connection already released", it) }
    }

    private fun rpc(binder: IBinder, transaction: Int, target: DisplayTarget? = null): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RootDisplayAuthorization.DESCRIPTOR)
            data.writeStrongBinder(sessionToken)
            if (transaction == RootDisplayAuthorization.ATTACH) {
                val displayTarget = requireNotNull(target)
                // Delegate only the parent capability. The app-owned producer must NOT be sent.
                data.writeTypedObject(displayTarget.parent, 0)
                data.writeInt(displayTarget.bufferWidth)
                data.writeInt(displayTarget.bufferHeight)
            }
            check(binder.transact(transaction, data, reply, 0)) { "Root display RPC not handled" }
            reply.readException()
            return reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "ViruaisorRootDisplay"
        const val BIND_TIMEOUT_MS = 30_000L
    }
}
