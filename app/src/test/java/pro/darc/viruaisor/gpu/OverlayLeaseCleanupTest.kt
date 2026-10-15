package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class OverlayLeaseCleanupTest {
    @Test fun waitsForRollbackBeforeDeletingControlEvenAfterVmFailure() {
        val calls = mutableListOf<String>()
        assertTrue(OverlayLeaseCleanup.finish({ calls.add("release") }, { calls.add("rollback"); true },
            { calls.add("close") }, { calls.add("delete") }))
        assertEquals(listOf("release", "rollback", "close", "delete"), calls)
    }
    @Test fun retainsArtifactsWhenRollbackIsUnconfirmed() {
        val calls = mutableListOf<String>()
        assertFalse(OverlayLeaseCleanup.finish({ calls.add("release") }, { calls.add("rollback"); false },
            { calls.add("close") }, { calls.add("delete") }))
        assertEquals(listOf("release", "rollback", "close"), calls)
    }
    @Test fun stillClosesRootButDoesNotDeleteOnReleaseFailure() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            OverlayLeaseCleanup.finish({ error("release failed") }, { true }, { calls.add("close") }, { calls.add("delete") })
        }
        assertEquals(listOf("close"), calls)
    }
}
