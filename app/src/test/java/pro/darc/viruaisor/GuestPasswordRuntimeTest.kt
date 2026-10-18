package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.vm.GuestPasswordRuntime as Runtime

class GuestPasswordRuntimeTest {
    @Test fun preventsDuplicateSubmissionAndIgnoresDetachedCompletion() {
        val first=Any()
        val second=Any()
        var completion: ((Boolean)->Unit)?=null
        Runtime.bind(first) { _,done -> completion=done }
        try {
            assertTrue(Runtime.request("test-secret"))
            assertFalse(Runtime.request("second-secret"))
            Runtime.unbind(Any())
            assertEquals(Runtime.Status.Updating,Runtime.status.value)
            Runtime.bind(second) { _,done -> done(true) }
            completion!!(false)
            assertEquals(Runtime.Status.Idle,Runtime.status.value)
            assertTrue(Runtime.request("third-secret"))
            assertEquals(Runtime.Status.Changed,Runtime.status.value)
        } finally { Runtime.unbind(first);Runtime.unbind(second) }
        assertFalse(Runtime.request("detached"))
    }

    @Test fun failedDispatchDoesNotLeaveTheFormStuckUpdating() {
        val owner=Any()
        Runtime.bind(owner) { _,_ -> error("transport unavailable") }
        try {
            assertFalse(Runtime.request("test-secret"))
            assertEquals(Runtime.Status.Failed,Runtime.status.value)
        } finally { Runtime.unbind(owner) }
    }
}
