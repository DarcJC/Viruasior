package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.vm.DisplaySize
import pro.darc.viruaisor.vm.GuestDisplayProtocol

class GuestDisplayProtocolTest {
    @Test fun parsesSupportedModesAndActualSize() {
        val modes = GuestDisplayProtocol.parseModes("OK MODES 1280 720 1920 1080 1280 720 1024 768")
        assertEquals(DisplaySize(1280,720), modes.current)
        assertEquals(listOf(DisplaySize(1920,1080), DisplaySize(1280,720), DisplaySize(1024,768)), modes.available)
        assertEquals("RESIZE 1024 768", GuestDisplayProtocol.resize(DisplaySize(1024,768)))
    }
    @Test fun rejectsInvalidUnboundedOrInconsistentReplies() {
        for (reply in listOf("OK", "ERROR display-control", "OK MODES 0 720 0 720",
            "OK MODES 1280 720 1920", "OK MODES 1280 720 1920 1080",
            "OK MODES 4096 4096 4096 4096", "OK MODES 1 1 1 1 1 1")) {
            assertThrows(IllegalStateException::class.java) { GuestDisplayProtocol.parseModes(reply) }
        }
        assertThrows(IllegalArgumentException::class.java) { DisplaySize(4096,4096) }
    }
    @Test fun resizeAckMustNameTheActualTarget() {
        val target = DisplaySize(1024,768)
        GuestDisplayProtocol.validateResize("OK SIZE 1024 768", target)
        assertThrows(IllegalStateException::class.java) { GuestDisplayProtocol.validateResize("OK SIZE 1280 720", target) }
    }
}
