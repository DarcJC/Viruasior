package pro.darc.viruaisor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import pro.darc.viruaisor.root.*
import pro.darc.viruaisor.vm.*

@Composable
internal fun SettingsPage(state: VmUiState, environment: VmEnvironment, appearance: Appearance,
    setAppearance: (Appearance) -> Unit, actions: VmUiActions, openDiagnostics: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("让工作空间按你的方式运行", style = MaterialTheme.typography.headlineMedium)
            Muted("完成必要配置，专注于 Linux。")
            EnvironmentCard(state, environment, actions)
            SectionCard("外观", "选择整个应用的主题。桌面内容由 Linux 系统控制。") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Appearance.entries.forEach { item ->
                        FilterChip(selected = item == appearance, onClick = { setAppearance(item) }, label = { Text(item.label) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("theme_${item.name.lowercase()}"))
                    }
                }
            }
            SectionCard("开发与排障", "Root、显示桥接和 GPU 验证的独立诊断空间。") {
                OutlinedButton(onClick = openDiagnostics, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_diagnostics")) {
                    Glyph(Symbol.CODE); Spacer(Modifier.width(10.dp)); Text("打开诊断中心"); Spacer(Modifier.weight(1f)); Glyph(Symbol.ARROW)
                }
            }
            SectionCard("关于 Viruaisor") {
                Text("把 Linux 带到你的 Android 工作空间。", style = MaterialTheme.typography.bodyMedium)
                Muted("基于 Android Virtualization Framework · 当前管理一台虚拟机")
            }
        }
    }
}

@Composable
private fun EnvironmentCard(state: VmUiState, environment: VmEnvironment, actions: VmUiActions) {
    val root = environment.root
    val policy = WorkbenchPolicy(state, environment.probesRunning, environment.permissionBusy)
    SectionCard("运行环境", "Root、AVF 授权和显示连接分别验证。") {
        DetailRow("Root 授权", rootLabel(root))
        OutlinedButton(onClick = actions.onRequestRoot, enabled = root !is RootAccess.Requesting && policy.canConfigure) {
            Text(if(root is RootAccess.Idle) "申请 Root 权限" else "重新检测 Root")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailRow("Android 虚拟化框架", when(state.support) {
            is AvfSupport.Available -> "AVF 可用"
            AvfSupport.Checking -> "正在检测…"
            is AvfSupport.Unavailable -> "需要配置或当前设备不支持"
        })
        if(state.support is AvfSupport.Unavailable) TechnicalDetails("查看环境检查原因", state.support.reason)
        when(val setup = environment.permissions) {
            AvfPermissionSetup.Working -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Muted("正在补齐权限并从应用进程验证…") }
            is AvfPermissionSetup.Failed -> Notice(setup.reason, true)
            is AvfPermissionSetup.Complete -> {
                val success = setup.changes.all { it.isGranted }
                Notice(if(success) "必要权限已验证。" else "仍有权限未通过验证，请查看诊断详情。", !success)
            }
            else -> Unit
        }
        Button(onClick = actions.onGrantAvf, enabled = root is RootAccess.Granted && policy.canConfigure,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("检查与补齐 AVF 权限") }
        Muted(if(!policy.canConfigure) "停止虚拟机并等待当前任务完成后，可更改运行环境。" else "只补齐本应用缺失的必要权限，保留已有授权。")
    }
}

