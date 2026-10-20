package pro.darc.viruaisor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pro.darc.viruaisor.vm.VmMemoryPolicy
import pro.darc.viruaisor.vm.hostMemoryBytes

@Composable
internal fun MemoryDialog(currentMiB: Int, enabled: Boolean, dismiss: () -> Unit, save: (Int) -> Unit) {
    val context = LocalContext.current
    val hostBytes = remember { hostMemoryBytes(context) }
    val limit = VmMemoryPolicy.maxMiB(hostBytes)
    var value by remember { mutableStateOf(currentMiB.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("调整分配内存") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("当前分配 $currentMiB MiB。保存后，下次启动虚拟机时生效。")
                OutlinedTextField(value, { value = it; error = null }, label = { Text("目标内存 · MiB") },
                    enabled = enabled, singleLine = true, isError = error != null,
                    modifier = Modifier.fillMaxWidth().testTag("memory_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(error ?: if (limit >= VmMemoryPolicy.MIN_MIB)
                        "可设置 ${VmMemoryPolicy.MIN_MIB}–$limit MiB（1 GiB = 1024 MiB）"
                        else "无法读取足够的设备内存，请稍后重试") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 4096, 8192).forEach { preset ->
                        FilterChip(selected = value.toIntOrNull() == preset,
                            onClick = { value = preset.toString(); error = null },
                            enabled = enabled && preset <= limit,
                            label = { Text("${preset / 1024} GiB") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("memory_preset_$preset"))
                    }
                }
                Muted(if (enabled) "请为 Android 和其他应用保留足够内存。" else "停止虚拟机并等待当前任务完成后可调整内存。")
            }
        }, confirmButton = {
            TextButton(enabled = enabled && limit >= VmMemoryPolicy.MIN_MIB,
                modifier = Modifier.testTag("confirm_memory"), onClick = {
                    error = VmMemoryPolicy.inputError(value, hostBytes, currentMiB)
                    if (error == null) { save(value.trim().toInt()); dismiss() }
                }) { Text("保存内存") }
        }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("cancel_memory")) { Text("取消") } })
}
