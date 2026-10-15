package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class StockProbeSessionTest {
    @Test fun onlyFixedRunningVmFaultsAreAccepted() {
        assertTrue(StockProbeConfig.validFault("none"))
        assertTrue(StockProbeConfig.validFault("owner-death"))
        assertTrue(StockProbeConfig.validFault("lease-timeout"))
        assertFalse(StockProbeConfig.validFault("kill-default"))
    }
    @Test fun recoveryRequiresOurExactSessionMarker() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(token, StockProbeConfig.recoveryToken("stock-probe-v1:$token\n"))
        for (value in listOf("", "default", "stock-probe-v1:../../default\n", "stock-probe-v2:$token\n", "stock-probe-v1:$token\nextra")) {
            assertNull(StockProbeConfig.recoveryToken(value))
        }
    }
}
