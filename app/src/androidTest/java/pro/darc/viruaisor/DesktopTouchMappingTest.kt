package pro.darc.viruaisor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import pro.darc.viruaisor.display.DesktopSurfaceView
import pro.darc.viruaisor.display.InputBackendRegistry
import pro.darc.viruaisor.display.VmInputBackend

@RunWith(AndroidJUnit4::class)
class DesktopTouchMappingTest {
    @Test fun letterboxedTouchUsesViewportCoordinatesForAvfRawCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var received: MotionEvent? = null
            val backend = object : VmInputBackend {
                override fun setTouchScale(scale: Float) = Unit
                override fun sendTouch(event: MotionEvent): Boolean {
                    received?.recycle()
                    received = MotionEvent.obtain(event)
                    return true
                }
                override fun sendMouse(event: MotionEvent) = true
                override fun sendTrackpad(event: MotionEvent) = true
                override fun sendKey(scanCode: Short, down: Boolean) = true
            }
            InputBackendRegistry.bind(backend)
            val view = DesktopSurfaceView(instrumentation.targetContext).apply {
                inputWidth = 3408
                inputHeight = 2272
                guestWidth = 1920
                guestHeight = 1200
                layout(0, 71, 3408, 2201)
                fullscreen = true
            }
            try {
                val now = SystemClock.uptimeMillis()
                // InputDispatcher supplies screen raw coordinates with a local view offset.
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                    170.4f, 177.5f, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                    offsetLocation(0f, -71f)
                }
                view.onTouchEvent(event)
                event.recycle()
                assertEquals(170.4f, received!!.rawX, 0.1f)
                assertEquals(113.6f, received!!.rawY, 0.1f)
            } finally {
                view.releaseInput()
                received?.recycle()
                InputBackendRegistry.unbind(backend)
            }
        }
    }
}
