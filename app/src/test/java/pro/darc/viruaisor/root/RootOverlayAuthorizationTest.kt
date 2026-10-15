package pro.darc.viruaisor.root

import org.junit.Assert.*
import org.junit.Test

class RootOverlayAuthorizationTest {
    @Test fun stockExecutionIsASeparateFixedCapability() {
        val token = "0123456789abcdef0123456789abcdef"
        assertTrue(RootOverlayAuthorization.validRequest(token, "stock-exec"))
        assertEquals(RootOverlayAuthorization.STOCK_EXEC, RootOverlayAuthorization.transactionFor("stock-exec"))
        RootOverlayAuthorization(10278).enforce(10278, RootOverlayAuthorization.STOCK_EXEC)
        assertThrows(SecurityException::class.java) { RootOverlayAuthorization.transactionFor("candidate-exec") }
    }
    @Test fun acceptsOnlyExactFullUidAndFixedCapability() {
        val auth = RootOverlayAuthorization(10278)
        auth.enforce(10278, RootOverlayAuthorization.PROBE)
        assertThrows(SecurityException::class.java) { auth.enforce(1010278, RootOverlayAuthorization.PROBE) }
        assertThrows(SecurityException::class.java) { auth.enforce(0, RootOverlayAuthorization.PROBE) }
        assertThrows(SecurityException::class.java) { auth.enforce(10278, 99) }
        assertThrows(SecurityException::class.java) { RootOverlayAuthorization(0).enforce(0, RootOverlayAuthorization.PROBE) }
        RootOverlayAuthorization(1010278).enforce(1010278, RootOverlayAuthorization.PROBE)
    }
    @Test fun rejectsPathsAndUnknownFaultModes() {
        assertTrue(RootOverlayAuthorization.validRequest("0123456789abcdef0123456789abcdef", "normal"))
        assertFalse(RootOverlayAuthorization.validRequest("../../system", "normal"))
        assertFalse(RootOverlayAuthorization.validRequest("0123456789abcdef0123456789abcdef", "mount-system"))
        assertFalse(RootOverlayAuthorization.validRequest("0123456789abcdef0123456789abcdeF", "normal"))
        for (mode in listOf("timeout", "client-death", "helper-death")) {
            assertTrue(RootOverlayAuthorization.validRequest("0123456789abcdef0123456789abcdef", mode))
        }
    }
}
