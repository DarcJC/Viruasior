package pro.darc.viruaisor.root

import java.util.concurrent.TimeUnit

/** The native child's pidfd must get a chance to finish owner-death rollback. */
internal object RootOverlayChildCleanup {
    fun finish(process: Process) {
        try {
            if (process.waitFor(3, TimeUnit.SECONDS)) return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process.destroyForcibly()
    }
}