@Composable
internal fun DiagnosticsPage(state: VmUiState, environment: VmEnvironment, actions: VmUiActions, back: () -> Unit) {
    val policy = WorkbenchPolicy(state, environment.probesRunning, environment.permissionBusy)
    var revoke by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 980.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TextButton(onClick = back, modifier = Modifier.testTag("back_settings")) { Glyph(Symbol.BACK); Spacer(Modifier.width(8.dp)); Text("返回设置") }
            Text("诊断中心", style = MaterialTheme.typography.headlineMedium)
            Muted("独立的技术诊断与实验工具。验证结果各自独立，不代表完整桌面已可用。")
            SectionCard("Root 与 AVF") {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(rootLabel(environment.root))
                        (environment.root as? RootAccess.Granted)?.let {
                            Text("${it.identity}\nSELinux: ${it.selinuxMode}\nsu: ${it.suPath}\n${it.implementation}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(when(val support = state.support) {
                            is AvfSupport.Available -> "AVF capabilities: 0x${support.capabilities.toString(16)}"
                            AvfSupport.Checking -> "正在检测 AVF"
                            is AvfSupport.Unavailable -> support.reason
                        }, style = MaterialTheme.typography.bodyMedium)
                        when(val setup = environment.permissions) {
                            is AvfPermissionSetup.Complete -> setup.changes.forEach {
                                Text("${it.permission.name}: ${if(it.isGranted) "已授权" else "未授权"}" +
                                    (if(it.wasGranted) " · 保留已有授权" else "") + (it.error?.let { error -> "\n$error" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            is AvfPermissionSetup.Failed -> Text(setup.reason, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                }
                OutlinedButton(onClick = actions.onRequestRoot, enabled = policy.canConfigure && environment.root !is RootAccess.Requesting) { Text("重新检测 Root") }
                OutlinedButton(onClick = { revoke = true }, enabled = policy.canConfigure && environment.root is RootAccess.Granted) { Text("撤销配置新增的 AVF 权限") }
                Muted("仅撤销本应用配置时新增的授权；已有授权不变。Android 可能终止应用。")
            }
            SectionCard("实验显示桥接", "当前仅限单 VM；内部显示接口没有 VM 所有权参数。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Root Direct Surface"); Muted("只桥接显示，VM 与输入仍属于应用") }
                    Switch(checked = environment.useRootDisplay, onCheckedChange = { actions.onToggleRootDisplay() },
                        enabled = environment.root is RootAccess.Granted && policy.canConfigure)
                }
                SelectionContainer { Text(environment.displayStatus, style = MaterialTheme.typography.bodySmall) }
                Muted("Venus 系统包会自动启用当前依赖的显示桥接。停止虚拟机后可切换。")
            }
            SectionCard("Venus 验证", "宿主 GPU 计算与 AVF 后端独立检查，不挂载客体磁盘。") {
                if(environment.probesRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                SelectionContainer { Text(environment.host.message, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = actions.onHostProbe, enabled = policy.canConfigure, modifier = Modifier.testTag("probe_host")) { Text("验证 Venus GPU 计算") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SelectionContainer { Text(environment.avfProbe.message, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = actions.onAvfProbe, enabled = policy.canConfigure && environment.host.verified) { Text("检查 AVF Venus 后端") }
                if(!policy.canConfigure) Notice("请先停止虚拟机，并等待导入、存储、授权或其他验证结束。")
                else if(!environment.host.verified) Muted("宿主验证通过后，才能检查 AVF 后端。")
            }
            state.bundle?.let { bundle ->
                SectionCard("当前运行配置") {
                    SelectionContainer { Text("VM 0 · default\n${bundle.directory}\nGPU: ${bundle.gpuProfile.avfBackend}\nvsock: ${bundle.vsockPort}\n${state.detail.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if(revoke) ConfirmDialog("撤销新增权限？", "仅撤销本应用记录的新增 AVF 授权，保留原有授权。Android 可能立即终止应用。", "撤销新增权限", policy.canConfigure,
        { revoke = false }, actions.onRevokeAvf)
}

private fun rootLabel(root: RootAccess): String = when(root) {
    RootAccess.Idle -> "尚未申请 Root"
    RootAccess.Requesting -> "正在请求 Root，请在授权管理器中允许…"
    is RootAccess.Granted -> "已获得 Root（UID 0）"
    is RootAccess.Denied -> root.reason
    is RootAccess.Unavailable -> root.reason
}
