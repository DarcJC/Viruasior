package pro.darc.viruaisor.vm

internal object VmMemoryPolicy {
    const val MIB = 1024L * 1024
    const val MIN_MIB = 256
    const val MAX_MIB = 32768

    fun maxMiB(hostMemoryBytes: Long): Int =
        (hostMemoryBytes / MIB).coerceIn(0, MAX_MIB.toLong()).toInt()

    fun inputError(input: String, hostMemoryBytes: Long, currentMiB: Int): String? {
        val memoryMiB = input.trim().toIntOrNull() ?: return "请输入 MiB 整数"
        return error(memoryMiB, hostMemoryBytes)
            ?: if (memoryMiB == currentMiB) "分配内存已经是这个大小" else null
    }

    fun error(memoryMiB: Int, hostMemoryBytes: Long): String? = when {
        memoryMiB !in MIN_MIB..MAX_MIB -> "请输入 $MIN_MIB–$MAX_MIB MiB 之间的整数"
        hostMemoryBytes <= 0 -> "无法读取设备总内存，请稍后重试"
        memoryMiB > maxMiB(hostMemoryBytes) -> "不能超过设备总内存（${maxMiB(hostMemoryBytes)} MiB）"
        else -> null
    }
}
