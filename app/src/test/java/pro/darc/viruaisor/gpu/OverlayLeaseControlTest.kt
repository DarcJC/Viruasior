package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OverlayLeaseControlTest {
    @Test fun preservesOneByteControlWhilePublishingRelease() {
        val file = Files.createTempFile("venus-control", ".tmp").toFile()
        try {
            file.writeText("0")
            OverlayLeaseControl.release(file)
            assertEquals("1", file.readText())
            assertEquals(1L, file.length())
            OverlayLeaseControl.release(file)
            assertEquals("1", file.readText())
        } finally { file.delete() }
    }
    @Test fun malformedControlsAreNeverTruncatedOrReplaced() {
        val file = Files.createTempFile("venus-control-invalid", ".tmp").toFile()
        try {
            for (value in listOf("", "00", "x")) {
                file.writeText(value)
                assertThrows(IllegalStateException::class.java) { OverlayLeaseControl.release(file) }
                assertEquals(value, file.readText())
            }
        } finally { file.delete() }
    }
}
