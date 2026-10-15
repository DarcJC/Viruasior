package pro.darc.viruaisor.gpu

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Isolated AVF initialization only. Never attaches the user's disks or claims guest execution. */
object VenusAvfBackendProbe {
    fun run(context: Context): String {
        val manager = requireNotNull(context.getSystemService("virtualization"))
        val defaultVm = call(manager, "get", "default")
        check(defaultVm == null || call(defaultVm, "getStatus") != 1) { "请先停止当前 VM" }
        check(call(manager, "get", NAME) == null) { "上次探测定义仍存在；请先检查，未覆盖它" }
        val kernel = File(context.filesDir, "vms/default/vmlinuz")
        check(kernel.isFile) { "缺少已导入的 guest 内核" }
        val gpu = builder("VirtualMachineCustomImageConfig\$GpuConfig")
        call(gpu, "setBackend", "virglrenderer")
        call(gpu, "setContextTypes", arrayOf("venus"))
        call(gpu, "setRendererUseEgl", true)
        call(gpu, "setRendererUseGles", true)
        call(gpu, "setRendererUseSurfaceless", true)
        call(gpu, "setRendererUseVulkan", true)
        val custom = builder("VirtualMachineCustomImageConfig")
        call(custom, "setName", NAME)
        call(custom, "setOsName", "debian")
        call(custom, "setKernelPath", kernel.absolutePath)
        call(custom, "setGpuConfig", requireNotNull(call(gpu, "build")))
        call(custom, "addParam", "panic=-1")
        val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        val config = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
            .getConstructor(Context::class.java).newInstance(context)
        call(config, "setCustomImageConfig", requireNotNull(call(custom, "build")))
        call(config, "setProtectedVm", false)
        call(config, "setMemoryBytes", 256L * 1024 * 1024)
        call(config, "setDebugLevel", configClass.getField("DEBUG_LEVEL_FULL").getInt(null))
        var vm: Any? = null
        try {
            vm = requireNotNull(call(manager, "create", NAME, requireNotNull(call(config, "build"))))
            Log.i(TAG, "VENUS_PROBE_BEGIN backend=virglrenderer context=venus disks=none uid=${android.os.Process.myUid()}")
            return try {
                call(vm, "run")
                Thread.sleep(2_000)
                val status = call(vm, "getStatus")
                Log.i(TAG, "VENUS_RUN_RETURNED status=$status; inspect native renderer logs")
                "AVF 探测返回 status=$status；须核查 crosvm 日志，不代表 guest GPU 已通过"
            } catch (error: Exception) {
                Log.i(TAG, "VENUS_RUN_REJECTED ${error.javaClass.name}: ${error.message}")
                "AVF 后端未启动：${error.message}"
            }
        } finally {
            if (vm != null && call(vm, "getStatus") == 1) call(vm, "stop")
            call(manager, "delete", NAME)
            Log.i(TAG, "VENUS_PROBE_END definitionDeleted=true; no GPU execution claimed")
        }
    }

    private fun builder(type: String): Any =
        Class.forName("android.system.virtualmachine.$type\$Builder").getConstructor().newInstance()

    private fun call(receiver: Any, name: String, vararg args: Any): Any? {
        val method = receiver.javaClass.methods.single { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    val parameter = candidate.parameterTypes[index]
                    parameter.isInstance(args[index]) ||
                        parameter == Boolean::class.javaPrimitiveType && args[index] is Boolean ||
                        parameter == Int::class.javaPrimitiveType && args[index] is Int ||
                        parameter == Long::class.javaPrimitiveType && args[index] is Long
                }
        }
        return try { method.invoke(receiver, *args) } catch (error: InvocationTargetException) {
            throw requireNotNull(error.cause)
        }
    }

    private const val NAME = "viruaisor-venus-backend-probe"
    private const val TAG = "ViruaisorGpuProbe"
}
