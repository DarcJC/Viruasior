package pro.darc.viruaisor.display

/** The caller serializes planning, waits for submission, then records a successful apply. */
internal class RootDisplayCadence {
    data class Slot(val submitAtNanos: Long, val presentAtNanos: Long)
    data class Phase(val expectedPresentationNanos: Long, val intervalNanos: Long)
    private var mode: Long? = null
    private var lastTarget: Long? = null
    private var targetPeriod = PERIOD_NANOS

    fun plan(nowNanos: Long, mode: Long?, phase: Phase? = null, targetPeriodNanos: Long = PERIOD_NANOS): Slot? {
        require(targetPeriodNanos > 0)
        if (this.mode != mode || targetPeriod != targetPeriodNanos) {
            this.mode = mode
            targetPeriod = targetPeriodNanos
            lastTarget = null
        }
        if (mode == null) return null
        val interval = phase?.intervalNanos ?: 0L
        val ticksPerFrame = if (interval in 1_000_000L..17_000_000L)
            (targetPeriod + interval / 2) / interval else 0L
        val aligned = ticksPerFrame > 0 &&
            kotlin.math.abs(ticksPerFrame * interval - targetPeriod) <= targetPeriod / 100
        val period = if (aligned) ticksPerFrame * interval else targetPeriod
        var target = lastTarget?.plus(period) ?: (nowNanos + period)
        if (aligned && phase != null) {
            val anchor = phase.expectedPresentationNanos - 1_000_000L
            target = anchor + Math.floorDiv(target - anchor + interval / 2, interval) * interval
        }
        // A frame already at the sink can use a still-future high-refresh slot.
        // Keep the existing native/fallback margin and never target the past.
        val minimumLead = if (aligned && targetPeriodNanos < PERIOD_NANOS)
            minOf(period / 2, 1_000_000L) else period / 2
        val earliest = nowNanos + minimumLead
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
