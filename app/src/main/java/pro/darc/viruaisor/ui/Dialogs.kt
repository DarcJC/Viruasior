package pro.darc.viruaisor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pro.darc.viruaisor.vm.*
import pro.darc.viruaisor.vm.DisplayMode

@Composable
internal fun CapacityDialog(state: VmUiState, enabled: Boolean, dismiss: () -> Unit, resize: (Long) -> Unit) {
    var value by remember { mutableStateOf(((state.storage.rootBytes + RootfsCapacity.GIB - 1) / RootfsCapacity.GIB).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }, title = { Text("调整根盘容量") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if(state.isActive) "虚拟机会先正常关闭，完成调整后重新启动。请保存 Linux 中的工作。" else "调整完成后，虚拟机将保持停止。")
            OutlinedTextField(value, { value = it; error = null }, label = { Text("目标容量 · GiB") },
                singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("capacity_input"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(error ?: "输入 1–1024 之间的整数") })
            Muted("扩缩前会保留原盘，直到新盘启动验证成功。缩小容量需要保留足够空间。")
        }
    }, confirmButton = {
        TextButton(enabled = enabled, modifier = Modifier.testTag("confirm_action"), onClick = {
            error = capacityError(value, state.storage.rootBytes)
            if(error == null) { resize(value.toLong() * RootfsCapacity.GIB); dismiss() }
        }) { Text("确认调整") }
    }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("cancel_action")) { Text("取消") } })
}

@Composable
internal fun PasswordDialog(enabled: Boolean, dismiss: () -> Unit, update: (String) -> Boolean) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun close() { password = ""; confirmation = ""; dismiss() }
    DisposableEffect(Unit) { onDispose { password = ""; confirmation = "" } }
    AlertDialog(onDismissRequest = ::close, title = { Text("修改 droid 密码") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Muted("用于 Linux 登录与锁屏。密码只发送给当前客体，不保存在应用设置中。")
            OutlinedTextField(password, { password = it; error = null }, label = { Text("新密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmation, { confirmation = it; error = null }, label = { Text("再次输入新密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = enabled, onClick = {
            if(password != confirmation) error = "两次输入的密码不一致"
            else {
                error = runCatching { GuestPasswordProtocol.command(password) }.exceptionOrNull()?.message
                if(error == null) {
                    if(update(password)) close() else error = "虚拟机暂不可用，请稍后重试"
                }
            }
        }) { Text("更新密码") }
    }, dismissButton = { TextButton(onClick = ::close) { Text("取消") } })
}

@Composable
internal fun DisplayModeDialog(state: VmUiState, dismiss: () -> Unit, resize: (DisplayMode) -> Unit) {
    val native = state.bundle?.display?.let { DisplaySize(it.width, it.height) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("选择显示模式") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Muted("刷新率为客体显示时序，不代表实际渲染帧率。")
            state.display.modes.sortedByDescending { native != null && DisplayModePolicy.fits(native, it.size) }.forEach { mode ->
                val label = when {
                    native == mode.size -> "原生尺寸"
                    native != null && DisplayModePolicy.fits(native, mode.size) -> "适配屏幕"
                    else -> null
                }
                TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = state.display.requested == null,
                    onClick = { resize(mode); dismiss() }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = mode == state.display.actual, onClick = null)
                        Column { Text(mode.toString()); label?.let { Muted(it) } }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
}
