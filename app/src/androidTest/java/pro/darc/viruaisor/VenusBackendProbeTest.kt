package pro.darc.viruaisor

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Device feasibility probe: initializes an isolated AVF GPU backend with no writable guest disks. */
class VenusBackendProbeTest {
    @Test fun initializeVenusBackend() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuBackendProbe") == "venus")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = requireNotNull(context.getSystemService("virtualization"))
        val defaultVm = call(manager, "get", "default")
        check(defaultVm == null || call(defaultVm, "getStatus") != 1) {
            "Stop the project's VM before running this probe"
        }
        check(call(manager, "get", NAME) == null) { "A previous probe definition exists" }
        val kernel = File(context.filesDir, "vms/default/vmlinuz")
        check(kernel.isFile) { "The imported test kernel is missing" }

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
        // No initrd/root disk: backend initialization is the only acceptance gate in this test.
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
            Log.i(TAG, "VENUS_PROBE_BEGIN backend=virglrenderer context=venus disks=none")
            try {
                call(vm, "run")
                Thread.sleep(2_000)
                Log.i(TAG, "VENUS_RUN_RETURNED status=${call(vm, "getStatus")}; inspect native renderer logs")
            } catch (error: Throwable) {
                Log.i(TAG, "VENUS_RUN_REJECTED ${error.javaClass.name}: ${error.message}")
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

    private companion object {
        const val NAME = "viruaisor-venus-backend-probe"
        const val TAG = "ViruaisorGpuProbe"
    }
}
