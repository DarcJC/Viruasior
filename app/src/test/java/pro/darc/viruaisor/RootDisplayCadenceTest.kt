package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.display.RootDisplayCadence

class RootDisplayCadenceTest {
    private val period = RootDisplayCadence.PERIOD_NANOS

    @Test fun highRefreshLateArrivalCanStillUseTheNextFutureSlot() {
        val cadence = RootDisplayCadence()
        val interval = 8_333_333L
        val now = 1_000_000_000L
        val phase = RootDisplayCadence.Phase(now + interval + 1_000_000L, interval)
        val first = requireNotNull(cadence.plan(now, 1L, phase, interval))
        cadence.submitted(first)
        // A traced producer gap of 13.5 ms still leaves 3.17 ms before this
        // target. Sleeping until the following slot needlessly loses a refresh.
        val arrival = first.submitAtNanos + 13_500_000L
        val next = requireNotNull(cadence.plan(arrival, 1L, phase, interval))
        assertEquals(first.presentAtNanos + interval, next.presentAtNanos)
        assertTrue(next.submitAtNanos <= arrival)
        assertTrue(next.presentAtNanos - arrival >= 1_000_000L)
    }

    @Test fun highRefreshKeepsOneMillisecondOfLeadAndSkipsAnExpiredMargin() {
        val interval = 8_333_333L
        for (lead in listOf(1_000_000L, 999_999L)) {
            val cadence = RootDisplayCadence()
            val now = 1_000_000_000L
            val phase = RootDisplayCadence.Phase(now + interval + 1_000_000L, interval)
            val first = requireNotNull(cadence.plan(now, 1L, phase, interval))
            cadence.submitted(first)
            val target = first.presentAtNanos + interval
            val arrival = target - lead
            val next = requireNotNull(cadence.plan(arrival, 1L, phase, interval))
            assertEquals(if (lead == 1_000_000L) target else target + interval,
                next.presentAtNanos)
            assertTrue(next.presentAtNanos - arrival >= 1_000_000L)
            assertTrue(next.submitAtNanos - arrival <= interval)
        }
    }

    @Test fun nativeProfileKeepsItsExistingHalfPeriodMargin() {
        val now = 1_000_000_000L
        val aligned = RootDisplayCadence.Phase(now + period + 1_000_000L, period / 2)
        for (phase in listOf(null, aligned)) {
            val cadence = RootDisplayCadence()
            val first = requireNotNull(cadence.plan(now, 1L, phase))
            cadence.submitted(first)
            val target = first.presentAtNanos + period
            val next = requireNotNull(cadence.plan(target - 3_000_000L, 1L, phase))
            assertEquals(target + period, next.presentAtNanos)
        }
    }

    @Test fun highRefreshWithoutAnAlignedClockKeepsTheExistingMargin() {
        val interval = 8_333_333L
        for (phase in listOf(null, RootDisplayCadence.Phase(1_010_000_000L, 11_111_111L))) {
            val cadence = RootDisplayCadence()
            val first = requireNotNull(cadence.plan(1_000_000_000L, 1L, phase, interval))
            cadence.submitted(first)
            val target = first.presentAtNanos + interval
            val next = requireNotNull(cadence.plan(target - 3_000_000L, 1L, phase, interval))
            assertEquals(target + interval, next.presentAtNanos)
        }
    }

    @Test fun highRefreshProfileUsesEveryPhysicalTick() {
        val cadence = RootDisplayCadence()
        val interval = 8_333_333L
        var now = 1_000_000_000L
        var expected = now + 6_000_000L
        var previous: Long? = null
        repeat(240) {
            val slot = requireNotNull(cadence.plan(now, 1L,
                RootDisplayCadence.Phase(expected, interval), interval))
            previous?.let { assertEquals(interval, slot.presentAtNanos - it) }
            assertTrue(slot.submitAtNanos - now <= interval)
            assertEquals(0L, Math.floorMod(slot.presentAtNanos + 1_000_000L - expected, interval))
            now = maxOf(now, slot.submitAtNanos) + 3_000_000L
            cadence.submitted(slot)
            previous = slot.presentAtNanos
            expected += interval
        }
    }

