package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class DisposableProbeExitTest {
    @Test fun terminatesSynchronouslyWithoutWaitingForAnotherMainLoopTick() {
        val calls = mutableListOf<String>()
        DisposableProbeExit.finish({ calls.add("foreground") }, { calls.add("service") }, { calls.add("exit") })
        assertEquals(listOf("foreground", "service", "exit"), calls)
    }
    @Test fun stillTerminatesWhenServiceTeardownThrows() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            DisposableProbeExit.finish({ calls.add("foreground") }, { error("stop failed") }, { calls.add("exit") })
        }
        assertEquals(listOf("foreground", "exit"), calls)
    }
}
