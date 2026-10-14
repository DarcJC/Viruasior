package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.darc.viruaisor.vm.GuestDesktopProtocol
import pro.darc.viruaisor.vm.VmGpuProfile

class GuestDesktopProtocolTest {
    @Test fun startCarriesTheParsedGpuProfile() {
        assertEquals("START_DESKTOP 2d", GuestDesktopProtocol.start(VmGpuProfile.SOFTWARE_2D))
        assertEquals("START_DESKTOP gfxstream", GuestDesktopProtocol.start(VmGpuProfile.GFXSTREAM))
    }

    @Test fun stopCarriesTheSameParsedGpuProfile() {
        assertEquals("STOP_DESKTOP 2d", GuestDesktopProtocol.stop(VmGpuProfile.SOFTWARE_2D))
        assertEquals("STOP_DESKTOP gfxstream", GuestDesktopProtocol.stop(VmGpuProfile.GFXSTREAM))
    }
}
