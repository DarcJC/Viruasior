package pro.darc.viruaisor.ui

import pro.darc.viruaisor.vm.*

/** Shared action gates for the workbench and diagnostics. */
internal class WorkbenchPolicy(
    private val state: VmUiState,
    probesRunning: Boolean = false,
    permissionBusy: Boolean = false,
) {
    private val externalBusy = probesRunning || permissionBusy
    val canStart = state.canStart && !externalBusy
    val canImport = state.canImport && !externalBusy
    val canStop = state.canStop && !externalBusy
    val canConfigure = !state.isActive && state.status !is VmStatus.Importing && !state.storage.busy && !externalBusy
    val canResizeDisk = state.bundle != null && state.support is AvfSupport.Available &&
        !state.storage.busy && !state.storage.recoveryPending && !externalBusy &&
        state.status !is VmStatus.Importing && state.status !is VmStatus.Stopping
    val canRestoreDisk = state.storage.recoveryPending && canConfigure
}

internal fun capacityError(input: String, currentBytes: Long): String? {
    val gib = input.toLongOrNull()
    return when {
        gib == null || gib !in 1..1024 -> "请输入 1–1024 之间的整数"
        gib * RootfsCapacity.GIB == currentBytes -> "根盘已经是这个容量"
        else -> null
    }
}

internal fun statusLabel(status: VmStatus): String = when (status) {
    VmStatus.Stopped -> "已停止"
    VmStatus.Importing -> "正在导入"
    VmStatus.Starting -> "正在启动"
    VmStatus.Running -> "虚拟机运行中"
    VmStatus.GuestAgentReady -> "客体服务已就绪"
    VmStatus.Stopping -> "正在关闭"
    is VmStatus.Crashed -> "运行中断"
}

internal fun VmUiState.isTransitioning() = status is VmStatus.Importing || status is VmStatus.Starting ||
    status is VmStatus.Stopping || storage.busy
