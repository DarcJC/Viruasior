package pro.darc.viruaisor.vm

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

internal object Ext4Geometry {
    fun bytes(image: File): Long {
        require(image.isFile && image.length() >= 2048) { "Rootfs must be a raw ext4 partition image." }
        val data = ByteArray(1024)
        RandomAccessFile(image, "r").use { it.seek(1024); it.readFully(data) }
        val sb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(sb.getShort(56).toInt() and 0xffff == 0xef53) { "Rootfs is not an ext4 partition image." }
        val shift = sb.getInt(24)
        require(shift in 0..6) { "Invalid ext4 block size." }
        val low = sb.getInt(4).toLong() and 0xffffffffL
        val high = if (sb.getInt(96) and 0x80 != 0) sb.getInt(336).toLong() and 0xffffffffL else 0
        require(high <= 0x7fffffffL) { "Invalid ext4 block count." }
        val count = low or (high shl 32)
        val bytes = runCatching { Math.multiplyExact(count, 1024L shl shift) }.getOrNull()
        require(bytes != null && bytes in 2048..image.length()) { "Ext4 exceeds the image length." }
        return bytes
    }

    fun blockSize(image: File): Long = RandomAccessFile(image, "r").use {
        it.seek(1048)
        val shift = Integer.reverseBytes(it.readInt())
        require(shift in 0..6)
        1024L shl shift
    }
}
internal object RootfsCapacity {
    const val GIB = 1024L * 1024 * 1024
    fun parseGiB(value: String): Long {
        val gib = value.trim().toLongOrNull()
        require(gib != null && gib in 1..1024) { "容量必须是 1–1024 GiB 的整数。" }
        return gib * GIB
    }
}

internal data class RootfsToolResult(val code: Int, val output: String)

internal class PackagedRootfsTools(
    private val checker: File,
    private val resizer: File,
    private val run: (List<String>) -> RootfsToolResult = ::runRootfsTool,
) : RootfsTools {
    override fun filesystemBytes(image: File) = Ext4Geometry.bytes(image)

    override fun check(image: File) {
        val repaired = run(listOf(checker.path, "-f", "-p", image.path))
        check(repaired.code in 0..2) { "ext4 check failed (${repaired.code}): ${repaired.output}" }
        val verified = run(listOf(checker.path, "-f", "-n", image.path))
        check(verified.code == 0) { "ext4 verification failed (${verified.code}): ${verified.output}" }
    }

    override fun minimumBytes(image: File): Long {
        val result = run(listOf(resizer.path, "-P", image.path))
        check(result.code == 0) { "Cannot estimate ext4 minimum: ${result.output}" }
        val blocks = Regex("Estimated minimum size of the filesystem: (\\d+)|Estimated minimum size of filesystem: (\\d+)")
            .find(result.output)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toLongOrNull()
            ?: error("Invalid ext4 minimum-size report: ${result.output}")
        val estimated = Math.multiplyExact(blocks, Ext4Geometry.blockSize(image))
        return Math.addExact(estimated, maxOf(256L * 1024 * 1024, estimated / 10))
    }

    override fun resize(image: File, targetBytes: Long) {
        val result = run(listOf(resizer.path, image.path, "${targetBytes / 512}s"))
        check(result.code == 0) { "ext4 resize failed (${result.code}): ${result.output}" }
    }
}

private fun runRootfsTool(arguments: List<String>): RootfsToolResult {
    check(File(arguments.first()).canExecute()) { "Packaged ext4 tool is missing; rebuild the APK native tools." }
    val builder = ProcessBuilder(arguments).redirectErrorStream(true)
    builder.environment()["LC_ALL"] = "C"
    val process = builder.start()
    val output = StringBuilder()
    val reader = Thread({
        process.inputStream.bufferedReader().use { input ->
            val buffer = CharArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                synchronized(output) {
                    output.append(buffer, 0, count)
                    if (output.length > 65536) output.delete(0, output.length - 65536)
                }
            }
        }
    }, "rootfs-tool-output").apply { isDaemon = true; start() }
    try {
        check(process.waitFor(30, TimeUnit.MINUTES)) { "Ext4 tool timed out; original rootfs retained." }
        reader.join(5000)
        check(!reader.isAlive) { "Ext4 tool output did not close." }
        return RootfsToolResult(process.exitValue(), synchronized(output) { output.toString() })
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
    }
}
