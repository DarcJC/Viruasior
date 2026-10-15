package pro.darc.viruaisor.root

internal object RootOverlayArtifactPolicy {
    const val CROSVM_PATH = "/apex/com.android.virt/bin/crosvm"
    const val VIRTMGR_PATH = "/apex/com.android.virt/bin/virtmgr"
    const val CROSVM_SHA256 = "e2855ffd29db9826431af49436a66324fde5a1c2179549c480a663ff1ca60e2d"
    const val VIRTMGR_SHA256 = "081f600e64780dbfd8d17681d4bd36813fb5dc1e86c268452932a028eef5a78c"
    fun enforce(crosvm: String, virtmgr: String) {
        if (crosvm != CROSVM_SHA256 || virtmgr != VIRTMGR_SHA256) {
            throw SecurityException("Stock AVF artifacts do not match the reviewed device baseline")
        }
    }
}
