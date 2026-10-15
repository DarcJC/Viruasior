package pro.darc.viruaisor.root

import org.junit.Assert.*
import org.junit.Test

class RootOverlayArtifactPolicyTest {
    @Test fun onlyAcceptsBothPinnedSystemArtifacts() {
        RootOverlayArtifactPolicy.enforce(RootOverlayArtifactPolicy.CROSVM_SHA256, RootOverlayArtifactPolicy.VIRTMGR_SHA256)
        assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforce("0".repeat(64), RootOverlayArtifactPolicy.VIRTMGR_SHA256) }
        assertThrows(SecurityException::class.java) { RootOverlayArtifactPolicy.enforce(RootOverlayArtifactPolicy.CROSVM_SHA256, "0".repeat(64)) }
    }
}
