package pro.darc.viruaisor.vm

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.MotionEvent
import pro.darc.viruaisor.display.VmInputBackend
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

sealed interface VmEngineEvent {
    data object Running : VmEngineEvent
    data object GuestAgentReady : VmEngineEvent
    data class Error(val message: String) : VmEngineEvent
    data class Stopped(val reason: Int, val expected: Boolean) : VmEngineEvent
}

/**
 * Thin reflective adapter around AVF's system API.
 *
 * API 37 ships the implementation on AVF devices, but the classes are not in the public
 * android.jar. Keeping reflection isolated here lets the rest of the app remain testable and gives
 * an actionable runtime error when the app is not installed with the required system API access.
 */
class AvfVmEngine(
    context: Context,
    private val callbackExecutor: Executor,
    private val onEvent: (VmEngineEvent) -> Unit,
    private val venusRuntimeReady: () -> Boolean = { false },
) : VmInputBackend {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var vm: Any? = null
    private var consoleInput: OutputStream? = null
    private var guestAgent: Any? = null
    private var callbackProxy: Any? = null
    private var stopRequested = false

    fun start(bundle: VmBundle) = synchronized(lock) {
        Log.d(TAG, "start engine=${System.identityHashCode(this)}")
        try {
            check(bundle.gpuProfile != VmGpuProfile.VENUS || venusRuntimeReady()) {
                "Venus requires the pinned application-private desktop runtime; software fallback is forbidden."
            }
            stopRequested = false
            guestAgent = null
            val manager = manager()
            val desiredConfig = buildConfig(bundle)
            val existing = invoke(manager, "get", VM_NAME)
            val target = when {
                existing == null -> invoke(manager, "create", VM_NAME, desiredConfig)
                invoke(existing, "getStatus") as Int == STATUS_RUNNING -> existing
                else -> {
                    val currentConfig = invoke(existing, "getConfig")
                        ?: error("AVF returned no configuration for the existing VM.")
                    val compatible = invoke(currentConfig, "isCompatibleWith", desiredConfig) as Boolean
                    if (compatible) {
                        invoke(existing, "setConfig", desiredConfig)
                        existing
                    } else {
                        invoke(manager, "delete", VM_NAME)
                        invoke(manager, "create", VM_NAME, desiredConfig)
                    }
                }
            } ?: error("AVF returned no virtual machine instance.")
            vm = target
            Log.d(TAG, "using vm=${System.identityHashCode(target)} status=${invoke(target, "getStatus")}")
            installCallback(target)

            val status = invoke(target, "getStatus") as Int
            if (status == STATUS_RUNNING) {
                onEvent(VmEngineEvent.Running)
            } else {
                invoke(target, "run")
                if (invoke(target, "getStatus") as Int == STATUS_RUNNING) {
                    onEvent(VmEngineEvent.Running)
                }
            }
            true
        } catch (error: Throwable) {
            onEvent(VmEngineEvent.Error(error.avfMessage()))
            false
        }
    }

    fun connectVsock(port: Long): ParcelFileDescriptor = synchronized(lock) {
        val target = vm ?: error("The VM is not running.")
        invoke(target, "connectVsock", port) as? ParcelFileDescriptor
            ?: error("AVF did not return a vsock descriptor.")
    }

    fun sendConsoleInput(text: String) = synchronized(lock) {
        Log.d(TAG, "sendConsoleInput engine=${System.identityHashCode(this)} vm=${vm?.let(System::identityHashCode)} bytes=${text.toByteArray().size}")
        val target = vm ?: error("The VM is not running.")
        val output = consoleInput ?: (invoke(target, "getConsoleInput") as? OutputStream)
            ?.also { consoleInput = it }
            ?: error("AVF did not return a console input stream.")
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    override fun setTouchScale(scale: Float) {
        synchronized(lock) { vm }?.let { target -> invoke(target, "setTouchScale", scale) }
    }

    override fun sendTouch(event: MotionEvent): Boolean =
        synchronized(lock) { vm }?.let { target ->
            runCatching { invoke(target, "sendMultiTouchEvent", event) as Boolean }
                .getOrDefault(false)
        } ?: false

    override fun sendMouse(event: MotionEvent): Boolean =
        synchronized(lock) { vm }?.let { target ->
            runCatching { invoke(target, "sendMouseEvent", event) as Boolean }
                .getOrDefault(false)
        } ?: false

    override fun sendKey(scanCode: Short, down: Boolean): Boolean =
        synchronized(lock) { vm }?.let { target ->
            runCatching { invoke(target, "sendKeyEvent", scanCode, down) as Boolean }
                .getOrDefault(false)
        } ?: false

    fun requestGuestAgentShutdown(): Boolean = synchronized(lock) {
        val agent = guestAgent ?: return@synchronized false
        stopRequested = true
        try {
            invoke(agent, "shutdownAsync")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun requestHardStop() {
        synchronized(lock) {
            stopRequested = true
            val target = vm ?: return@synchronized
            try {
                invoke(target, "stop")
            } catch (error: Throwable) {
                onEvent(VmEngineEvent.Error(error.avfMessage()))
            }
        }
    }

    fun deleteDefinition() {
        synchronized(lock) {
            val manager = manager()
            val existing = invoke(manager, "get", VM_NAME) ?: return@synchronized
            val status = invoke(existing, "getStatus") as Int
            check(status != STATUS_RUNNING) { "Stop the VM before importing another bundle." }
            invoke(manager, "delete", VM_NAME)
            consoleInput?.close()
            vm = null
            consoleInput = null
            guestAgent = null
            callbackProxy = null
        }
    }

    fun clearCallback() {
        synchronized(lock) {
            val target = vm ?: return@synchronized
            runCatching { invoke(target, "clearCallback") }
            callbackProxy = null
        }
    }

    fun closeStopped() = synchronized(lock) {
        val target=vm ?: return@synchronized
        check(invoke(target,"getStatus") != STATUS_RUNNING) { "Cannot close an active VM" }
        invoke(target,"close")
        vm=null; consoleInput?.close(); consoleInput=null; guestAgent=null; callbackProxy=null
    }

    private fun manager(): Any = appContext.getSystemService(AvfCapability.SERVICE)
        ?: error("The AVF system service is unavailable.")

    private fun buildConfig(bundle: VmBundle): Any {
        val customConfigClass = loadClass(CUSTOM_CONFIG)
        val customBuilder = loadClass("$CUSTOM_CONFIG\$Builder").getConstructor().newInstance()
        invoke(customBuilder, "setName", VM_NAME)
        invoke(customBuilder, "setOsName", bundle.osName)
        invoke(customBuilder, "setKernelPath", bundle.kernel.absolutePath)
        bundle.initrd?.let { invoke(customBuilder, "setInitrdPath", it.absolutePath) }
        invoke(customBuilder, "useNetwork", bundle.network)
        invoke(customBuilder, "useAutoMemoryBalloon", bundle.autoMemoryBalloon)
        bundle.kernelArgs.forEach { invoke(customBuilder, "addParam", it) }

        val displayBuilder = loadClass("$CUSTOM_CONFIG\$DisplayConfig\$Builder")
            .getConstructor()
            .newInstance()
        invoke(displayBuilder, "setWidth", bundle.display.width)
        invoke(displayBuilder, "setHeight", bundle.display.height)
        invoke(displayBuilder, "setHorizontalDpi", bundle.display.horizontalDpi)
        invoke(displayBuilder, "setVerticalDpi", bundle.display.verticalDpi)
        invoke(displayBuilder, "setRefreshRate", bundle.display.refreshRate)
        val displayConfig = invoke(displayBuilder, "build")
            ?: error("Could not build the AVF display configuration.")
        invoke(customBuilder, "setDisplayConfig", displayConfig)

        val gpuBuilder = loadClass("$CUSTOM_CONFIG\$GpuConfig\$Builder")
            .getConstructor()
            .newInstance()
        VmGpuConfiguration.apply(bundle.gpuProfile) { method, value ->
            invoke(gpuBuilder, method, value)
        }
        val gpuConfig = invoke(gpuBuilder, "build")
            ?: error("Could not build the AVF GPU configuration.")
        invoke(customBuilder, "setGpuConfig", gpuConfig)
        invoke(customBuilder, "useTouch", true)
        invoke(customBuilder, "useKeyboard", true)
        invoke(customBuilder, "useMouse", true)

        val diskClass = loadClass("$CUSTOM_CONFIG\$Disk")
        val rootDisk = if (bundle.rootfsPartitionLabel != null) {
            val disk = diskClass.getMethod("RWDisk", String::class.java)
                .invoke(null, null as Any?)
            val partitionClass = loadClass("$CUSTOM_CONFIG\$Partition")
            val partition = partitionClass.getConstructor(
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                String::class.java,
            ).newInstance(
                bundle.rootfsPartitionLabel,
                bundle.rootfs.absolutePath,
                true,
                bundle.rootfsPartitionGuid,
            )
            invoke(disk, "addPartition", partition)
            disk
        } else {
            diskClass.getMethod("RWDisk", String::class.java)
                .invoke(null, bundle.rootfs.absolutePath)
        }
        invoke(customBuilder, "addDisk", rootDisk)
        bundle.extraDisks.forEach { extra ->
            val factory = if (extra.writable) "RWDisk" else "RODisk"
            val disk = diskClass.getMethod(factory, String::class.java)
                .invoke(null, extra.image.absolutePath)
            invoke(customBuilder, "addDisk", disk)
        }
        val customConfig = invoke(customBuilder, "build")
            ?: error("Could not build the custom image configuration.")

        val configClass = loadClass(VM_CONFIG)
        val configBuilderClass = loadClass("$VM_CONFIG\$Builder")
        val configBuilder = configBuilderClass.getConstructor(Context::class.java)
            .newInstance(appContext)
        invoke(configBuilder, "setCustomImageConfig", customConfig)
        invoke(configBuilder, "setProtectedVm", false)
        invoke(configBuilder, "setMemoryBytes", bundle.memoryMiB.toLong() * MIB)
        invoke(configBuilder, "setShouldUseHugepages", false)
        if (bundle.debuggable) {
            val debugFull = configClass.getField("DEBUG_LEVEL_FULL").getInt(null)
            invoke(configBuilder, "setDebugLevel", debugFull)
        }
        bundle.consoleInputDevice?.let {
            invoke(configBuilder, "setConsoleInputDevice", it)
            invoke(configBuilder, "setVmConsoleInputSupported", true)
        }
        val topology = if (bundle.matchHostCpuTopology) {
            configClass.getField("CPU_TOPOLOGY_MATCH_HOST").getInt(null)
        } else {
            configClass.getField("CPU_TOPOLOGY_ONE_CPU").getInt(null)
        }
        invoke(configBuilder, "setCpuTopology", topology)
        return invoke(configBuilder, "build")
            ?: error("Could not build the AVF VM configuration.")
    }

    private fun installCallback(target: Any) {
        val callbackClass = loadClass(VM_CALLBACK)
        val proxy = Proxy.newProxyInstance(
            appContext.classLoader,
            arrayOf(callbackClass),
        ) { proxyInstance, method, args ->
            Log.d(TAG, "AVF callback ${method.name}; args=${args?.map { it?.javaClass?.name }}")
            when (method.name) {
                "onPayloadStarted", "onPayloadReady" -> {
                    onEvent(VmEngineEvent.Running)
                    null
                }
                "onGuestAgentRegistered" -> {
                    synchronized(lock) { guestAgent = args?.getOrNull(1) }
                    onEvent(VmEngineEvent.GuestAgentReady)
                    null
                }
                "onError" -> {
                    onEvent(
                        VmEngineEvent.Error(args?.getOrNull(2)?.toString() ?: "Unknown AVF error"),
                    )
                    null
                }
                "onStopped" -> {
                    val reason = args?.getOrNull(1) as? Int ?: STOP_REASON_UNKNOWN
                    synchronized(lock) {
                        vm?.let { stopped -> runCatching { invoke(stopped,"close") } }
                        consoleInput?.close()
                        vm = null
                        consoleInput = null
                        guestAgent = null
                        callbackProxy = null
                    }
                    onEvent(
                        VmEngineEvent.Stopped(
                            reason = reason,
                            expected = stopRequested || reason == STOP_REASON_SHUTDOWN,
                        ),
                    )
                    null
                }
                "toString" -> "ViruaisorVirtualMachineCallback"
                "hashCode" -> System.identityHashCode(proxyInstance)
                "equals" -> proxyInstance === args?.getOrNull(0)
                else -> null
            }
        }
        callbackProxy = proxy
        invoke(target, "setCallback", callbackExecutor, proxy)
    }

    private fun invoke(receiver: Any, name: String, vararg arguments: Any): Any? {
        val method = receiver.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == arguments.size &&
                candidate.parameterTypes.indices.all { index ->
                    candidate.parameterTypes[index].accepts(arguments[index].javaClass)
                }
        } ?: error("AVF method ${receiver.javaClass.name}#$name is unavailable.")
        return try {
            method.invoke(receiver, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    private fun Class<*>.accepts(argument: Class<*>): Boolean = when (this) {
        java.lang.Boolean.TYPE -> argument == java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> argument == java.lang.Integer::class.java
        java.lang.Long.TYPE -> argument == java.lang.Long::class.java
        java.lang.Short.TYPE -> argument == java.lang.Short::class.java
        java.lang.Float.TYPE -> argument == java.lang.Float::class.java
        else -> isAssignableFrom(argument)
    }

    private fun loadClass(name: String): Class<*> = try {
        Class.forName(name)
    } catch (error: Throwable) {
        throw IllegalStateException(
            "AVF system class $name is not accessible. Install this app as an AVF-enabled system app.",
            error,
        )
    }

    private fun Throwable.avfMessage(): String =
        "${rootMessage()} (the app requires AVF system API access and both VM permissions)"

    private companion object {
        const val TAG = "ViruaisorAvf"
        const val VM_NAME = "default"
        const val VM_CONFIG = "android.system.virtualmachine.VirtualMachineConfig"
        const val CUSTOM_CONFIG = "android.system.virtualmachine.VirtualMachineCustomImageConfig"
        const val VM_CALLBACK = "android.system.virtualmachine.VirtualMachineCallback"
        const val STATUS_RUNNING = 1
        const val STOP_REASON_UNKNOWN = 2
        const val STOP_REASON_SHUTDOWN = 3
        const val MIB = 1024L * 1024L
    }
}
