package pro.darc.viruaisor.gpu

import java.io.File
import java.io.RandomAccessFile

internal object OverlayLeaseControl {
    fun release(file: File) {
        check(file.isFile) { "Missing lease control" }
        RandomAccessFile(file, "rw").use { control ->
            check(control.length() == 1L) { "Invalid lease control length" }
            check(control.read() in listOf('0'.code, '1'.code)) { "Invalid lease control value" }
            // Truncate-and-rewrite has an observable zero-byte interval for the
            // independent native pread. Publish at offset zero without resizing.
            control.seek(0)
            control.write('1'.code)
            control.fd.sync()
        }
    }
}