    @Test fun fastProducerGetsAnEvenTimelineWithAtMostOnePeriodOfWait() {
        val cadence = RootDisplayCadence()
        var now = 1_000_000_000L
        var previous: Long? = null
        repeat(120) {
            val slot = requireNotNull(cadence.plan(now, 1L))
            assertTrue(slot.submitAtNanos - now <= period)
            now = maxOf(now, slot.submitAtNanos)
            assertTrue(slot.presentAtNanos - now in period / 2..period)
            previous?.let { assertEquals(period, slot.presentAtNanos - it) }
            cadence.submitted(slot)
            previous = slot.presentAtNanos
            now += 6_000_000L // Render the next frame faster than the target cadence.
        }
    }

    @Test fun planningWithoutSuccessfulSubmissionDoesNotConsumeASlot() {
        val cadence = RootDisplayCadence()
        val first = requireNotNull(cadence.plan(1_000_000_000L, 1L))
        cadence.submitted(first)
        val failed = cadence.plan(1_006_000_000L, 1L)
        val retried = cadence.plan(1_007_000_000L, 1L)
        assertEquals(failed, retried)
    }

    @Test fun lateFramesSkipMissedSlotsWithoutLosingTheClockPhase() {
        val cadence = RootDisplayCadence()
        val first = requireNotNull(cadence.plan(1_000_000_000L, 1L))
        cadence.submitted(first)
        val now = first.presentAtNanos + 3 * period + 1_000_000L
        val next = requireNotNull(cadence.plan(now, 1L))
        assertEquals(0L, (next.presentAtNanos - first.presentAtNanos) % period)
        assertTrue(next.presentAtNanos - now >= period / 2)
        assertTrue(next.submitAtNanos - now <= period)
    }

    @Test fun leavingOrChangingTheModeDiscardsTheOldSchedule() {
        val cadence = RootDisplayCadence()
        val first = requireNotNull(cadence.plan(1_000_000_000L, 1L))
        cadence.submitted(first)
        assertNull(cadence.plan(1_001_000_000L, null))
        val resumed = requireNotNull(cadence.plan(1_002_000_000L, 1L))
        assertEquals(1_002_000_000L + period, resumed.presentAtNanos)
        cadence.submitted(resumed)
        val resized = requireNotNull(cadence.plan(1_003_000_000L, 2L))
        assertEquals(1_003_000_000L + period, resized.presentAtNanos)
    }

    @Test fun displayPhaseLeavesAMarginBeforeTheExpectedPhysicalRefresh() {
        val cadence = RootDisplayCadence()
        val now = 1_000_000_000L
        val expected = now + 12_000_000L
        val interval = period / 2
        val slot = requireNotNull(cadence.plan(now, 1L, RootDisplayCadence.Phase(expected, interval)))
        assertEquals(0L, Math.floorMod(slot.presentAtNanos + 1_000_000L - expected, interval))
        assertTrue(slot.presentAtNanos - now >= period / 2)
    }

    @Test fun refreshedPhaseDoesNotSwitchBetweenOddAndEven120HzTicks() {
        val cadence = RootDisplayCadence()
        val interval = period / 2
        var expected = 1_012_000_000L
        var now = 1_000_000_000L
        var previous: Long? = null
        repeat(120) {
            val slot = requireNotNull(cadence.plan(now, 1L, RootDisplayCadence.Phase(expected, interval)))
            assertEquals(0L, Math.floorMod(slot.presentAtNanos + 1_000_000L - expected, interval))
            previous?.let { assertEquals(period, slot.presentAtNanos - it) }
            now = maxOf(now, slot.submitAtNanos)
            cadence.submitted(slot)
            previous = slot.presentAtNanos
            now += 6_000_000L
            expected += if (it % 2 == 0) interval else 3 * interval
        }
    }

    @Test fun incompatibleOrInvalidDisplayClockKeepsThe60HzFallback() {
        val now = 1_000_000_000L
        val expected = RootDisplayCadence().plan(now, 1L)
        for (interval in listOf(0L, -1L, 11_111_111L, Long.MAX_VALUE)) {
            val clock = RootDisplayCadence.Phase(now + 12_000_000L, interval)
            assertEquals(expected, RootDisplayCadence().plan(now, 1L, clock))
        }
    }
}
