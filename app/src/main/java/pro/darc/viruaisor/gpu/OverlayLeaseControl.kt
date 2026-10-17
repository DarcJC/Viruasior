package pro.darc.viruaisor.gpu

import java.io.File
import java.io.RandomAccessFile

internal object OverlayLeaseControl {
    @Synchronized fun heartbeat(file: File): Boolean {
        check(file.isFile) { "Missing lease control" }
        RandomAccessFile(file,"rw").use { control ->
            check(control.length() == 1L) { "Invalid lease control length" }
            val previous=control.read()
            check(previous in '0'.code..'3'.code) { "Invalid lease control value" }
            if (previous == '1'.code) return false
            control.seek(0)
            control.write(if (previous == '2'.code) '3'.code else '2'.code)
            control.fd.sync()
            return true
        }
    }
    @Synchronized fun release(file: File) {
        check(file.isFile) { "Missing lease control" }
        RandomAccessFile(file, "rw").use { control ->
            check(control.length() == 1L) { "Invalid lease control length" }
            check(control.read() in '0'.code..'3'.code) { "Invalid lease control value" }
            // Truncate-and-rewrite has an observable zero-byte interval for the
            // independent native pread. Publish at offset zero without resizing.
            control.seek(0)
            control.write('1'.code)
            control.fd.sync()
        }
    }
}
