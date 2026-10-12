package pro.darc.viruaisor.root

import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

sealed interface RootAccess {
    data object Idle : RootAccess
    data object Requesting : RootAccess
    data class Granted(
        val suPath: String,
        val implementation: String,
        val identity: String,
        val selinuxMode: String,
    ) : RootAccess
    data class Denied(val reason: String) : RootAccess
    data class Unavailable(val reason: String) : RootAccess
}

internal data class RootCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

internal fun interface RootCommandExecutor {
    @Throws(IOException::class)
    fun execute(command: List<String>, timeoutSeconds: Long): RootCommandResult
}

/**
 * KernelSU authorizes the UID executing su, so this must run in the app, not in an ADB shell.
 * Only a successful su command reporting UID 0 counts as a grant. These probes are read-only:
 * obtaining a root child does not elevate the app's Binder calls or change SELinux policy.
 */
internal class RootAccessClient(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor(),
    private val candidates: List<String> = SU_CANDIDATES,
) {
    fun request(): RootAccess {
        for (su in candidates) {
            val result = try {
                executor.execute(listOf(su, "-c", ROOT_PROBE), AUTH_TIMEOUT_SECONDS)
            } catch (_: IOException) {
                // KernelSU may hide su from PATH; try known paths when exec itself fails.
                continue
            }
            if (result.timedOut) {
                return RootAccess.Unavailable(
                    "Root 请求超时。请在 KernelSU 中允许 Viruaisor 的超级用户权限，然后重试。",
                )
            }
            val uid = result.output.lineSequence()
                .firstOrNull { it.startsWith(UID_MARKER) }
                ?.removePrefix(UID_MARKER)?.trim()
            if (result.exitCode != 0 || uid != "0") {
                return RootAccess.Denied(
                    "未获得 Root。请在 KernelSU → 超级用户中允许 Viruaisor，然后重试。" +
                        result.output.trim().take(300).let { if (it.isEmpty()) "" else "\n$it" },
                )
            }
            val version = try {
                executor.execute(listOf(su, "-v"), VERSION_TIMEOUT_SECONDS)
                    .takeIf { it.exitCode == 0 && !it.timedOut }
                    ?.output?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            } catch (_: IOException) {
                null
            }
            return RootAccess.Granted(
                suPath = su,
                implementation = version ?: "su (版本未知)",
                identity = result.output.lineSequence().firstOrNull { it.startsWith("uid=") }
                    ?: "uid=0",
                selinuxMode = result.output.lineSequence()
                    .firstOrNull { it.startsWith(SELINUX_MARKER) }
                    ?.removePrefix(SELINUX_MARKER)?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown",
            )
        }
        return RootAccess.Unavailable(
            "无法执行 su。请确认 KernelSU 已启用，并为 Viruaisor 开启超级用户访问后重试。",
        )
    }

    private companion object {
        const val UID_MARKER = "VIRUAISOR_ROOT_UID="
        const val SELINUX_MARKER = "VIRUAISOR_SELINUX="
        const val ROOT_PROBE =
            "printf '$UID_MARKER'; id -u; id; printf '$SELINUX_MARKER'; getenforce"
        const val AUTH_TIMEOUT_SECONDS = 60L
        const val VERSION_TIMEOUT_SECONDS = 5L
        val SU_CANDIDATES = listOf(
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        )
    }
}

/** Drains output concurrently so a full pipe cannot hang an authorization request. */
internal class ProcessRootCommandExecutor : RootCommandExecutor {
    override fun execute(command: List<String>, timeoutSeconds: Long): RootCommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val outputTask = FutureTask {
            process.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    val kept = minOf(count, (MAX_OUTPUT_CHARS - output.length).coerceAtLeast(0))
                    if (kept > 0) output.append(buffer, 0, kept)
                }
                output.toString()
            }
        }
        try {
            process.outputStream.close()
            Thread(outputTask, "ViruaisorRootOutput").apply { isDaemon = true }.start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return RootCommandResult(exitCode = -1, output = "", timedOut = true)
            }
            val output = try {
                outputTask.get(1, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                // A descendant may keep stdout open even after su exits. Never block the UI on it.
                return RootCommandResult(exitCode = -1, output = "", timedOut = true)
            }
            return RootCommandResult(process.exitValue(), output)
        } finally {
            process.destroy()
            outputTask.cancel(true)
            process.inputStream.close()
        }
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 16 * 1024
    }
}
