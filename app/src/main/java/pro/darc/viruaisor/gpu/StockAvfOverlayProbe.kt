package pro.darc.viruaisor.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException

/** Fixed diskless/GPU-off execution gate. No root Binder VM operations or user disk mounts. */
internal object StockAvfOverlayProbe {
    fun run(context: Context, baseline: Boolean = false): String {
        val manager = requireNotNull(context.getSystemService("virtualization"))
        val existing = call(manager, "get", "default")
        check(existing == null || call(existing, "getStatus") != 1) { "Normal VM is active" }
        check(call(manager, "get", NAME) == null) { "Previous stock probe definition exists; not overwriting it" }
        val kernel = File(context.filesDir, "vms/default/vmlinuz")
        check(kernel.isFile) { "Imported kernel missing" }
        val image = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder").getConstructor().newInstance()
        call(image, "setName", NAME)
        call(image, "setOsName", "debian")
        call(image, "setKernelPath", kernel.path)
        StockProbeConfig.kernelParams.forEach { call(image, "addParam", it) }
        // Deliberately no GPU, disks, initrd, display, network, or shared directories.
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
        try {
            vm = requireNotNull(call(manager, "create", NAME, requireNotNull(call(config, "build"))))
            for ((method, suffix) in listOf("getConsoleOutput" to "console", "getLogOutput" to "log")) {
                val stream = call(vm, method) as InputStream
                streams.add(stream)
                val file = File(context.filesDir, "venus-stock-$kind.$suffix")
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
            Log.i(TAG, "STOCK_AVF_BEGIN kind=$kind uid=${Process.myUid()} pid=${Process.myPid()} disks=none gpu=none")
            call(vm, "run")
            Thread.sleep(2000)
            val status = call(vm, "getStatus")
            Log.i(TAG, "STOCK_AVF_RUN_RETURNED status=$status uid=${Process.myUid()}; executable provenance must be verified separately")
            check(status == 1) { "Stock VM did not remain running: status=$status" }
            val console = File(context.filesDir, "venus-stock-$kind.console").readText()
            check(console.contains("Kernel panic - not syncing: VFS: Unable to mount root fs")) { "Expected diskless kernel console marker missing" }
            return "STOCK_AVF_RUN_RETURNED=true status=$status uid=${Process.myUid()} disks=none gpu=none fixture=expected-halted-rootfs-panic; no userspace boot or guest GPU claim"
        } finally {
            if (vm != null) {
                try {
                    if (call(vm, "getStatus") == 1) call(vm, "stop")
                } finally {
                    call(manager, "delete", NAME)
                    call(vm, "close")
                    streams.forEach { runCatching { it.close() } }
                    readers.forEach { it.join(500) }
                    Log.i(TAG, "STOCK_AVF_END definitionDeleted=true")
                }
            }
        }
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
