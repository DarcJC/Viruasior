package pro.darc.viruaisor.display

/** The caller serializes planning, waits for submission, then records a successful apply. */
internal class RootDisplayCadence {
    data class Slot(val submitAtNanos: Long, val presentAtNanos: Long)
    data class Phase(val expectedPresentationNanos: Long, val intervalNanos: Long)
    private var mode: Long? = null
    private var lastTarget: Long? = null

    fun plan(nowNanos: Long, mode: Long?, phase: Phase? = null): Slot? {
        if (this.mode != mode) {
            this.mode = mode
            lastTarget = null
        }
        if (mode == null) return null
        val interval = phase?.intervalNanos ?: 0L
        val ticksPerFrame = if (interval in 1_000_000L..17_000_000L)
            (PERIOD_NANOS + interval / 2) / interval else 0L
        val aligned = ticksPerFrame > 0 &&
            kotlin.math.abs(ticksPerFrame * interval - PERIOD_NANOS) <= PERIOD_NANOS / 100
        val period = if (aligned) ticksPerFrame * interval else PERIOD_NANOS
        var target = lastTarget?.plus(period) ?: (nowNanos + period)
        if (aligned && phase != null) {
            val anchor = phase.expectedPresentationNanos - 1_000_000L
            target = anchor + Math.floorDiv(target - anchor + interval / 2, interval) * interval
        }
        val earliest = nowNanos + period / 2
        if (target < earliest) {
            // Skip missed slots while retaining the phase; never queue stale frames.
            target += ((earliest - target + period - 1) / period) * period
        }
        return Slot(target - period, target)
    }

    fun submitted(slot: Slot) { lastTarget = slot.presentAtNanos }

    companion object {
        const val PERIOD_NANOS = 1_000_000_000L / 60
    }
}
