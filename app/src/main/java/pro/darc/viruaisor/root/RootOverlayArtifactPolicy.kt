package pro.darc.viruaisor.root

internal object RootOverlayArtifactPolicy {
    const val CROSVM_PATH = "/apex/com.android.virt/bin/crosvm"
    const val VIRTMGR_PATH = "/apex/com.android.virt/bin/virtmgr"
    const val CROSVM_SHA256 = "e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d"
    const val VIRTMGR_SHA256 = "081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c"
    const val CANDIDATE_LIBRARY = "libvenus_crosvm.so"
    const val CANDIDATE_SHA256 = "a4a71002533174408d0acdbfe3d4980482087bc9a29d0428ac423579bce9029b"
    const val CANDIDATE_BYTES = 13277408L
    const val DESKTOP_LIBRARY = "libvenus_desktop_crosvm.so"
    const val DESKTOP_SHA256 = "c647a739fdf6d0b6da7e74bfe00acfd56456dc132cb9a06fc14633d85c871021"
    const val DESKTOP_BYTES = 14116120L
    fun enforceDesktopCandidate(hash: String, bytes: Long, owner: Int, mode: Int) {
        if (hash != DESKTOP_SHA256 || bytes != DESKTOP_BYTES || owner !in setOf(0,1000) ||
            mode !in setOf(0x81ed,0x816d)) throw SecurityException("Desktop runtime must be the pinned installer-owned executable")
    }
    fun enforceCandidate(hash: String, bytes: Long, owner: Int, mode: Int) {
        if (hash != CANDIDATE_SHA256 || bytes != CANDIDATE_BYTES || owner !in setOf(0, 1000) ||
            mode !in setOf(0x81ed, 0x816d)) {
            throw SecurityException("Candidate must be the pinned installer-owned executable")
        }
    }
    fun enforce(crosvm: String, virtmgr: String) {
        if (crosvm != CROSVM_SHA256 || virtmgr != VIRTMGR_SHA256) {
            throw SecurityException("Stock AVF artifacts do not match the reviewed device baseline")
        }
    }
}
