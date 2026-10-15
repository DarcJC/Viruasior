package pro.darc.viruaisor.gpu

/** Do not unlink a live lease's control file while native rollback still owns it. */
internal object OverlayLeaseCleanup {
    fun finish(release: () -> Unit, awaitRollback: () -> Boolean, closeRoot: () -> Unit, deleteArtifacts: () -> Unit): Boolean {
        val confirmed = try {
            release()
            awaitRollback()
        } finally {
            closeRoot()
        }
        if (confirmed) deleteArtifacts()
        return confirmed
    }
}
