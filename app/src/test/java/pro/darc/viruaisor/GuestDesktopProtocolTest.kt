package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.darc.viruaisor.vm.GuestDesktopProtocol
import pro.darc.viruaisor.vm.GuestControlProtocol
import pro.darc.viruaisor.vm.VmStopSequence
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

class VmStopSequenceTest {
    @Test fun stopsAnActiveDesktopBeforeGuestShutdownCanContinue() {
        val calls = mutableListOf<String>()
        assertTrue(VmStopSequence.stopDesktopIfStarted(true) { calls += "desktop-stop" })
        assertEquals(listOf("desktop-stop"), calls)
    }

    @Test fun skipsDesktopStopWhenNoSessionWasStarted() {
        assertFalse(VmStopSequence.stopDesktopIfStarted(false) { error("must not run") })
    }

    @Test fun desktopStopFailureIsNotReportedAsCompleted() {
        val failure = IllegalStateException("stop failed")
        assertEquals(
            failure,
            assertThrows(IllegalStateException::class.java) {
                VmStopSequence.stopDesktopIfStarted(true) { throw failure }
            },
        )
    }
}

class GuestControlProtocolTest {
    @Test fun acceptsTheAgentGreetingBeforeTheCommandResult() {
        GuestControlProtocol.validate("READY", "OK")
    }

    @Test fun rejectsACommandResultConsumedAsTheGreeting() {
        assertThrows(IllegalStateException::class.java) {
            GuestControlProtocol.validate("OK", null)
        }
    }

    @Test fun rejectsAnAgentErrorAfterAValidGreeting() {
        assertThrows(IllegalStateException::class.java) {
            GuestControlProtocol.validate("READY", "ERROR systemctl")
        }
    }
}
