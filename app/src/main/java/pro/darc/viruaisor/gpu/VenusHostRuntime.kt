package pro.darc.viruaisor.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VenusHostState(val running: Boolean = false, val message: String = "尚未验证宿主；不代表 AVF guest 已获 GPU 加速", val verified: Boolean = false)

object VenusHostRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(VenusHostState())
    val state = mutableState.asStateFlow()
    private val mutableAvfState = MutableStateFlow(VenusHostState(message = "宿主验证通过后可检查 AVF 后端；不挂载 guest 磁盘"))
    val avfState = mutableAvfState.asStateFlow()

    @Synchronized fun startAvfProbe(context: Context) {
        if (!mutableState.value.verified || mutableState.value.running || mutableAvfState.value.running) return
        mutableAvfState.value = VenusHostState(true, "正在检查 AVF Venus 后端…")
        val app = context.applicationContext
        scope.launch {
            val message = try { VenusAvfBackendProbe.run(app) } catch (failure: Exception) {
                Log.e("ViruaisorGpuProbe", "AVF probe failed", failure)
                "AVF 探测失败：${failure.message}"
            }
            mutableAvfState.value = VenusHostState(message = message)
        }
    }

    @Synchronized fun start(context: Context) {
        if (mutableState.value.running || mutableAvfState.value.running) return
        mutableState.value = VenusHostState(true, "正在验证 Venus 宿主（最多 30 秒，无 Root）…")
        val app = context.applicationContext
        scope.launch {
            var child: java.lang.Process? = null
            try {
                val output = File(app.filesDir, "venus-host-last.log")
                val executable = File(app.applicationInfo.nativeLibraryDir, "libvenus_host_probe.so")
                val builder = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).redirectOutput(output)
                builder.environment()["VENUS_ALLOCATOR"] = "dmaheap"
                child = builder.start()
                val completed = child.waitFor(30, TimeUnit.SECONDS)
                if (!completed) {
                    child.destroy()
                    if (!child.waitFor(2, TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        child.waitFor(2, TimeUnit.SECONDS)
                    }
                }
                val exit = if (completed) child.exitValue() else -1
                val report = output.readText().take(131072)
                report.lineSequence().filter(String::isNotBlank).forEach { Log.i("VenusHostProbe", it) }
                val verified = VenusHostReport.isVerified(report, exit, Process.myUid())
                mutableState.value = VenusHostState(verified = verified, message = if (verified) {
                    "Venus 宿主通过：普通应用 UID、DMA-BUF 导出/映射、GPU 计算 16/16 正确。AVF 尚未接入。"
                } else {
                    "Venus 宿主未通过（exit=$exit）；${report.lineSequence().lastOrNull { "VENUS_FAILURE" in it || "errno=" in it }.orEmpty()}"
                })
                Log.i("VenusHostProbe", "APP_VERIFIED=$verified exit=$exit uid=${Process.myUid()}")
            } catch (failure: Exception) {
                mutableState.value = VenusHostState(message = "Venus 宿主验证失败：${failure.message}")
                Log.e("VenusHostProbe", "Host validation failed", failure)
            } finally {
                child?.takeIf { it.isAlive }?.destroyForcibly()
            }
        }
    }
}
