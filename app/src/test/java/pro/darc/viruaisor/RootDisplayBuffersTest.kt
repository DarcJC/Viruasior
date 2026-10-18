package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.display.RootDisplayBuffers

class RootDisplayBuffersTest {
    private class Reference(val onClose: () -> Unit = {}) : AutoCloseable {
        var closes = 0
        override fun close() { closes++; onClose() }
    }

    @Test fun repeatedBufferPreservesTheNativeReference() {
        val buffers = RootDisplayBuffers<Reference>(4)
        val first = buffers.get(7, 1920, 1200) { Reference() }
        val repeated = buffers.get(7, 1920, 1200) { Reference() }
        assertSame(first, repeated)
        assertEquals(0, first.closes)
        buffers.close()
        assertEquals(1, first.closes)
    }

    @Test fun recentBuffersSurviveBoundedEviction() {
        val buffers = RootDisplayBuffers<Reference>(2)
        val first = buffers.get(1, 1920, 1200) { Reference() }
        val second = buffers.get(2, 1920, 1200) { Reference() }
        buffers.get(1, 1920, 1200) { error("Must reuse") }
        val third = buffers.get(3, 1920, 1200) { Reference() }
        assertEquals(0, first.closes)
        assertEquals(1, second.closes)
        buffers.close()
        assertEquals(1, first.closes)
        assertEquals(1, third.closes)
    }

    @Test fun resizeReleasesOldResolutionBeforeRetainingNewBuffers() {
        val buffers = RootDisplayBuffers<Reference>(4)
        val old = buffers.get(1, 3408, 2272) { Reference() }
        val current = buffers.get(2, 1920, 1280) {
            assertEquals(1, old.closes)
            Reference()
        }
        buffers.close()
        assertEquals(1, old.closes)
        assertEquals(1, current.closes)
    }

    @Test fun failedImportDoesNotEvictExistingReferences() {
        val buffers = RootDisplayBuffers<Reference>(1)
        val old = buffers.get(1, 1920, 1200) { Reference() }
        assertThrows(IllegalStateException::class.java) {
            buffers.get(2, 1920, 1200) { error("Import failed") }
        }
        assertEquals(0, old.closes)
        buffers.close()
        assertEquals(1, old.closes)
    }

    @Test fun leavingRetainedModeReleasesReferencesWithoutClosingTheSession() {
        val buffers = RootDisplayBuffers<Reference>(4)
        val first = buffers.get(1, 3408, 2272) { Reference() }
        buffers.clear()
        buffers.clear()
        assertEquals(1, first.closes)
        val next = buffers.get(1, 3408, 2272) { Reference() }
        assertNotSame(first, next)
        assertEquals(0, next.closes)
        buffers.close()
        assertEquals(1, first.closes)
        assertEquals(1, next.closes)
    }

    @Test fun closeAttemptsEveryReleaseAndPermanentlyClosesTheCache() {
        val buffers = RootDisplayBuffers<Reference>(4)
        val expected = IllegalStateException("Release failed")
        val first = buffers.get(1, 1920, 1200) { Reference { throw expected } }
        val second = buffers.get(2, 1920, 1200) { Reference() }
        assertSame(expected, assertThrows(IllegalStateException::class.java) { buffers.close() })
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        buffers.close()
        assertThrows(IllegalStateException::class.java) {
            buffers.get(3, 1920, 1200) { error("Closed cache must not import") }
        }
    }
}
