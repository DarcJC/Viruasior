package pro.darc.viruaisor.vm

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

data class VmDisk(
    val image: File,
    val writable: Boolean,
)

enum class VmGpuProfile(val avfBackend: String) {
    SOFTWARE_2D("2d"),
    GFXSTREAM("gfxstream"),
    VENUS("virglrenderer"),
}

data class VmDisplayConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val horizontalDpi: Int = 240,
    val verticalDpi: Int = 240,
    val refreshRate: Int = 60,
)

data class VmBundle(
    val directory: File,
    val kernel: File,
    val initrd: File?,
    val rootfs: File,
    val rootfsPartitionLabel: String?,
    val rootfsPartitionGuid: String?,
    val extraDisks: List<VmDisk>,
    val osName: String,
    val memoryMiB: Int,
    val matchHostCpuTopology: Boolean,
    val vsockPort: Long,
    val network: Boolean,
    val autoMemoryBalloon: Boolean,
    val debuggable: Boolean,
    val consoleInputDevice: String?,
    val kernelArgs: List<String>,
    val gpuProfile: VmGpuProfile = VmGpuProfile.SOFTWARE_2D,
    val display: VmDisplayConfig = VmDisplayConfig(),
)

sealed interface AvfSupport {
    data object Checking : AvfSupport
    data class Available(val capabilities: Int) : AvfSupport
    data class Unavailable(val reason: String) : AvfSupport
}

sealed interface VmStatus {
    data object Stopped : VmStatus
    data object Importing : VmStatus
    data object Starting : VmStatus
    data object Running : VmStatus
    data object GuestAgentReady : VmStatus
    data object Stopping : VmStatus
    data class Crashed(val reason: String) : VmStatus
}

data class VmUiState(
    val support: AvfSupport = AvfSupport.Checking,
    val status: VmStatus = VmStatus.Stopped,
    val bundle: VmBundle? = null,
    val detail: String? = null,
    val display: VmDisplayState = VmDisplayState(),
) {
    val canImport: Boolean
        get() = support is AvfSupport.Available && status !is VmStatus.Importing && !isActive

    val canStart: Boolean
        get() = support is AvfSupport.Available && bundle != null &&
            (status is VmStatus.Stopped || status is VmStatus.Crashed)

    val canStop: Boolean
        get() = status is VmStatus.Starting || status is VmStatus.Running ||
            status is VmStatus.GuestAgentReady

    val isActive: Boolean
        get() = status is VmStatus.Starting || status is VmStatus.Running ||
            status is VmStatus.GuestAgentReady || status is VmStatus.Stopping
}

internal object AvfCapability {
    const val FEATURE = "android.software.virtualization_framework"
    const val SERVICE = "virtualization"
    const val MANAGE_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE"
    const val CUSTOM_PERMISSION = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    const val NON_PROTECTED_VM = 1 shl 1

    fun check(context: Context): AvfSupport {
        if (!context.packageManager.hasSystemFeature(FEATURE)) {
            return AvfSupport.Unavailable("This device does not advertise Android Virtualization Framework support.")
        }
        if (context.checkSelfPermission(MANAGE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return AvfSupport.Unavailable("MANAGE_VIRTUAL_MACHINE was not granted to this app.")
        }
        if (context.checkSelfPermission(CUSTOM_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return AvfSupport.Unavailable("USE_CUSTOM_VIRTUAL_MACHINE was not granted to this app.")
        }

        return try {
            val manager = context.getSystemService(SERVICE)
                ?: return AvfSupport.Unavailable("The AVF system service is unavailable.")
            val capabilities = manager.javaClass.getMethod("getCapabilities").invoke(manager) as Int
            if (capabilities and NON_PROTECTED_VM == 0) {
                AvfSupport.Unavailable("AVF is present, but non-protected VMs are not supported.")
            } else {
                AvfSupport.Available(capabilities)
            }
        } catch (error: Throwable) {
            AvfSupport.Unavailable(
                "AVF system APIs are unavailable: ${error.rootMessage()}. " +
                    "This build must be installed with the platform AVF permissions/API allowlist.",
            )
        }
    }
}

internal fun Throwable.rootMessage(): String {
    var current = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current.message ?: current.javaClass.simpleName
}
