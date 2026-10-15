package pro.darc.viruaisor.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException

/** Fixed diskless stock/Venus gates. No root Binder VM operations or user disk mounts. */
internal object StockAvfOverlayProbe {
    fun run(context: Context, baseline: Boolean = false, venus: Boolean = false, skipCacheSync: Boolean = false,
            token: String = java.util.UUID.randomUUID().toString().replace("-", ""), whileRunning: () -> Unit = {}): String {
        require(!skipCacheSync || venus) { "Cache negative control requires the Venus fixture" }
        val manager = requireNotNull(context.getSystemService("virtualization"))
        val existing = call(manager, "get", "default")
        check(existing == null || call(existing, "getStatus") != 1) { "Normal VM is active" }
        check(call(manager, "get", NAME) == null) { "Previous stock probe definition exists; not overwriting it" }
        val session = File(context.filesDir, "venus-stock-owned.session")
        check(!session.exists()) { "Previous probe session needs ordinary-app recovery" }
        val kernel = File(context.filesDir, "vms/default/vmlinuz")
        check(kernel.isFile) { "Imported kernel missing" }
        val image = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder").getConstructor().newInstance()
        call(image, "setName", NAME)
        call(image, "setOsName", "debian")
        call(image, "setKernelPath", kernel.path)
        StockProbeConfig.kernelParams.forEach { call(image, "addParam", it) }
        if (venus) {
            // AAPT expands .gz assets and removes the suffix (verified in the Release APK).
            val initrd = File(context.filesDir, "venus-guest-probe.cpio")
            context.assets.open("venus/guest-probe.cpio").use { input -> initrd.outputStream().use { input.copyTo(it) } }
            check(initrd.length() in 1..8L*1024*1024) { "Unexpected guest fixture size" }
            call(image, "setInitrdPath", initrd.path)
            call(image, "addParam", "rdinit=/init")
            call(image, "addParam", "drm_kms_helper.fbdev_emulation=0")
            if (skipCacheSync) call(image, "addParam", "venus_probe.skip_cache_sync=1")
            val gpu = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$GpuConfig\$Builder").getConstructor().newInstance()
            call(gpu, "setBackend", "virglrenderer")
            call(gpu, "setContextTypes", arrayOf("venus"))
            call(gpu, "setRendererUseEgl", false)
            call(gpu, "setRendererUseGles", false)
            call(gpu, "setRendererUseSurfaceless", true)
            call(gpu, "setRendererUseVulkan", true)
            call(image, "setGpuConfig", requireNotNull(call(gpu, "build")))
        }
        // Never user disks/user initrd, display, network, or shared directories.
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val config = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        call(config, "setCustomImageConfig", requireNotNull(call(image, "build")))
        call(config, "setProtectedVm", false)
        call(config, "setMemoryBytes", 256L * 1024 * 1024)
        call(config, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        call(config, "setVmOutputCaptured", true)
        var vm: Any? = null
        val streams = mutableListOf<InputStream>()
        val readers = mutableListOf<Thread>()
        val kind = if (baseline) "baseline" else "overlay"
        check(StockProbeConfig.recoveryToken("stock-probe-v1:$token\n") == token)
        session.writeText("stock-probe-v1:$token\n")
        try {
            vm = requireNotNull(call(manager, "create", NAME, requireNotNull(call(config, "build"))))
            for ((method, suffix) in listOf("getConsoleOutput" to "console", "getLogOutput" to "log")) {
                val stream = call(vm, method) as InputStream
                streams.add(stream)
                val file = File(context.filesDir, "venus-stock-$kind.$suffix")
                ProbeOutputDrain.reset(file) // Remove prior results before the reader can be scheduled.
                readers.add(Thread({
                    try {
                        file.outputStream().use { output ->
                            val total = ProbeOutputDrain.copy(stream, output, 256 * 1024)
                            Log.i(TAG, "STOCK_OUTPUT kind=$kind stream=$suffix total=$total retained=${file.length()}")
                        }
                    } catch (error: Exception) {
                        Log.i(TAG, "STOCK_OUTPUT_CLOSED kind=$kind stream=$suffix retained=${file.length()} error=${error.javaClass.simpleName}")
                    }
                }, "stock-$suffix-reader").apply { isDaemon = true; start() })
            }
            Log.i(TAG, "STOCK_AVF_BEGIN kind=$kind uid=${Process.myUid()} pid=${Process.myPid()} disks=none gpu=${if (venus) "venus" else "none"}")
            call(vm, "run")
            if (venus) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 12000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val console = File(context.filesDir, "venus-stock-$kind.console")
                    if (console.exists() && console.readText().contains("VENUS_GUEST_RESULT=")) break
                    Thread.sleep(100)
                }
                // DestroyInstance is asynchronous in Venus. A console PASS must
                // not conceal a renderer-thread crash during context teardown.
                Thread.sleep(500)
            } else Thread.sleep(2000)
            val status = call(vm, "getStatus")
            Log.i(TAG, "STOCK_AVF_RUN_RETURNED status=$status uid=${Process.myUid()}; executable provenance must be verified separately")
            check(status == 1) { "Stock VM did not remain running: status=$status" }
            val console = File(context.filesDir, "venus-stock-$kind.console").readText()
            if (venus) check(if (skipCacheSync) VenusGuestReport.negativeRejected(console) else VenusGuestReport.passed(console)) {
                if (skipCacheSync) "Cache negative control did not demonstrate stale readback; inspect console"
                else "Guest Venus computation did not pass; inspect captured console"
            }
            else check(console.contains("Kernel panic - not syncing: VFS: Unable to mount root fs")) { "Expected diskless kernel console marker missing" }
            whileRunning()
            return if (skipCacheSync) "VENUS_GUEST_CACHE_NEGATIVE_VERIFIED=true uid=${Process.myUid()} disks=none gpuFenceCompleted=true staleReadback=true"
            else if (venus) "VENUS_GUEST_COMPUTE_VERIFIED=true uid=${Process.myUid()} disks=none passes=3 wordsPerPass=256"
            else "STOCK_AVF_RUN_RETURNED=true status=$status uid=${Process.myUid()} disks=none gpu=none fixture=expected-halted-rootfs-panic; no userspace boot or guest GPU claim"
        } finally {
            if (vm != null) {
                try {
                    if (call(vm, "getStatus") == 1) call(vm, "stop")
                } finally {
                    call(manager, "delete", NAME)
                    check(session.delete()) { "Cannot remove completed probe ownership marker" }
                    call(vm, "close")
                    streams.forEach { runCatching { it.close() } }
                    readers.forEach { it.join(500) }
                    Log.i(TAG, "STOCK_AVF_END definitionDeleted=true")
                }
            }
            if (vm == null) session.delete()
        }
    }
    /** Ordinary app only, and only a definition this harness recorded as its own. */
    fun recover(context: Context): String {
        val session = File(context.filesDir, "venus-stock-owned.session")
        if (!session.exists()) return "STOCK_RECOVERY noOwnedSession=true"
        val token = requireNotNull(StockProbeConfig.recoveryToken(session.readText())) { "Unknown probe ownership marker" }
        val lab = File(context.filesDir, "venus-overlay-lab")
        check(!File(lab, "$token.snapshot").exists()) { "Native lease has not released its snapshot" }
        val manager = requireNotNull(context.getSystemService("virtualization"))
        val vm = call(manager, "get", NAME)
        if (vm != null) {
            if (call(vm, "getStatus") == 1) call(vm, "stop")
            call(manager, "delete", NAME)
            call(vm, "close")
        }
        check(session.delete())
        listOf("target", "control", "outside").forEach { suffix ->
            val file = File(lab, "$token.$suffix")
            check(!file.exists() || file.delete()) { "Cannot delete owned probe artifact" }
        }
        return "STOCK_RECOVERY ownedDefinitionDeleted=true uid=${Process.myUid()}"
    }
    private fun call(receiver: Any, name: String, vararg args: Any): Any? {
        val method = receiver.javaClass.methods.single { method ->
            method.name == name && method.parameterTypes.size == args.size && method.parameterTypes.indices.all { i ->
                val type = method.parameterTypes[i]
                type.isInstance(args[i]) || type == Boolean::class.javaPrimitiveType && args[i] is Boolean ||
                    type == Int::class.javaPrimitiveType && args[i] is Int || type == Long::class.javaPrimitiveType && args[i] is Long
            }
        }
        return try { method.invoke(receiver, *args) } catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
    }
    private const val NAME = "viruaisor-stock-overlay-probe"
    private const val TAG = "ViruaisorStockProbe"
}
