package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.display.DesktopPointerPolicy

class DesktopPointerPolicyTest {
    @Test fun capturedRawTouchpadIsNotConvertedIntoRelativeMouse() {
        assertTrue(DesktopPointerPolicy.isTrackpad(0x00100008,true))
        assertFalse(DesktopPointerPolicy.isTrackpad(0x00100008,false))
        assertFalse(DesktopPointerPolicy.isTrackpad(0x00020004,true))
        assertFalse(DesktopPointerPolicy.isTrackpad(0x00002002,true))
    }
    @Test fun refreshRequestChoosesOnlySameResolutionAtOrBelowTarget() {
        val modes=listOf(DesktopPointerPolicy.ScreenMode(1,3408,2272,60f),
            DesktopPointerPolicy.ScreenMode(2,3408,2272,120f),
            DesktopPointerPolicy.ScreenMode(3,3408,2272,144f),
            DesktopPointerPolicy.ScreenMode(4,1920,1200,120f))
        assertEquals(2,DesktopPointerPolicy.preferredMode(modes,3408,2272,120f)?.id)
        assertEquals(1,DesktopPointerPolicy.preferredMode(modes,3408,2272,60f)?.id)
        assertEquals(1,DesktopPointerPolicy.preferredMode(modes,3408,2272,59.88f)?.id)
        assertEquals(2,DesktopPointerPolicy.preferredMode(modes,3408,2272,119.88f)?.id)
        assertNull(DesktopPointerPolicy.preferredMode(modes,1000,1000,120f))
    }
}
