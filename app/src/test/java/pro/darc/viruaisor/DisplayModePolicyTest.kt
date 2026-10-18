package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.vm.DisplayMode
import pro.darc.viruaisor.vm.DisplaySize
import pro.darc.viruaisor.vm.DisplayModePolicy

class DisplayModePolicyTest {
    private val native = DisplaySize(3408,2272)
    private fun mode(w: Int,h: Int,rate: Int=120000)=DisplayMode(DisplaySize(w,h),rate)
    @Test fun defaultChoosesMatching1920AspectAndHighestSupportedRate() {
        val fitted=mode(1920,1280)
        assertEquals(fitted, DisplayModePolicy.preferred(native,listOf(
            mode(1920,1200),mode(3408,2272,60000),mode(1920,1280,60000),fitted)))
    }
    @Test fun oldGuestKeepsNativeWhenMatchingDesktopModeIsAbsent() {
        val original=mode(3408,2272,60000)
        assertEquals(original,DisplayModePolicy.preferred(native,listOf(mode(1920,1200),original)))
    }
    @Test fun ratioDetectionDoesNotMistakeSixteenTenForThreeTwo() {
        assertTrue(DisplayModePolicy.fits(native,DisplaySize(1920,1280)))
        assertFalse(DisplayModePolicy.fits(native,DisplaySize(1920,1200)))
    }
}
