package pro.darc.viruaisor.gpu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ProbeOutputDrainTest {
    @Test fun keepsOutputBelowLimit() {
        val input = ByteArrayInputStream("guest console\n".toByteArray())
        val output = ByteArrayOutputStream()
        assertEquals(14L, ProbeOutputDrain.copy(input, output, 256))
        assertEquals("guest console\n", output.toString("UTF-8"))
        assertEquals(0, input.available())
    }
    @Test fun drainsOverflowWithoutRetainingIt() {
        val bytes = ByteArray(20000) { (it % 127).toByte() }
        val input = ByteArrayInputStream(bytes)
        val output = ByteArrayOutputStream()
        assertEquals(20000L, ProbeOutputDrain.copy(input, output, 17))
        assertArrayEquals(bytes.copyOf(17), output.toByteArray())
        assertEquals(0, input.available())
    }
    @Test fun zeroLimitStillDrains() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        assertEquals(3L, ProbeOutputDrain.copy(input, output, 0))
        assertEquals(0, output.size())
        assertEquals(0, input.available())
    }
    @Test fun rejectsNegativeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeOutputDrain.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), -1)
        }
    }
}
