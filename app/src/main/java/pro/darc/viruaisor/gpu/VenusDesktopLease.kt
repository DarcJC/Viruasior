package pro.darc.viruaisor.gpu

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.util.Log
import pro.darc.viruaisor.root.RootOverlayAuthorization
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One application-owned VM session. No caller-selected executables, paths or mount targets. */
internal class VenusDesktopLease(context: Context, private val lost: (Throwable) -> Unit) : AutoCloseable {
    private val app=context.applicationContext
    private val root=OverlayRootConnection(app)
    private val rpc=Executors.newSingleThreadExecutor()
    private val heartbeat=Executors.newSingleThreadScheduledExecutor()
    private val token=UUID.randomUUID().toString().replace("-","")
    private val lab=File(app.filesDir,"venus-overlay-lab")
    private val control=File(lab,"$token.control")
    private val ready=CompletableFuture<String>()
    private val executed=CompletableFuture<String>()
    private var result: CompletableFuture<String>?=null
    @Volatile private var closing=false
    @Volatile var active=false
        private set

    fun open() {
        check(Process.myUid() == app.applicationInfo.uid)
        check(File("/proc/self/cmdline").readText().substringBefore('\u0000') == "pro.darc.viruaisor:venus_vm")
        check(lab.mkdirs() || lab.isDirectory)
        File(lab,"$token.target").writeText("ORIGINAL:$token\n")
        control.writeText("0")
        val callback=object : Binder() {
            override fun onTransact(code: Int,data: Parcel,reply: Parcel?,flags: Int): Boolean {
                check(getCallingUid() == 0 && code in 1..2)
                data.enforceInterface(RootOverlayAuthorization.DESCRIPTOR)
                val status=requireNotNull(data.readString()).take(2048)
                data.enforceNoDataAvail()
                if (code == 1) ready.complete(status) else executed.complete(status)
                return true
            }
        }
        root.bind()
        val pending=CompletableFuture.supplyAsync({ root.probe(token,"venus-desktop",callback) },rpc)
        result=pending
        CompletableFuture.anyOf(ready,pending).get(18,TimeUnit.SECONDS)
        check(ready.isDone) { "Desktop runtime refused: ${pending.get(1,TimeUnit.SECONDS)}" }
        val status=ready.get()
        check(status.contains("kind=venus-candidate-byte-identical") && status.contains("root_stock_original=true") &&
            status.contains("root_outside=ORIGINAL:$token")) { "Runtime isolation checks failed" }
        active=true
        Log.i(TAG,"DESKTOP_LEASE_READY token=$token uid=${Process.myUid()} pid=${Process.myPid()} $status")
        heartbeat.scheduleAtFixedRate({
            if (!closing) runCatching {
                check(!pending.isDone) { "Desktop lease ended: ${pending.get()}" }
                check(OverlayLeaseControl.heartbeat(control))
            }.onFailure {
                active=false
                if (!closing) { closing=true; lost(it) }
            }
        },0,2,TimeUnit.SECONDS)
    }

    fun verifyExecution() {
        check(active)
        val status=executed.get(8,TimeUnit.SECONDS)
        check(status.contains("name=crosvm_default") && status.contains("caps=0"))
        Log.i(TAG,"DESKTOP_EXECUTION_VERIFIED token=$token $status")
    }

    override fun close() {
        closing=true; active=false
        heartbeat.shutdownNow()
        try {
            if (control.isFile) OverlayLeaseControl.release(control)
            val outcome=result?.get(12,TimeUnit.SECONDS)
            if (outcome != null) {
                Log.i(TAG,"DESKTOP_LEASE_END token=$token $outcome")
                check(!ready.isDone || (outcome.startsWith("native_exit=0\n") && outcome.contains("ROLLBACK=PASS"))) {
                    "Desktop runtime cleanup is unverified"
                }
            }
            listOf("target","control","snapshot").forEach { suffix ->
                val file=File(lab,"$token.$suffix")
                check(!file.exists() || file.delete()) { "Retained desktop lease artifact: ${file.name}" }
            }
        } catch (error: Throwable) {
            // Destroy only this disposable application namespace, never detach an unknown mount.
            Log.e(TAG,"DESKTOP_LEASE_CLEANUP_FAILED; disposing application process",error)
            Process.killProcess(Process.myPid())
        } finally { root.close(); rpc.shutdownNow() }
    }
    companion object { private const val TAG="ViruaisorVenusDesktop" }
}
