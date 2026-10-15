package pro.darc.viruaisor.gpu

import java.io.InputStream
import java.io.OutputStream
import java.io.File

/** Retain a bounded prefix but always consume the VM pipe to avoid guest backpressure. */
internal object ProbeOutputDrain {
    fun reset(file: File) { file.outputStream().use { } }
    fun copy(input: InputStream, output: OutputStream, limit: Int): Long {
        require(limit >= 0)
        val buffer = ByteArray(8192)
        var total = 0L
        var retained = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val keep = minOf(count, limit - retained)
            if (keep > 0) output.write(buffer, 0, keep)
            retained += keep
            total += count
        }
        output.flush()
        return total
    }
}
