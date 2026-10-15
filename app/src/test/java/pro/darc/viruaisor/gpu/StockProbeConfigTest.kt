package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class StockProbeConfigTest {
    @Test fun disklessFixtureHaltsOnExpectedNoRootfsPanic() {
        assertEquals(listOf("panic=0"), StockProbeConfig.kernelParams.filter { it.startsWith("panic=") })
        assertTrue("console=ttyS0,115200" in StockProbeConfig.kernelParams)
        assertTrue(StockProbeConfig.kernelParams.none { it.startsWith("root=") || it.startsWith("init=") })
    }
}
