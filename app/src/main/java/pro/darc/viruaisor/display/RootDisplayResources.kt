package pro.darc.viruaisor.display

/** Resource budget for authenticated display requests; arithmetic must not overflow. */
internal object RootDisplaySize {
    fun validate(width: Int, height: Int) {
        require(width in 1..4096 && height in 1..4096) { "Invalid root display buffer dimensions" }
        require(width.toLong() * height.toLong() <= 4_194_304L) { "Root display pixel budget exceeded" }
    }
}

/** Shared by successful leases and partial construction rollback. Never removes the UI parent. */
internal class RootDisplayCleanup(
    private val hideAndUnparent: () -> Unit,
    private val releaseSurface: () -> Unit,
    private val destroyQueue: () -> Unit,
    private val releaseChild: () -> Unit,
    private val releaseParentHandle: () -> Unit,
) {
    private var closed = false

    @Synchronized
    fun close(stopProducer: () -> Unit = {}) {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (action in listOf(stopProducer, hideAndUnparent, releaseSurface, destroyQueue, releaseChild, releaseParentHandle)) {
            try {
                action()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
