package pro.darc.viruaisor.display

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceView
import android.hardware.input.InputManager

/** Touch uses the boot-time virtio ABS range; relative mouse deltas use the current guest mode. */
internal class DesktopSurfaceView(context: Context) : SurfaceView(context) {
    var fullscreen = false
        set(value) {
            if (field == value) return
            field = value
            // A null object means "inherit", not "hide". Keep only the guest
            // cursor even during a brief pointer-capture transition.
            pointerIcon=if(value) PointerIcon.getSystemIcon(context,PointerIcon.TYPE_NULL) else null
            if (!value) releaseInput()
            else post { requestFocus(); requestDesktopPointerCapture() }
        }
    var enterFullscreen: () -> Unit = {}
    var guestWidth = 1280
    var guestHeight = 720
    var inputWidth = 1280
    var inputHeight = 720
    var inputEnabled = true
        set(value) {
            if(field == value) return
            if(!value) releaseInput()
            field=value
            // A modeset releases capture but does not change window focus or
            // fullscreen state. Reacquire it when input becomes ready again.
            if(value && fullscreen) post { requestDesktopPointerCapture() }
        }
    private val trackpad=DesktopTrackpad(InputBackendRegistry::sendTrackpad)
    private var capturedSource: Pair<Int,Int>?=null
    private val inputManager=context.getSystemService(InputManager::class.java)
    private val inputListener=object: InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { post { requestDesktopPointerCapture() } }
        override fun onInputDeviceChanged(id: Int) { trackpad.deviceRemoved(id) }
        override fun onInputDeviceRemoved(id: Int) { trackpad.deviceRemoved(id) }
    }
    private var absolute: Pair<Float, Float>? = null
    private var touch: MotionEvent? = null
    private val buttons = linkedSetOf<Int>()
    private val buttonEvents = mutableMapOf<Int,MotionEvent>()
    private val remainder = FloatArray(4)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setZOrderOnTop(true)
        setBackgroundColor(android.graphics.Color.BLACK)
        requestUnbufferedDispatch(InputDevice.SOURCE_ANY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        if (!fullscreen) {
            if (event.actionMasked == MotionEvent.ACTION_UP) { performClick(); enterFullscreen() }
            return true
        }
        requestFocus()
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return pointer(event, relative = false)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { releaseInput(); return true }
        if (width <= 0 || height <= 0) return true
        // Rebuild from local coordinates: transform/offsetLocation leave rawX/Y
        // in screen space, while some AVF versions consume raw coordinates.
        val properties=Array(event.pointerCount) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(i,it) } }
        fun coordinates(history: Int) = Array(event.pointerCount) { i ->
            MotionEvent.PointerCoords().also {
                if (history < 0) event.getPointerCoords(i,it) else event.getHistoricalPointerCoords(i,history,it)
                it.x=(it.x*inputWidth/width).coerceIn(0f,(inputWidth-1).toFloat())
                it.y=(it.y*inputHeight/height).coerceIn(0f,(inputHeight-1).toFloat())
            }
        }
        val history=if(event.actionMasked == MotionEvent.ACTION_MOVE) event.historySize else 0
        val mapped=MotionEvent.obtain(event.downTime,
            if(history > 0) event.getHistoricalEventTime(0) else event.eventTime,event.action,
            event.pointerCount,properties,coordinates(if(history > 0) 0 else -1),
            event.metaState,event.buttonState,event.xPrecision,event.yPrecision,
            event.deviceId,event.edgeFlags,event.source,event.flags)
        for(index in 1 until history) mapped.addBatch(event.getHistoricalEventTime(index),coordinates(index),event.metaState)
        if(history > 0) mapped.addBatch(event.eventTime,coordinates(-1),event.metaState)
        InputBackendRegistry.setTouchScale(1f)
        InputBackendRegistry.sendTouch(mapped)
        touch?.recycle()
        touch = if (event.actionMasked == MotionEvent.ACTION_UP) null
            else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                copyActiveTouch(mapped,event.getPointerId(event.actionIndex))
            } else MotionEvent.obtain(mapped)
        mapped.recycle()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun copyActiveTouch(event: MotionEvent,lifted: Int): MotionEvent? {
        val indices=(0 until event.pointerCount).filter { event.getPointerId(it) != lifted }
        if (indices.isEmpty()) return null
        val properties=Array(indices.size) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(indices[i],it) } }
        val coordinates=Array(indices.size) { i -> MotionEvent.PointerCoords().also { event.getPointerCoords(indices[i],it) } }
        return MotionEvent.obtain(event.downTime,event.eventTime,MotionEvent.ACTION_MOVE,indices.size,
            properties,coordinates,event.metaState,event.buttonState,event.xPrecision,event.yPrecision,
            event.deviceId,event.edgeFlags,event.source,event.flags)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        if (fullscreen && (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_TOUCHPAD))) {
            if(!hasPointerCapture()) requestDesktopPointerCapture()
            return pointer(event, relative = false)
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        if (!fullscreen) return false
        if(capturedSource != (event.deviceId to event.source)) {
            capturedSource=event.deviceId to event.source
            android.util.Log.i("ViruaisorInput","CAPTURED_INPUT device=${event.deviceId} source=${event.source.toString(16)} trackpad=${DesktopPointerPolicy.isTrackpad(event.source,true)}")
        }
        if (DesktopPointerPolicy.isTrackpad(event.source,true)) { trackpad.forward(event);return true }
        return pointer(event,relative=true)
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        android.util.Log.i("ViruaisorInput","POINTER_CAPTURE enabled=$hasCapture fullscreen=$fullscreen input=$inputEnabled")
        absolute = null
        if (!hasCapture) { releaseButtons();trackpad.release() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseInput()
        else if (fullscreen) post { if (hasWindowFocus()) requestDesktopPointerCapture() }
    }

    private fun requestDesktopPointerCapture() {
        if(!fullscreen || !inputEnabled || !isAttachedToWindow || !hasWindowFocus() || hasPointerCapture()) return
        requestFocus()
        // API 37's no-argument overload defaults to relative gesture emulation.
        requestPointerCapture(android.view.View.POINTER_CAPTURE_MODE_ABSOLUTE)
    }

    fun resumePointerCapture() {
        if(fullscreen && inputEnabled && !hasPointerCapture()) post { requestDesktopPointerCapture() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        inputManager.registerInputDeviceListener(inputListener,handler)
        post { requestDesktopPointerCapture() }
    }
    override fun onDetachedFromWindow() {
        inputManager.unregisterInputDeviceListener(inputListener)
        releaseInput();super.onDetachedFromWindow()
    }

    private fun pointer(event: MotionEvent, relative: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                for (index in 0 until event.historySize) move(event.getHistoricalX(index),event.getHistoricalY(index),relative)
                move(event.x,event.y,relative)
            }
            // Android delivers BUTTON_PRESS/RELEASE separately; preserve the real actionButton.
            MotionEvent.ACTION_DOWN -> Unit
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseButtons()
            MotionEvent.ACTION_BUTTON_PRESS -> button(event.actionButton, true,event)
            MotionEvent.ACTION_BUTTON_RELEASE -> button(event.actionButton, false,event)
            MotionEvent.ACTION_SCROLL -> {
                val x = whole(2, event.getAxisValue(MotionEvent.AXIS_HSCROLL))
                val y = whole(3, event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                // AVF handles only one nonzero scroll axis per event.
                if (x != 0) mouse(MotionEvent.ACTION_SCROLL, scrollX = x.toFloat())
                if (y != 0) mouse(MotionEvent.ACTION_SCROLL, scrollY = y.toFloat())
            }
            MotionEvent.ACTION_HOVER_EXIT -> absolute = null
        }
        return true
    }

    private fun move(currentX: Float,currentY: Float,relative: Boolean) {
        val previous = absolute
        val dx = if (relative) currentX else previous?.let { currentX - it.first } ?: 0f
        val dy = if (relative) currentY else previous?.let { currentY - it.second } ?: 0f
        absolute = currentX to currentY
        val x = whole(0, dx*guestWidth/width.coerceAtLeast(1))
        val y = whole(1, dy*guestHeight/height.coerceAtLeast(1))
        if (x != 0 || y != 0) mouse(MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat())
    }

    private fun whole(axis: Int, delta: Float): Int {
        if (!delta.isFinite()) return 0
        remainder[axis] += delta
        val value = remainder[axis].toInt()
        remainder[axis] -= value
        return value
    }

    private fun button(button: Int, down: Boolean,source: MotionEvent?=null) {
        if (button !in setOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY)) return
        if (if (down) buttons.add(button) else buttons.remove(button)) {
            val cached=buttonEvents.remove(button)
            val event=(source ?: cached)?.let { MotionEvent.obtain(it) }
            cached?.recycle()
            if (event != null) {
                event.action=if (down) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE
                InputBackendRegistry.sendMouse(event)
                if (down) buttonEvents[button]=event else event.recycle()
            }
        }
    }

    private fun mouse(action: Int, x: Float = 0f, y: Float = 0f,
                      scrollX: Float = 0f, scrollY: Float = 0f) {
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, scrollX)
            setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY)
        }
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(props), arrayOf(coords), 0,
            buttons.fold(0) { mask, value -> mask or value }, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        InputBackendRegistry.sendMouse(event)
        event.recycle()
    }

    private fun releaseButtons() { buttons.toList().forEach { button(it, false) } }

    fun releaseInput() {
        trackpad.release()
        if (hasPointerCapture()) releasePointerCapture()
        releaseButtons()
        touch?.let {
            // API 37's AVF touch adapter does not handle ACTION_CANCEL. End each active slot.
            for (index in it.pointerCount-1 downTo 1) {
                it.action=MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                InputBackendRegistry.sendTouch(it)
            }
            it.action = MotionEvent.ACTION_UP
            InputBackendRegistry.sendTouch(it)
            it.recycle()
        }
        touch = null
        absolute = null
        remainder.fill(0f)
    }
}
