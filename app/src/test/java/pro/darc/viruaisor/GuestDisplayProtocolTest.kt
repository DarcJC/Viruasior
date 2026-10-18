package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.vm.DisplaySize
import pro.darc.viruaisor.vm.GuestDisplayProtocol
import pro.darc.viruaisor.vm.DisplayMode
import pro.darc.viruaisor.vm.VmDisplayState

class GuestDisplayProtocolTest {
    @Test fun parsesNativeAndFastModesWithoutInventingRefreshRates() {
        val modes = GuestDisplayProtocol.parseConfigs("OK CONFIGS 1920 1200 119990 1920 1200 119990 3408 2272 60000")
        assertEquals(DisplayMode(DisplaySize(1920,1200),119990),modes.current)
        assertEquals(2,modes.available.size)
        assertEquals("RESIZE_RATE 1920 1200 119990",GuestDisplayProtocol.resize(modes.current))
        assertThrows(IllegalArgumentException::class.java) { DisplayMode(DisplaySize(3408,2272),120000) }
        assertThrows(IllegalStateException::class.java) { GuestDisplayProtocol.parseConfigs("OK CONFIGS 1920 1200 120000 1920 1200 60000") }
    }
    @Test fun firstFrameAndModeAckCanArriveInEitherOrder() {
        val old = DisplayMode(DisplaySize(1280,720),60000)
        val target = DisplayMode(DisplaySize(1920,1200),120000)
        val pending = VmDisplayState(actual=old,presented=old.size,requested=target)
        assertNotNull(pending.withPresented(target.size).requested)
        assertNull(pending.withPresented(target.size).withConfirmed(target).requested)
        assertNotNull(pending.withConfirmed(target).requested)
        assertNull(pending.withConfirmed(target).withPresented(target.size).requested)
    }
    @Test fun refreshOnlyConfirmationDoesNotWaitForANonexistentSizeChange() {
        val slow = DisplayMode(DisplaySize(1920,1200),60000)
        val fast = slow.copy(refreshMilliHz=120000)
        val state=VmDisplayState(actual=slow,presented=slow.size,requested=fast)
        assertFalse(state.switching)
        assertNull(state.withConfirmed(fast).requested)
        assertEquals(fast,state.withConfirmed(fast).actual)
        assertEquals(fast,state.withConfirmed(slow).requested)
    }
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
