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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pro.darc.viruaisor.gpu.VenusDesktopLease
import pro.darc.viruaisor.MainActivity
import pro.darc.viruaisor.R
import pro.darc.viruaisor.display.InputBackendRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

internal class PendingLifecycleCommands {
    private val count = AtomicInteger()

    fun begin() {
        count.incrementAndGet()
    }

    fun complete(): Boolean {
        val remaining = count.decrementAndGet()
        check(remaining >= 0) { "Lifecycle command completion was not balanced" }
        return remaining == 0
    }

    fun hasPending(): Boolean = count.get() != 0
}

class VmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: AvfVmEngine
    private lateinit var controlClient: VsockControlClient
    private var bundle: VmBundle? = null
    private var readinessJob: Job? = null
    private var desktopRequested = false
    private val desktopStartGate = DesktopStartGate()
    private var foreground = false
    private var venusLease: VenusDesktopLease? = null
    private var vpnLease: pro.darc.viruaisor.network.VmVpnLease? = null
    private var runtimeWakeLock: PowerManager.WakeLock? = null
    private val lifecycleMutex=Mutex()
    private val pendingLifecycleCommands = PendingLifecycleCommands()
    private val displayMutex = Mutex()
    private val resizeRequests = Channel<Pair<Int,DisplayMode>>(Channel.CONFLATED)
    private var displayMonitor: Job? = null
    private var resizeTimeout: Job? = null
    @Volatile private var displayGeneration = 0
    private lateinit var storageManager: VmStorageManager
    private var storageOperation = false
    private var pendingRootfsBoot = false
    private var storageChecked = false
    private var storageReadyJob: Job? = null
    private var rootfsBootTimeout: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate service=${System.identityHashCode(this)}")
        createNotificationChannel()
        storageManager = VmStorageManager(this)
        pro.darc.viruaisor.network.VmNetworkRuntime.initialize(this)
        val executor = Executor { command ->
            scope.launch { command.run() }
            Unit
        }
        engine = AvfVmEngine(this, executor, ::onEngineEvent) { venusLease?.active == true }
        InputBackendRegistry.bind(engine)
        controlClient = VsockControlClient(engine)
        GuestPasswordRuntime.bind(this) { password, complete ->
            scope.launch {
                val success=lifecycleMutex.withLock {
                    val selected=bundle
                    selected != null && VmRuntime.state.value.status is VmStatus.GuestAgentReady &&
                        runCatching { controlClient.changeDroidPassword(selected.vsockPort,password) }.isSuccess
                }
                complete(success)
            }
        }
        scope.launch {
            for ((generation,size) in resizeRequests) {
                displayMutex.withLock {
                    val selected = bundle
                    if (generation != displayGeneration || selected == null || !desktopStartGate.started) return@withLock
                    val previous = VmRuntime.state.value.display.actual
                    val result = runCatching {
                        check(size in VmRuntime.state.value.display.modes) { "Guest does not support this resolution" }
                        controlClient.resize(selected.vsockPort, size)
                    }
                    if (generation == displayGeneration) {
                        result.onFailure {
                            if (VmRuntime.state.value.display.requested == size) VmRuntime.displayFailure(it.rootMessage())
                        }
                        if (result.isSuccess) {
                            VmRuntime.confirmDisplayMode(size)
                            resizeTimeout?.cancel()
                            resizeTimeout = scope.launch {
                                delay(12_000)
                                displayMutex.withLock {
                                    val display = VmRuntime.state.value.display
                                    if (generation == displayGeneration && display.requested == size && display.presented != size.size) {
                                        if (previous != null) runCatching { controlClient.resize(selected.vsockPort,previous) }
                                        if (generation == displayGeneration && VmRuntime.state.value.display.requested == size)
                                            VmRuntime.displayFailure("新分辨率画面未提交，已尝试恢复上一分辨率")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
                if (uri == null) fail("No VM bundle was selected.") else launchLifecycle { importBundle(uri) }
            }
            ACTION_IMPORT_PREPARED -> launchLifecycle {
                val file=getExternalFilesDir(null)?.resolve(PREPARED_BUNDLE_NAME)
                if (file?.isFile == true) importBundle(Uri.fromFile(file))
                else fail("Prepared Manjaro bundle is missing.")
            }
            ACTION_START -> launchLifecycle { startVm() }
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_VPN_MODE") -> {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                    !VmRuntime.state.value.isActive) pro.darc.viruaisor.network.VmNetworkRuntime.setEnabled(this, intent.getBooleanExtra("enabled", true))
                finishForegroundIfIdle()
            }
            ACTION_STOP -> launchLifecycle { stopVm() }
            ACTION_ROOTFS_RESIZE -> launchLifecycle { resizeRootfs(intent.getLongExtra("bytes", 0)) }
            ACTION_ROOTFS_RESTORE -> launchLifecycle { restoreRootfs() }
            ACTION_SURFACE_READY -> scope.launch {
                desktopRequested = true
                startDesktopIfReady()
            }
            ACTION_SURFACE_GONE -> scope.launch {
                desktopRequested = false
                // Detaching Android's window must preserve the user's Linux session.
                // Explicit VM Stop still stops the desktop before guest shutdown.
            }
            ACTION_RESIZE -> {
                val size = runCatching { DisplaySize(intent.getIntExtra("width",0),intent.getIntExtra("height",0)) }.getOrNull()
                val rate = intent.getIntExtra("millihz",0)
                val modes = VmRuntime.state.value.display.modes.filter { it.size == size && (rate == 0 || it.refreshMilliHz == rate) }
                val mode = modes.maxByOrNull { it.refreshMilliHz }
                if (mode != null && desktopStartGate.started) {
                    resizeTimeout?.cancel()
                    VmRuntime.requestResize(mode)
                    resizeRequests.trySend(displayGeneration to mode)
                }
            }
            ACTION_DEBUG_CONSOLE -> scope.launch {
                runCatching { engine.sendConsoleInput(intent.getStringExtra(EXTRA_TEXT).orEmpty()) }
                    .onFailure { fail("Console input failed: ${it.rootMessage()}") }
            }
            (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_GUEST_CHECK") -> {
                val command = intent.getStringExtra("command")
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                    command in setOf("FONT_STATUS", "NETWORK_STATUS", "NETWORK_BENCH", "CHROMIUM_RENDER")) scope.launch {
                    val selected = bundle ?: return@launch
                    val result = runCatching { controlClient.guestCheck(selected.vsockPort, requireNotNull(command)) }
                    java.io.File(filesDir, "guest-check-$command.json").writeText(result.getOrElse {
                        org.json.JSONObject().put("ok", false).put("error", it.rootMessage()).toString()
                    })
                    Log.i(TAG, "GUEST_CHECK_COMPLETE command=$command success=${result.isSuccess}")
                }
            }
            else -> finishForegroundIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        GuestPasswordRuntime.unbind(this)
        Log.d(TAG, "onDestroy service=${System.identityHashCode(this)} status=${VmRuntime.state.value.status}")
        readinessJob?.cancel()
        displayMonitor?.cancel()
        resizeTimeout?.cancel()
        storageReadyJob?.cancel()
        rootfsBootTimeout?.cancel()
        displayGeneration++
        InputBackendRegistry.unbind(engine)
        engine.clearCallback()
        val displayLease = venusLease
        val networkLease = vpnLease
        venusLease=null; vpnLease=null
        if (displayLease != null || networkLease != null || runtimeWakeLock != null) {
            Thread({
                engine.requestHardStop()
                runCatching { engine.closeStopped() }
                try { displayLease?.close() } finally {
                    runCatching { networkLease?.close() }
                    releaseRuntimeWakeLock()
                }
            },"venus-session-dispose").start()
        }
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
        val existingBundle = bundle ?: VmRuntime.state.value.bundle
        if (existingBundle != null && storageManager.pending(existingBundle)) {
            fail("请先启动验证或恢复根盘，再替换 VM bundle。")
            return
        }

        VmRuntime.setImporting()
        updateNotification("Importing VM bundle")
        // A foreground service alone does not keep the CPU awake with the screen locked.
        // Hold only a bounded, application-owned partial lock during disk extraction.
        val wakeLock=getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,"$packageName:bundle-import",
        )
        wakeLock.acquire(IMPORT_WAKE_TIMEOUT_MS)
        Log.i(TAG,"IMPORT_WAKE_ACQUIRED timeoutMs=$IMPORT_WAKE_TIMEOUT_MS")
        try {
            withContext(Dispatchers.IO) {
                engine.deleteDefinition()
                VmBundleStore(this@VmService).import(uri)
            }.also {
                bundle = it
                VmRuntime.setBundle(it)
                VmRuntime.setStorage(storageManager.state(it, "VM bundle imported."))
            }
        } catch (error: Throwable) {
            fail("Import failed: ${error.rootMessage()}")
            return
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG,"IMPORT_WAKE_RELEASED")
        }
    }

    private fun launchLifecycle(command: suspend () -> Unit) {
        pendingLifecycleCommands.begin()
        scope.launch {
            try {
                lifecycleMutex.withLock { command() }
            } finally {
                pendingLifecycleCommands.complete()
                finishForegroundIfIdle()
            }
        }
    }

    private suspend fun startVm() {
        Log.d(TAG, "startVm service=${System.identityHashCode(this)} engine=${System.identityHashCode(engine)}")
        VmRuntime.initialize(this)
        val state = VmRuntime.state.value
        if (state.support !is AvfSupport.Available) {
            fail("AVF is unavailable on this installation.")
            return
        }
        val importedBundle = state.bundle ?: VmBundleStore(this).currentBundle()
        if (importedBundle == null) {
            fail("Import a VM bundle before starting.")
            return
        }
        if (state.isActive) return
        if (java.io.File(importedBundle.directory, ".guest-maintenance").exists()) {
            fail("Guest 镜像维护未完成；请完成更新或从保留副本恢复。")
            return
        }

        try {
            engine.requireStopped()
            if (storageManager.pending(importedBundle)) {
                quiesceStorage()
                if (storageManager.awaitingBoot(importedBundle)) {
                    pendingRootfsBoot=true
                    VmRuntime.setStorage(storageManager.state(importedBundle, "正在启动验证已完成调整的根盘。"))
                } else {
                    storageManager.recover(importedBundle)
                    pendingRootfsBoot=false
                    VmRuntime.setStorage(storageManager.state(importedBundle, "已恢复上一次未完成的根盘操作。"))
                }
            } else pendingRootfsBoot=false
        } catch (error: Throwable) {
            fail("根盘恢复被阻止：${error.rootMessage()}")
            return
        }

        val selectedBundle = nativeDisplayBundle(importedBundle)
        VmRuntime.prepareDisplayConfig(selectedBundle.display)

        bundle = selectedBundle
        displayGeneration++
        displayMonitor?.cancel()
        VmRuntime.resetDisplay()
        storageChecked = false
        storageReadyJob?.cancel()
        desktopStartGate.reset()
        VmRuntime.setStatus(VmStatus.Starting, "VM starting")
        updateNotification("VM starting")
        try {
            // Keep VM CPU work awake for this session without waking the display.
            // Vendor freezing can still suspend it; no power policy is changed.
            runtimeWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:vm-runtime").apply { acquire(24 * 60 * 60 * 1000L) }
            if (selectedBundle.network && pro.darc.viruaisor.network.VmNetworkRuntime.state.value.vpn) {
                val generation = displayGeneration
                val network = pro.darc.viruaisor.network.VmVpnLease(this, scope) { error ->
                    scope.launch { lifecycleMutex.withLock {
                        if (generation != displayGeneration) return@withLock
                        engine.requestHardStop()
                        runCatching { engine.closeStopped() }
                        closeVenusLease()
                        closeVpnLease()
                        fail("VPN sharing failed: ${error.rootMessage()}")
                    } }
                }
                vpnLease = network
                network.open()
            }
            if (selectedBundle.gpuProfile == VmGpuProfile.VENUS) {
                val leaseGeneration = displayGeneration
                val lease=VenusDesktopLease(this) { error ->
                    scope.launch {
                        lifecycleMutex.withLock {
                            if (leaseGeneration != displayGeneration) return@withLock
                            engine.requestHardStop()
                            runCatching { engine.closeStopped() }
                            closeVenusLease()
                            closeVpnLease()
                            if (pendingRootfsBoot) rollbackBoot("Venus runtime lost: ${error.rootMessage()}")
                            else fail("Venus runtime lost: ${error.rootMessage()}")
                        }
                    }
                }
                venusLease=lease
                lease.open()
            }
            check(engine.start(selectedBundle)) { "AVF startup failed" }
            venusLease?.verifyExecution()
            if (pendingRootfsBoot) {
                val generation = displayGeneration
                rootfsBootTimeout?.cancel()
                rootfsBootTimeout = scope.launch {
                    delay(180_000)
                    lifecycleMutex.withLock {
                        if (generation == displayGeneration && pendingRootfsBoot) {
                            rollbackBoot("新根盘启动验证超时，已尝试恢复原盘。")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "VM startup failed before cleanup", error)
            engine.requestHardStop()
            runCatching { engine.closeStopped() }
            closeVenusLease()
            closeVpnLease()
            fail("VM startup failed: ${error.rootMessage()}")
            if (pendingRootfsBoot) rollbackBoot("新根盘启动失败，已尝试恢复原盘。")
        }
    }

    private suspend fun quiesceStorage() {
        withTimeout(15_000) {
            while (runCatching { engine.requireStopped() }.isFailure) delay(100)
        }
        engine.requireStopped()
        engine.closeStopped()
        closeVenusLease()
        closeVpnLease()
        withTimeout(8_000) {
            while (VmSharedStorage.backends(this@VmService).isNotEmpty()) delay(100)
        }
        engine.requireStopped()
        if (VmRuntime.state.value.isActive) VmRuntime.setStatus(VmStatus.Stopped,"AVF 已确认 VM 停止。")
    }

    private suspend fun resizeRootfs(target: Long) {
        VmRuntime.initialize(this)
        val selected = VmRuntime.state.value.bundle ?: return fail("请先导入 VM bundle。")
        if (VmRuntime.state.value.storage.busy || VmRuntime.state.value.status is VmStatus.Importing) return
        try { storageManager.preflight(selected, target) }
        catch (error: Throwable) { VmRuntime.setStorage(storageManager.state(selected,error.rootMessage())); return }
        val resume = VmRuntime.state.value.isActive
        storageOperation = true
        VmRuntime.setStorage(storageManager.state(selected, "正在停止 VM 并准备根盘副本…").copy(busy=true))
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:rootfs-resize")
        wakeLock.acquire(60 * 60 * 1000L)
        try {
            if (resume) stopVm()
            quiesceStorage()
            updateNotification("正在检查并调整根盘副本")
            storageManager.resize(selected, target)
            pendingRootfsBoot = true
            VmRuntime.setStorage(storageManager.state(selected, "根盘容量已调整；下次启动确认后清理恢复盘。"))
            if (resume) startVm()
        } catch (error: Throwable) {
            Log.e("ViruaisorStorage","ROOTFS_RESIZE_FAILED",error)
            val recovered = runCatching { quiesceStorage(); storageManager.recover(selected) }
            pendingRootfsBoot = false
            val message = "根盘调整失败：${error.rootMessage()}" +
                (recovered.exceptionOrNull()?.let { "；恢复未完成：${it.rootMessage()}" } ?: "；原盘已保留/恢复。")
            VmRuntime.setStorage(storageManager.state(selected,message))
            if (resume && recovered.isSuccess) startVm()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            storageOperation = false
            VmRuntime.setStorage(VmRuntime.state.value.storage.copy(busy=false))
        }
    }

    private suspend fun restoreRootfs() {
        VmRuntime.initialize(this)
        val selected = VmRuntime.state.value.bundle ?: return
        if (VmRuntime.state.value.isActive || VmRuntime.state.value.storage.busy) return
        storageOperation = true
        try {
            VmRuntime.setStorage(storageManager.state(selected,"正在恢复原根盘…").copy(busy=true))
            quiesceStorage()
            storageManager.recover(selected)
            pendingRootfsBoot = false
            VmRuntime.setStorage(storageManager.state(selected,"原根盘已恢复。"))
        } catch (error: Throwable) {
            VmRuntime.setStorage(storageManager.state(selected,"恢复被阻止：${error.rootMessage()}"))
        } finally { storageOperation=false; finishForegroundIfIdle() }
    }

    private suspend fun rollbackBoot(message: String) {
        val selected = bundle ?: return
        rootfsBootTimeout = null
        storageReadyJob?.cancel()
        storageOperation = true
        try {
            if (VmRuntime.state.value.isActive) stopVm()
            quiesceStorage()
            storageManager.recover(selected)
            pendingRootfsBoot = false
            VmRuntime.setStorage(storageManager.state(selected,message))
            VmRuntime.setStatus(VmStatus.Stopped,message)
            // Leave the recovered original stopped: no automatic retry loop on a broken guest.
        } catch (error: Throwable) {
            VmRuntime.setStorage(storageManager.state(selected,"$message 恢复未完成：${error.rootMessage()}"))
            fail("根盘恢复需要处理，已保留恢复文件。")
        } finally { storageOperation=false; finishForegroundIfIdle() }
    }

    private fun verifyGuestStorage() {
        if (storageReadyJob?.isActive == true) return
        val selected = bundle ?: return
        val generation = displayGeneration
        storageReadyJob = scope.launch {
            var desktopAllowed = false
            lifecycleMutex.withLock {
                if (generation != displayGeneration || VmRuntime.state.value.status !is VmStatus.GuestAgentReady) return@withLock
                val result = runCatching { controlClient.storageStatus(selected.vsockPort) }
                if (generation != displayGeneration || VmRuntime.state.value.status !is VmStatus.GuestAgentReady) return@withLock
                if (pendingRootfsBoot) {
                    val verified = result.mapCatching { storageManager.confirmBoot(selected,it.rootBytes) }
                    if (verified.isFailure) {
                        // A separate coroutine owns rollback so cancellation cannot cancel this lock holder.
                        scope.launch { lifecycleMutex.withLock {
                            if (generation == displayGeneration && pendingRootfsBoot)
                                rollbackBoot("根盘验证失败：${verified.exceptionOrNull()?.rootMessage()}")
                        } }
                        return@withLock
                    }
                    pendingRootfsBoot=false
                    rootfsBootTimeout?.cancel()
                    rootfsBootTimeout=null
                }
                storageChecked=true
                VmRuntime.setStorage(storageManager.state(selected,
                    result.fold({ if (it.mounted) "共享目录已挂载：/mnt/shared" else "共享目录未挂载，请检查 guest virtio-fs 驱动。" },
                        { "共享状态未确认：${it.rootMessage()}" })).copy(sharedMounted=result.getOrNull()?.mounted))
                desktopAllowed=true
            }
            if (desktopAllowed && generation == displayGeneration) startDesktopIfReady()
        }
    }

    private fun nativeDisplayBundle(selected: VmBundle): VmBundle {
        if (selected.gpuProfile != VmGpuProfile.VENUS) return selected
        val mode = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode ?: return selected
        val width=maxOf(mode.physicalWidth,mode.physicalHeight)
        val height=minOf(mode.physicalWidth,mode.physicalHeight)
        if (runCatching { DisplaySize(width,height) }.isFailure) return selected
        return selected.copy(display=selected.display.copy(width=width,height=height,refreshRate=60))
    }

    private suspend fun stopVm() {
        val selectedBundle = bundle ?: VmRuntime.state.value.bundle
        if (!VmRuntime.state.value.isActive || VmRuntime.state.value.status is VmStatus.Stopping) return

        readinessJob?.cancel()
        displayGeneration++
        displayMonitor?.cancel()
        resizeTimeout?.cancel()
        VmRuntime.setStatus(VmStatus.Stopping, "Requesting guest shutdown over vsock")
        updateNotification("VM stopping")
        val desktopStopCompleted = runCatching {
            VmStopSequence.stopDesktopIfStarted(desktopStartGate.started) {
                requireNotNull(selectedBundle) { "The active bundle metadata is unavailable." }
                controlClient.stopDesktop(selectedBundle.vsockPort, selectedBundle.gpuProfile)
            }
        }.onFailure { error ->
            Log.w(TAG, "desktop stop before shutdown failed", error)
        }.getOrDefault(false)
        if (desktopStopCompleted) desktopStartGate.reset()
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
                    verifyGuestStorage()
                }
            }
            is VmEngineEvent.Error -> {
                val errorGeneration = displayGeneration
                if (venusLease == null && !pendingRootfsBoot) fail("VM error: ${event.message}") else scope.launch {
                    lifecycleMutex.withLock {
                        if (errorGeneration != displayGeneration) return@withLock
                        engine.requestHardStop()
                        runCatching { engine.closeStopped() }
                        closeVenusLease()
                        closeVpnLease()
                        if (pendingRootfsBoot) rollbackBoot("VM error: ${event.message}")
                        else fail("VM error: ${event.message}")
                    }
                }
            }
            is VmEngineEvent.Stopped -> {
                readinessJob?.cancel()
                displayGeneration++
                displayMonitor?.cancel()
                if (event.expected) {
                    VmRuntime.setStatus(VmStatus.Stopped, "VM stopped")
                } else {
                    val reason = stopReason(event.reason)
                    VmRuntime.setStatus(VmStatus.Crashed(reason), "VM crashed: $reason")
                }
                val stoppedGeneration = displayGeneration
                val stoppedLease = venusLease
                val unverifiedAtStop = pendingRootfsBoot
                scope.launch { lifecycleMutex.withLock {
                    if (stoppedGeneration != displayGeneration) return@withLock
                    engine.closeStopped()
                    if (venusLease === stoppedLease) closeVenusLease()
                    closeVpnLease()
                    if (unverifiedAtStop && pendingRootfsBoot && !storageOperation)
                        rollbackBoot("新根盘在完成验证前停止，已尝试恢复原盘。")
                    finishForegroundIfIdle()
                } }
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
                    verifyGuestStorage()
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
        if (!desktopRequested || !storageChecked) return
        if (VmRuntime.state.value.status !is VmStatus.GuestAgentReady) return
        val selectedBundle = bundle ?: return
        if (!desktopStartGate.tryBegin()) return
        runCatching { controlClient.startDesktop(selectedBundle.vsockPort, selectedBundle.gpuProfile) }
            .onSuccess {
                desktopStartGate.succeeded()
                VmRuntime.setStatus(VmStatus.GuestAgentReady, "Guest agent ready; desktop requested")
                beginDisplayMonitor(selectedBundle)
            }
            .onFailure { error ->
                desktopStartGate.failed()
                VmRuntime.setStatus(
                    VmStatus.GuestAgentReady,
                    "Guest agent ready; desktop start failed: ${error.rootMessage()}",
                )
            }
    }

    private fun beginDisplayMonitor(selected: VmBundle) {
        if (selected.gpuProfile != VmGpuProfile.VENUS) return
        displayMonitor?.cancel()
        val generation = displayGeneration
        var chooseDefault = true
        displayMonitor = scope.launch {
            while (generation == displayGeneration && desktopStartGate.started) {
                displayMutex.withLock {
                    runCatching { controlClient.displayModes(selected.vsockPort) }.onSuccess {
                        if (generation == displayGeneration) {
                            val pending=VmRuntime.state.value.display.requested
                            VmRuntime.setDisplayModes(it)
                            if (chooseDefault) {
                                chooseDefault=false
                                val preferred=DisplayModePolicy.preferred(
                                    DisplaySize(selected.display.width,selected.display.height),it.available)
                                if (pending == null && preferred != null && preferred != it.current) {
                                    VmRuntime.requestResize(preferred)
                                    resizeRequests.trySend(generation to preferred)
                                }
                            }
                        }
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun closeVenusLease() {
        val lease=venusLease ?: return
        venusLease=null
        lease.close()
    }

    private fun closeVpnLease() {
        val lease = vpnLease
        vpnLease = null
        try { runCatching { lease?.close() }.onFailure { Log.e(TAG, "VPN cleanup incomplete; scoped guard retained", it) } }
        finally { releaseRuntimeWakeLock() }
    }

    @Synchronized private fun releaseRuntimeWakeLock() {
        runtimeWakeLock?.let { if (it.isHeld) it.release() }
        runtimeWakeLock = null
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
        if (storageOperation) return
        if (pendingLifecycleCommands.hasPending()) return
        if (venusLease != null) return // Teardown must finish before the owning service is destroyed.
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
        private const val ACTION_IMPORT = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "IMPORT_VM")
        private const val ACTION_IMPORT_PREPARED = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "IMPORT_PREPARED_MANJARO")
        const val PREPARED_BUNDLE_NAME = "manjaro-plasma-venus.zip"
        private const val ACTION_START = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "START_VM")
        private const val ACTION_STOP = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "STOP_VM")
        private const val ACTION_SURFACE_READY = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "SURFACE_READY")
        private const val ACTION_SURFACE_GONE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "SURFACE_GONE")
        private const val ACTION_DEBUG_CONSOLE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "DEBUG_CONSOLE")
        private const val ACTION_RESIZE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "RESIZE")
        private const val ACTION_ROOTFS_RESIZE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "RESIZE_ROOTFS")
        private const val ACTION_ROOTFS_RESTORE = (pro.darc.viruaisor.AppIdentity.ACTION_PREFIX + "RESTORE_ROOTFS")
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "vm_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val READY_ATTEMPTS = 60
        private const val READY_RETRY_MS = 1_000L
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000L
        private const val IMPORT_WAKE_TIMEOUT_MS = 10 * 60 * 1_000L

        fun importIntent(context: Context, uri: Uri) =
            Intent(context, VmService::class.java).setAction(ACTION_IMPORT).setData(uri)

        fun importPreparedIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_IMPORT_PREPARED)

        fun startIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_STOP)

        fun resizeRootfsIntent(context: Context, bytes: Long) =
            Intent(context, VmService::class.java).setAction(ACTION_ROOTFS_RESIZE).putExtra("bytes", bytes)

        fun restoreRootfsIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_ROOTFS_RESTORE)

        fun surfaceReadyIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_SURFACE_READY)

        fun surfaceGoneIntent(context: Context) =
            Intent(context, VmService::class.java).setAction(ACTION_SURFACE_GONE)

        fun resizeIntent(context: Context, size: DisplaySize) =
            Intent(context, VmService::class.java).setAction(ACTION_RESIZE)
                .putExtra("width",size.width).putExtra("height",size.height)

        fun resizeIntent(context: Context, mode: DisplayMode) =
            resizeIntent(context,mode.size).putExtra("millihz",mode.refreshMilliHz)

        fun debugConsoleIntent(context: Context, text: String) =
            Intent(context, VmService::class.java)
                .setAction(ACTION_DEBUG_CONSOLE)
                .putExtra(EXTRA_TEXT, text)
    }
}

internal object VmStopSequence {
    fun stopDesktopIfStarted(desktopStarted: Boolean, stopDesktop: () -> Unit): Boolean {
        if (!desktopStarted) return false
        stopDesktop()
        return true
    }
}

internal class DesktopStartGate {
    private enum class State { IDLE, STARTING, STARTED }

    private var state = State.IDLE

    @get:Synchronized
    val started: Boolean
        get() = state == State.STARTED

    @Synchronized
    fun tryBegin(): Boolean {
        if (state != State.IDLE) return false
        state = State.STARTING
        return true
    }

    @Synchronized
    fun succeeded() {
        check(state == State.STARTING) { "Desktop start was not in progress." }
        state = State.STARTED
    }

    @Synchronized
    fun failed() {
        if (state == State.STARTING) state = State.IDLE
    }

    @Synchronized
    fun reset() {
        state = State.IDLE
    }
}
