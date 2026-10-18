package pro.darc.viruaisor

import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.display.RootDisplayBuffers
import pro.darc.viruaisor.display.retainHardwareBuffer

@RunWith(AndroidJUnit4::class)
class RootHardwareBufferReferenceTest {
    private fun allocate() = HardwareBuffer.create(16, 16, HardwareBuffer.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)

    @Test fun closingParcelReceiverDoesNotInvalidateRetainedBuffer() {
        val buffers = RootDisplayBuffers<HardwareBuffer>(4)
        val source = allocate()
        try {
            val received = retainHardwareBuffer(source)
            val id = received.id
            assertEquals(source.id, id)
            val retained = buffers.get(id, 16, 16) { retainHardwareBuffer(received) }
            received.close()
            assertFalse(retained.isClosed)
            assertEquals(id, retained.id)
            retainHardwareBuffer(source).use { next ->
                assertSame(retained, buffers.get(next.id, 16, 16) { error("Must reuse") })
            }
            buffers.close()
            assertTrue(retained.isClosed)
            assertFalse(source.isClosed)
        } finally {
            buffers.close()
            source.close()
        }
    }

    @Test fun sameSizeAllocationsHaveIndependentCacheEntries() {
        val buffers = RootDisplayBuffers<HardwareBuffer>(4)
        allocate().use { first ->
            allocate().use { second ->
                try {
                    assertNotEquals(first.id, second.id)
                    val a = buffers.get(first.id, 16, 16) { retainHardwareBuffer(first) }
                    val b = buffers.get(second.id, 16, 16) { retainHardwareBuffer(second) }
                    assertNotSame(a, b)
                    assertNotEquals(a.id, b.id)
                } finally { buffers.close() }
            }
        }
    }
}
