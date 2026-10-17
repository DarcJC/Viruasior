package pro.darc.viruaisor.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLifecycleCommandsTest {
    @Test
    fun queuedCommandPreventsIdleShutdownUntilEveryCommandCompletes() {
        val commands = PendingLifecycleCommands()

        commands.begin()
        commands.begin()

        assertFalse(commands.complete())
        assertTrue(commands.hasPending())
        assertTrue(commands.complete())
        assertFalse(commands.hasPending())
    }

    @Test(expected = IllegalStateException::class)
    fun completionWithoutMatchingCommandIsRejected() {
        PendingLifecycleCommands().complete()
    }
}
