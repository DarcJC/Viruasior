package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import pro.darc.viruaisor.display.RootDisplayCleanup
import pro.darc.viruaisor.display.RootDisplaySize

class RootDisplayResourcesTest {
    @Test fun fixedGuestBufferIsWithinBudget() {
        RootDisplaySize.validate(1280, 720)
    }

    @Test fun nativeTabletDisplayFitsTheBoundedBudget() {
        org.junit.Assert.assertTrue(runCatching { RootDisplaySize.validate(3408,2272) }.isSuccess)
    }

    @Test fun invalidAndOversizedDimensionsFailClosed() {
        listOf(0 to 720, 1280 to 0, -1 to 720, 1280 to -1,
            4097 to 1, 1 to 4097, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { RootDisplaySize.validate(w, h) }
        }
    }

    @Test fun cleanupIsOrderedAndRunsOnlyOnce() {
        val events = mutableListOf<String>()
        val cleanup = RootDisplayCleanup(
            hideAndUnparent = { events += "child-detach" },
            releaseSurface = { events += "surface" },
            destroyQueue = { events += "queue" },
            releaseChild = { events += "child" },
            releaseParentHandle = { events += "parent-handle" },
        )
        cleanup.close { events += "producer-stop" }
        cleanup.close { events += "must-not-run" }
        assertEquals(listOf("producer-stop", "child-detach", "surface", "queue", "child", "parent-handle"), events)
    }

    @Test fun failureAtEveryStageStillAttemptsAllLaterCleanup() {
        for (failedStep in 0..5) {
            val events = mutableListOf<Int>()
            val expected = IllegalStateException("stage $failedStep")
            fun action(step: Int): () -> Unit = {
                events += step
                if (step == failedStep) throw expected
            }
            val cleanup = RootDisplayCleanup(action(1), action(2), action(3), action(4), action(5))
            val actual = assertThrows(IllegalStateException::class.java) { cleanup.close(action(0)) }
            assertSame(expected, actual)
            assertEquals((0..5).toList(), events)
            cleanup.close { error("closed lease must not run again") }
        }
    }

    @Test fun allCleanupErrorsAreRetained() {
        val first = IllegalStateException("producer")
        val second = IllegalStateException("queue")
        val cleanup = RootDisplayCleanup({}, {}, { throw second }, {}, {})
        val actual = assertThrows(IllegalStateException::class.java) { cleanup.close { throw first } }
        assertSame(first, actual)
        assertSame(second, actual.suppressed.single())
    }
}
