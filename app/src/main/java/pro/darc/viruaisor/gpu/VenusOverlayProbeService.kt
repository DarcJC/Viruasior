package pro.darc.viruaisor.gpu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import pro.darc.viruaisor.root.RootOverlayAuthorization
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable same-UID namespace worker; AVF only after the pinned stock lease is ready. */
class VenusOverlayProbeService : Service() {
    private var started = false
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Temporary GPU overlay validation", NotificationManager.IMPORTANCE_LOW))
        startForeground(42, Notification.Builder(this, CHANNEL).setContentTitle("Viruaisor overlay isolation test")
            .setContentText("Temporary private namespace; original system files untouched")
            .setSmallIcon(android.R.drawable.stat_notify_sync).build())
        if (started) return START_NOT_STICKY
        started = true
        val mode = intent?.getStringExtra("mode") ?: "normal"
        val injectErrorAfterVm = intent?.getBooleanExtra("inject_error_after_vm", false) == true
        if (intent?.action != ACTION || mode != "stock-baseline" && !RootOverlayAuthorization.validRequest("0".repeat(32), mode) ||
            injectErrorAfterVm && mode != "stock-exec") {
            stopSelf(); return START_NOT_STICKY
        }
        // Independent of Binder/native cleanup: app exit destroys its disposable namespace.
        watchdog.schedule({
            Log.e(TAG, "APP_WATCHDOG_EXIT pid=${Process.myPid()}")
            Process.killProcess(Process.myPid())
        }, 45, TimeUnit.SECONDS)
        Thread({ if (mode == "stock-baseline") runBaseline() else runProbe(mode, injectErrorAfterVm) }, "venus-overlay-app-observer").start()
        return START_NOT_STICKY
    }
    private fun runBaseline() {
        try {
            Log.i(TAG, "STOCK_BASELINE_BEGIN uid=${Process.myUid()} pid=${Process.myPid()} overlay=false rootRpc=false")
            val capabilities = android.system.Os.getxattr("/apex/com.android.virt/bin/virtmgr", "security.capability")
            Log.i(TAG, "STOCK_VIRTMGR_FILECAP_RAW=${capabilities.joinToString("") { "%02x".format(it.toInt() and 255) }}")
            Log.i(TAG, StockAvfOverlayProbe.run(this, baseline = true))
        } catch (error: Throwable) {
            Log.i(TAG, "STOCK_BASELINE_END error=${error.javaClass.simpleName}: ${error.message}")
        } finally {
            DisposableProbeExit.finish({ stopForeground(STOP_FOREGROUND_REMOVE) }, { stopSelf() }, { Process.killProcess(Process.myPid()) })
        }
    }
    private fun runProbe(mode: String, injectErrorAfterVm: Boolean) {
        val stock = mode == "stock-exec"
        val token = UUID.randomUUID().toString().replace("-", "")
        val lab = File(filesDir, "venus-overlay-lab").apply { mkdirs() }
        val report = File(filesDir, "venus-overlay-last.log")
        val log = StringBuilder("mode=$mode token=$token app_uid=${Process.myUid()} app_pid=${Process.myPid()}\n")
        fun record(line: String) { log.appendLine(line); report.writeText(log.toString()); Log.i(TAG, line) }
        val target = File(lab, "$token.target")
        val control = File(lab, "$token.control")
        val outside = File(lab, "$token.outside")
        val root = OverlayRootConnection(applicationContext)
        val rpc = Executors.newSingleThreadExecutor()
        var nativeResult: CompletableFuture<String>? = null
        var outcome: String? = null
        var mountReady = false
        try {
            target.writeText("ORIGINAL:$token\n"); control.writeText("0")
            record("original_view=${target.readText().trim()}")
            val ready = CompletableFuture<String>()
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (getCallingUid() != 0 || code != 1) throw SecurityException("Root callback only")
                    data.enforceInterface(RootOverlayAuthorization.DESCRIPTOR)
                    val status = requireNotNull(data.readString()).take(2048)
                    data.enforceNoDataAvail()
                    ready.complete(status)
                    return true
                }
            }
            root.bind()
            record("ROOT_BOUND=true normal_app_uid=${Process.myUid()}")
            val result = CompletableFuture.supplyAsync({ root.probe(token, mode, callback) }, rpc)
            nativeResult = result
            // A refused gate completes the RPC without MOUNT_READY; never wait until the lease.
            CompletableFuture.anyOf(ready, result).get(18, TimeUnit.SECONDS)
            if (!ready.isDone) {
                record(result.get(1, TimeUnit.SECONDS)); error("Native safety gate refused before mount")
            }
            val status = ready.get()
            mountReady = true
            record(status)
            check(status.contains("root_outside=ORIGINAL:$token")) { "Root control view changed" }
            record("inside_view=${target.readText().trim()}")
            check(target.readText() == "${if (stock) "ORIGINAL" else "OVERLAY"}:$token\n") { "Unexpected ordinary-app sentinel view" }
            if (stock) check(status.contains("kind=stock-copy-byte-identical") && status.contains("root_stock_original=true"))
            sendBroadcast(Intent(this, VenusOverlayOutsideReceiver::class.java).putExtra("token", token))
            val outsideDeadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (!outside.isFile && android.os.SystemClock.elapsedRealtime() < outsideDeadline) Thread.sleep(25)
            val controlView = outside.readText()
            record("outside_app=$controlView")
            check(controlView.contains("uid=${Process.myUid()}") && controlView.endsWith("view=ORIGINAL:$token\n")) { "Other app process view changed" }
            if (mode == "client-death") {
                record("CLIENT_DEATH_INJECTED; native pidfd must revoke lease")
                Process.killProcess(Process.myPid()); return
            }
            if (stock) record(StockAvfOverlayProbe.run(this))
            // Fixed app-only fault in this unexported service. The root capability stays STOCK_EXEC.
            if (injectErrorAfterVm) {
                record("INJECTED_APP_ERROR_AFTER_VM=true; VM already stopped/deleted")
                error("Intentional stock probe app-side failure")
            }
            if (mode == "normal" || stock) control.writeText("1")
            val completed = result.get(12, TimeUnit.SECONDS)
            outcome = completed
            record(completed)
            if (mode == "helper-death") {
                check(completed.startsWith("native_exit=23")); record("HELPER_DEATH_OBSERVED; disposing app namespace")
            } else {
                check(completed.startsWith("native_exit=0") && completed.contains("ROLLBACK=PASS"))
                check(target.readText() == "ORIGINAL:$token\n")
                if (stock) check(completed.contains("STOCK_EXEC_OBSERVED") && completed.contains("VIRTMGR_OBSERVED")) { "AVF did not execute the actual covered inode" }
                record("OVERLAY_${if (stock) "STOCK_EXEC" else if (mode == "normal") "PROBE" else "TIMEOUT"}_VERIFIED=true")
            }
        } catch (error: Throwable) {
            record("OVERLAY_PROBE_VERIFIED=false error=${error.javaClass.simpleName}: ${error.message}")
        } finally {
            try {
                val cleaned = OverlayLeaseCleanup.finish(
                    release = { if (mountReady && (mode == "normal" || stock) && control.exists()) control.writeText("1") },
                    awaitRollback = {
                        val result = nativeResult
                        if (result == null) true else {
                            if (outcome == null) { outcome = result.get(12, TimeUnit.SECONDS); record("CLEANUP_NATIVE_RESULT=$outcome") }
                            !mountReady && !outcome.orEmpty().contains("MOUNT_READY") || outcome.orEmpty().contains("ROLLBACK=PASS") ||
                                mode == "helper-death" && outcome.orEmpty().startsWith("native_exit=23")
                        }
                    },
                    closeRoot = { root.close() },
                    deleteArtifacts = {
                        // Only this nonce's fixed artifacts, after native completion. Never guest/user files.
                        listOf(target, control, outside, File(lab, "$token.snapshot")).forEach { file ->
                            if (file.exists() && !file.delete()) record("SESSION_FILE_RETAINED=${file.name}")
                        }
                    },
                )
                if (!cleaned) record("CLEANUP_PENDING_NATIVE=true; retaining control until namespace disposal")
            } catch (cleanupError: Throwable) {
                record("CLEANUP_PENDING_NATIVE=true error=${cleanupError.javaClass.simpleName}; retaining control until namespace disposal")
            }
            rpc.shutdownNow()
            DisposableProbeExit.finish({ stopForeground(STOP_FOREGROUND_REMOVE) }, { stopSelf() }, { Process.killProcess(Process.myPid()) })
        }
    }
    companion object {
        const val ACTION = "pro.darc.viruaisor.action.OVERLAY_SENTINEL_PROBE"
        private const val CHANNEL = "venus-overlay-lab"
        private const val TAG = "ViruaisorOverlay"
    }
}
