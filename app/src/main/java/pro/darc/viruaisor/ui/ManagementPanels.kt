package pro.darc.viruaisor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.vm.*
import java.util.Locale

@Composable
internal fun ControlPanel(state: VmUiState, environment: VmEnvironment, actions: VmUiActions, openSettings: () -> Unit) {
    val policy = WorkbenchPolicy(state, environment.probesRunning, environment.permissionBusy)
    var confirmation by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("会话控制", style = MaterialTheme.typography.titleMedium)
        StatusBadge(statusLabel(state.status), state.status is VmStatus.GuestAgentReady, state.status is VmStatus.Crashed)
        if(state.isTransitioning()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(environment.probesRunning) Notice("诊断验证进行中，请等待结束后操作虚拟机。")
        if(environment.permissionBusy) Notice("正在配置运行权限，请稍候。")
        if(state.status is VmStatus.Crashed) Notice("虚拟机运行中断，可查看详情后重新启动。", error = true)
        if(state.canStop || state.isActive) {
            OutlinedButton(onClick = { confirmation = "stop" }, enabled = policy.canStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("action_stop")) {
                Glyph(Symbol.POWER); Spacer(Modifier.width(10.dp)); Text("关闭虚拟机")
            }
        } else {
            Button(onClick = actions.onStart, enabled = policy.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("action_start")) {
                Glyph(Symbol.PLAY); Spacer(Modifier.width(10.dp)); Text("启动虚拟机")
            }
        }
        if(state.support !is AvfSupport.Available) {
            Notice(if(state.support is AvfSupport.Checking) "正在检查运行环境…" else "运行环境尚未就绪，请先完成设置。")
            TextButton(onClick = openSettings) { Text("配置运行环境"); Spacer(Modifier.width(6.dp)); Glyph(Symbol.ARROW) }
        }
        state.bundle?.let { bundle ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow("操作系统", bundle.osName)
            DetailRow("分配内存", "${bundle.memoryMiB} MiB")
        }
        OutlinedButton(onClick = { if(state.bundle == null) actions.onChooseBundle() else confirmation = "replace" },
            enabled = policy.canImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("choose_bundle")) {
            Text(if(state.bundle == null) "导入系统包" else "替换系统包")
        }
        actions.onPreparedBundle?.let {
            TextButton(onClick = { if(state.bundle == null) it() else confirmation = "prepared" }, enabled = policy.canImport,
                modifier = Modifier.fillMaxWidth()) { Text("导入已准备的 Manjaro 镜像") }
        }
        state.detail?.let { TechnicalDetails(text = it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("网络连接", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("共享 Android VPN"); Muted("停止虚拟机后可更改") }
            Switch(checked = environment.network.vpn, onCheckedChange = actions.onNetwork, enabled = policy.canConfigure)
        }
        Muted(environment.network.message)
        if(environment.network.vpn) Muted("VPN 断开时暂停客体外网，避免自动切换到普通网络。")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow("Linux 账户", "droid")
        val canPassword = state.status is VmStatus.GuestAgentReady && !state.storage.busy &&
            !environment.probesRunning && !environment.permissionBusy && environment.passwordStatus != GuestPasswordRuntime.Status.Updating
        OutlinedButton(onClick = { password = true }, enabled = canPassword, modifier = Modifier.fillMaxWidth()) {
            Text(if(environment.passwordStatus == GuestPasswordRuntime.Status.Updating) "正在更新密码…" else "修改账户密码")
        }
        when(environment.passwordStatus) {
            GuestPasswordRuntime.Status.Changed -> Notice("droid 密码已更新，可用于 Linux 登录与锁屏。")
            GuestPasswordRuntime.Status.Failed -> Notice("密码更新失败，请检查客体连接与配套 agent。", true)
            else -> if(!canPassword) Muted("客体服务就绪后可修改密码。")
        }
    }
    when(confirmation) {
        "stop" -> ConfirmDialog("关闭虚拟机？", "这会结束当前 Linux 会话。请先保存客体中的工作。", "关闭虚拟机", policy.canStop,
            { confirmation = null }, actions.onStop)
        "replace", "prepared" -> ConfirmDialog("替换当前系统？", "当前只保存一台虚拟机。导入新包会替换现有系统及其根盘，请先备份重要文件。", "继续导入", policy.canImport,
            { confirmation = null }, if(confirmation == "prepared") actions.onPreparedBundle ?: {} else actions.onChooseBundle)
    }
    if(password) PasswordDialog(state.status is VmStatus.GuestAgentReady && environment.passwordStatus != GuestPasswordRuntime.Status.Updating,
        { password = false }, actions.onPassword)
}

@Composable
internal fun StoragePanel(state: VmUiState, environment: VmEnvironment, actions: VmUiActions) {
    val storage = state.storage
    val policy = WorkbenchPolicy(state, environment.probesRunning, environment.permissionBusy)
    var resize by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("根盘容量", style = MaterialTheme.typography.titleMedium)
        Text(if(storage.rootBytes > 0) String.format(Locale.ROOT, "%.2f GiB", storage.rootBytes.toDouble()/RootfsCapacity.GIB) else "尚未导入",
            style = MaterialTheme.typography.headlineLarge)
        Muted("逻辑容量 · 不代表已用空间")
        if(storage.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Notice("正在处理根盘，请等待操作完成。") }
        storage.message?.let { Notice(it, state.status is VmStatus.Crashed) }
        OutlinedButton(onClick = { resize = true }, enabled = policy.canResizeDisk,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resize_disk")) { Text("调整根盘容量") }
        if(storage.recoveryPending) {
            Notice("恢复盘已保留。新盘启动验证成功后自动清理；验证前无法替换系统包。")
            OutlinedButton(onClick = { restore = true }, enabled = policy.canRestoreDisk, modifier = Modifier.fillMaxWidth()) { Text("恢复调整前的根盘") }
            if(!policy.canRestoreDisk) Muted("停止虚拟机并等待存储操作完成后可恢复。")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("共享目录", style = MaterialTheme.typography.titleMedium)
        DetailRow("Android 应用目录", "storage/0")
        DetailRow("Linux 挂载路径", "/mnt/shared")
        StatusBadge(when(storage.sharedMounted) { true -> "已挂载"; false -> "未挂载"; null -> "等待启动验证" }, storage.sharedMounted == true)
        if(storage.sharedPath == null) Notice("外部存储当前不可用。虚拟机仍可启动。")
        else TechnicalDetails("查看完整主机路径", storage.sharedPath)
        Muted("此目录中的文件独立于根盘，调整或恢复根盘不会清除共享文件。")
    }
    if(resize) CapacityDialog(state, policy.canResizeDisk, { resize = false }, actions.onResizeRootfs)
    if(restore) ConfirmDialog("恢复原根盘？", "新根盘上的改动会被放弃，共享目录中的文件不受影响。", "恢复原盘", policy.canRestoreDisk,
        { restore = false }, actions.onRestoreRootfs)
}

@Composable
internal fun DisplayPanel(state: VmUiState, environment: VmEnvironment, actions: VmUiActions) {
    var modes by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("显示模式", style = MaterialTheme.typography.titleMedium)
        DetailRow("当前客体时序", state.display.actual?.toString() ?: "等待客体上报")
        DetailRow("已呈现尺寸", state.display.presented?.toString() ?: "尚未收到画面报告")
        if(state.display.requested != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Notice("正在请求 ${state.display.requested}，等待客体与新画面确认。")
        }
        state.display.error?.let { Notice(it, true) }
        OutlinedButton(onClick = { modes = true }, enabled = state.display.modes.isNotEmpty() && state.display.requested == null &&
            state.status is VmStatus.GuestAgentReady && !state.storage.busy && !environment.permissionBusy && !environment.probesRunning,
            modifier = Modifier.fillMaxWidth()) { Text("选择显示模式") }
        Muted("模式来自当前客体。刷新率是显示时序，不代表实际渲染帧率。")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("桌面与输入", style = MaterialTheme.typography.titleMedium)
        DetailRow("工作台", "点击预览进入可交互全屏")
        DetailRow("退出全屏", "轻触顶部小手柄唤出退出按钮，或 Ctrl + Shift + Alt + Q")
        DetailRow("切到后台", "Android 返回或 Home，不会关闭虚拟机")
        Muted("全屏支持触摸、鼠标、键盘与触摸板。切换模式时暂时释放输入。")
        TechnicalDetails(text = "${if(environment.useRootDisplay) "Root 显示桥接" else "普通 AVF 显示"}\n${environment.displayStatus}")
    }
    if(modes) DisplayModeDialog(state, { modes = false }, actions.onResize)
}
