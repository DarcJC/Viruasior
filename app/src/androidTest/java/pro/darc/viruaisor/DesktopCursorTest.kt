package pro.darc.viruaisor

import android.view.MotionEvent
import android.view.InputDevice
import android.view.PointerIcon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.display.DesktopSurfaceView

@RunWith(AndroidJUnit4::class)
class DesktopCursorTest {
    @Test fun fullscreenResolvesOnlyTheGuestCursorAndPreviewRestoresHostCursor() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view=DesktopSurfaceView(instrumentation.targetContext)
            val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_HOVER_MOVE,20f,30f,0).apply {
                source=InputDevice.SOURCE_MOUSE
            }
            val hidden=PointerIcon.getSystemIcon(instrumentation.targetContext,PointerIcon.TYPE_NULL)
            try {
                view.fullscreen=true
                assertEquals(hidden,view.onResolvePointerIcon(event,0))
                view.fullscreen=false
                assertNotEquals(hidden,view.onResolvePointerIcon(event,0))
            } finally { event.recycle();view.releaseInput() }
        }
    }
}
