package pro.darc.viruaisor.root

import org.junit.Assert.*
import org.junit.Test

class VenusCandidatePolicyTest {
    @Test fun rejectsPrivilegedOrWritableCandidateModes() {
        val hash = RootOverlayArtifactPolicy.CANDIDATE_SHA256
        val size = RootOverlayArtifactPolicy.CANDIDATE_BYTES
        RootOverlayArtifactPolicy.enforceCandidate(hash, size, 1000, 0x81ed)
        RootOverlayArtifactPolicy.enforceCandidate(hash, size, 0, 0x816d)
        for (mode in listOf(0x89ed, 0x85ed, 0x81ff, 0x41ed)) {
            assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforceCandidate(hash, size, 1000, mode) }
        }
        assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforceCandidate("0".repeat(64), size, 1000, 0x81ed) }
        assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforceCandidate(hash, size + 1, 1000, 0x81ed) }
        assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforceCandidate(hash, size, 10278, 0x81ed) }
    }
    @Test fun candidateIsOneFixedCapabilityNotACallerSuppliedExecutable() {
        val token = "0123456789abcdef0123456789abcdef"
        assertTrue(RootOverlayAuthorization.validRequest(token, "venus-exec"))
        assertEquals(3, RootOverlayAuthorization.transactionFor("venus-exec"))
        RootOverlayAuthorization(10278).enforce(10278, 3)
        assertFalse(RootOverlayAuthorization.validRequest(token, "venus-exec:/data/local/tmp/crosvm"))
        assertThrows(SecurityException::class.java) { RootOverlayAuthorization(10278).enforce(0, 3) }
    }
}
