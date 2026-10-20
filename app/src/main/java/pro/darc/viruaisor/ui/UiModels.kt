package pro.darc.viruaisor.ui

import pro.darc.viruaisor.gpu.VenusHostState
import pro.darc.viruaisor.network.VmNetworkState
import pro.darc.viruaisor.root.AvfPermissionSetup
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.vm.DisplayMode
import pro.darc.viruaisor.vm.GuestPasswordRuntime

internal enum class Appearance(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

internal data class VmEnvironment(
    val root: RootAccess = RootAccess.Idle,
    val permissions: AvfPermissionSetup = AvfPermissionSetup.Idle,
    val host: VenusHostState = VenusHostState(),
    val avfProbe: VenusHostState = VenusHostState(),
    val useRootDisplay: Boolean = false,
    val displayStatus: String = "等待虚拟机和显示连接",
    val surfaceReady: Boolean = false,
    val network: VmNetworkState = VmNetworkState(),
    val passwordStatus: GuestPasswordRuntime.Status = GuestPasswordRuntime.Status.Idle,
) {
    val probesRunning get() = host.running || avfProbe.running
    val permissionBusy get() = permissions is AvfPermissionSetup.Working || root is RootAccess.Requesting
}

/** Only existing, bounded capabilities cross the UI boundary. */
internal data class VmUiActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onChooseBundle: () -> Unit = {},
    val onPreparedBundle: (() -> Unit)? = null,
    val onFullscreen: (Boolean) -> Unit = {},
    val onRequestRoot: () -> Unit = {},
    val onGrantAvf: () -> Unit = {},
    val onRevokeAvf: () -> Unit = {},
    val onToggleRootDisplay: () -> Unit = {},
    val onHostProbe: () -> Unit = {},
    val onAvfProbe: () -> Unit = {},
    val onResizeRootfs: (Long) -> Unit = {},
    val onRestoreRootfs: () -> Unit = {},
    val onResize: (DisplayMode) -> Unit = {},
    val onPassword: (String) -> Boolean = { false },
    val onNetwork: (Boolean) -> Unit = {},
)
