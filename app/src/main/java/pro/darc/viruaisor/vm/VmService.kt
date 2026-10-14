package pro.darc.viruaisor.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.darc.viruaisor.MainActivity
import pro.darc.viruaisor.R
import pro.darc.viruaisor.display.InputBackendRegistry
import java.util.concurrent.Executor

class VmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: AvfVmEngine
    private lateinit var controlClient: VsockControlClient
    private var bundle: VmBundle? = null
    private var readinessJob: Job? = null
    private var desktopRequested = false
    private var desktopStarted = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate service=${System.identityHashCode(this)}")
        createNotificationChannel()
        val executor = Executor { command ->
            scope.launch { command.run() }
            Unit
        }
        engine = AvfVmEngine(this, executor, ::onEngineEvent)
        InputBackendRegistry.bind(engine)
        controlClient = VsockControlClient(engine)
        scope.launch {
            VmRuntime.initialize(this@VmService)
            bundle = VmRuntime.state.value.bundle
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand service=${System.identityHashCode(this)} action=${intent?.action} startId=$startId")
        ensureForeground("Preparing VM")
        when (intent?.action) {
            ACTION_IMPORT -> {
                val uri = intent.data
                if (uri == null) fail("No VM bundle was selected.") else scope.launch { importBundle(uri) }
            }
            ACTION_START -> scope.launch { startVm() }
            ACTION_STOP -> scope.launch { stopVm() }
            ACTION_SURFACE_READY -> scope.launch {
                desktopRequested = true
                startDesktopIfReady()
            }
            ACTION_SURFACE_GONE -> scope.launch {
                desktopRequested = false
                stopDesktopIfStarted()
            }
            ACTION_DEBUG_CONSOLE -> scope.launch {
                runCatching { engine.sendConsoleInput(intent.getStringExtra(EXTRA_TEXT).orEmpty()) }
                    .onFailure { fail("Console input failed: ${it.rootMessage()}") }
            }
            else -> finishForegroundIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy service=${System.identityHashCode(this)} status=${VmRuntime.state.value.status}")
        readinessJob?.cancel()
        InputBackendRegistry.unbind(engine)
        engine.clearCallback()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun importBundle(uri: Uri) {
        VmRuntime.initialize(this)
        if (VmRuntime.state.value.support !is AvfSupport.Available) {
            fail("AVF is unavailable on this installation.")
            return
        }
        if (VmRuntime.state.value.isActive) {
            fail("Stop the VM before importing another bundle.")
            return
        }

        VmRuntime.setImporting()
        updateNotification("Importing VM bundle")
        try {
            withContext(Dispatchers.IO) {
                engine.deleteDefinition()
                VmBundleStore(this@VmService).import(uri)
            }.also {
                bundle = it
                VmRuntime.setBundle(it)
            }
        } catch (error: Throwable) {
            fail("Import failed: ${error.rootMessage()}")
            return
        }
        finishForegroundIfIdle()
    }

    private suspend fun startVm() {
        Log.d(TAG, "startVm service=${System.identityHashCode(this)} engine=${System.identityHashCode(engine)}")
        VmRuntime.initialize(this)
        val state = VmRuntime.state.value
        if (state.support !is AvfSupport.Available) {
            fail("AVF is unavailable on this installation.")
            return
        }
        val selectedBundle = state.bundle ?: VmBundleStore(this).currentBundle()
        if (selectedBundle == null) {
            fail("Import a VM bundle before starting.")
            return
        }
        if (state.isActive) return

        bundle = selectedBundle
        desktopStarted = false
        VmRuntime.setStatus(VmStatus.Starting, "VM starting")
        updateNotification("VM starting")
        engine.start(selectedBundle)
    }

    private suspend fun stopVm() {
        val selectedBundle = bundle ?: VmRuntime.state.value.bundle
        if (!VmRuntime.state.value.isActive || VmRuntime.state.value.status is VmStatus.Stopping) return

        readinessJob?.cancel()
        VmRuntime.setStatus(VmStatus.Stopping, "Requesting guest shutdown over vsock")
        updateNotification("VM stopping")
        val standardAgentSent = engine.requestGuestAgentShutdown()
        val rawVsockSent = !standardAgentSent && runCatching {
            requireNotNull(selectedBundle) { "The active bundle metadata is unavailable." }
            controlClient.shutdown(selectedBundle.vsockPort)
        }.isSuccess
        Log.i(
            TAG,
            "shutdown requested: standardAgent=$standardAgentSent rawVsock=$rawVsockSent",
        )
        if (!standardAgentSent && !rawVsockSent) {
            engine.requestHardStop()
            return
        }

        delay(GRACEFUL_SHUTDOWN_TIMEOUT_MS)
        if (VmRuntime.state.value.status is VmStatus.Stopping) engine.requestHardStop()
    }

    private fun onEngineEvent(event: VmEngineEvent) {
        when (event) {
            VmEngineEvent.Running -> {
                val current = VmRuntime.state.value.status
                if (current !is VmStatus.GuestAgentReady && current !is VmStatus.Stopping) {
                    VmRuntime.setStatus(VmStatus.Running, "VM running; waiting for guest agent")
                    updateNotification("VM running")
                    beginReadinessProbe()
                }
            }
            VmEngineEvent.GuestAgentReady -> {
                readinessJob?.cancel()
                if (VmRuntime.state.value.status !is VmStatus.Stopping) {
                    VmRuntime.setStatus(VmStatus.GuestAgentReady, "Guest agent ready")
                    updateNotification("Guest agent ready")
                    scope.launch { startDesktopIfReady() }
                }
            }
            is VmEngineEvent.Error -> fail("VM error: ${event.message}")
            is VmEngineEvent.Stopped -> {
                readinessJob?.cancel()
                if (event.expected) {
                    VmRuntime.setStatus(VmStatus.Stopped, "VM stopped")
                } else {
                    val reason = stopReason(event.reason)
                    VmRuntime.setStatus(VmStatus.Crashed(reason), "VM crashed: $reason")
                }
                finishForegroundIfIdle()
            }
        }
    }

    private fun beginReadinessProbe() {
        val selectedBundle = bundle ?: return
        readinessJob?.cancel()
        readinessJob = scope.launch {
            repeat(READY_ATTEMPTS) {
                val status = VmRuntime.state.value.status
                if (status !is VmStatus.Running && status !is VmStatus.Starting) return@launch
                val ready = runCatching {
                    controlClient.awaitReady(selectedBundle.vsockPort)
                }.getOrDefault(false)
                if (ready) {
                    VmRuntime.setStatus(VmStatus.GuestAgentReady, "Guest agent ready")
                    updateNotification("Guest agent ready")
                    startDesktopIfReady()
                    return@launch
                }
                delay(READY_RETRY_MS)
            }
            if (VmRuntime.state.value.status is VmStatus.Running) {
                VmRuntime.setStatus(VmStatus.Running, "VM running; guest agent did not become ready")
                updateNotification("VM running (agent unavailable)")
            }
        }
    }

    private fun startDesktopIfReady() {
        if (!desktopRequested || desktopStarted) return
        if (VmRuntime.state.value.status !is VmStatus.GuestAgentReady) return
        val selectedBundle = bundle ?: return
        runCatching { controlClient.startDesktop(selectedBundle.vsockPort, selectedBundle.gpuProfile) }
            .onSuccess {
                desktopStarted = true
                VmRuntime.setStatus(VmStatus.GuestAgentReady, "Guest agent ready; desktop requested")
            }
            .onFailure { error ->
                VmRuntime.setStatus(
                    VmStatus.GuestAgentReady,
                    "Guest agent ready; desktop start failed: ${error.rootMessage()}",
                )
            }
    }

    private fun stopDesktopIfStarted() {
        if (!desktopStarted) return
        val selectedBundle = bundle ?: return
        runCatching { controlClient.stopDesktop(selectedBundle.vsockPort, selectedBundle.gpuProfile) }
        desktopStarted = false
    }

    private fun fail(message: String) {
        VmRuntime.setStatus(VmStatus.Crashed(message), message)
        updateNotification(message)
        finishForegroundIfIdle()
    }

    private fun ensureForeground(text: String) {
        if (foreground) {
            updateNotification(text)
            return
        }
        startForeground(
            NOTIFICATION_ID,
            notification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        foreground = true
    }

    private fun updateNotification(text: String) {
        if (foreground) getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun finishForegroundIfIdle() {
        val status = VmRuntime.state.value.status
        if (status is VmStatus.Starting || status is VmStatus.Running ||
            status is VmStatus.GuestAgentReady || status is VmStatus.Stopping ||
            status is VmStatus.Importing
        ) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vm_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun stopReason(reason: Int): String = when (reason) {
        -1 -> "virtualization service died"
        0 -> "infrastructure error"
        1 -> "killed"
        3 -> "shutdown"
        4 -> "start failed"
        5 -> "unexpected reboot"
        6 -> "guest or crosvm crash"
        16 -> "hangup"
        else -> "AVF stop reason $reason"
    }

    companion object {
        private const val TAG = "ViruaisorService"
        private const val ACTION_IMPORT = "pro.darc.viruaisor.action.IMPORT_VM"
        private const val ACTION_START = "pro.darc.viruaisor.action.START_VM"
        private const val ACTION_STOP = "pro.darc.viruaisor.action.STOP_VM"
        private const val ACTION_SURFACE_READY = "pro.darc.viruaisor.action.SURFACE_READY"
        private const val ACTION_SURFACE_GONE = "pro.darc.viruaisor.action.SURFACE_GONE"
        private const val ACTION_DEBUG_CONSOLE = "pro.darc.viruaisor.action.DEBUG_CONSOLE"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "vm_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val READY_ATTEMPTS = 60
        private const val READY_RETRY_MS = 1_000L
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000L

        fun importIntent(context: Context, uri: Uri) =
            Intent(context, VmService::class.java).setAction(ACTION_IMPORT).setData(uri)

        fun startIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_STOP)

        fun surfaceReadyIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_SURFACE_READY)

        fun surfaceGoneIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_SURFACE_GONE)

        fun debugConsoleIntent(context: Context, text: String) =
            Intent(context, VmService::class.java)
                .setAction(ACTION_DEBUG_CONSOLE)
                .putExtra(EXTRA_TEXT, text)
    }
}
