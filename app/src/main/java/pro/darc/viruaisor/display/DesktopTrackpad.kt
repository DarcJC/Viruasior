package pro.darc.viruaisor.display

import android.view.InputDevice
import android.view.MotionEvent

/** Converts raw captured touchpad coordinates into the paired AVF trackpad, never screen pixels. */
internal class DesktopTrackpad(private val send: (MotionEvent) -> Boolean) {
    private val contacts=TrackpadContacts()
    private var last: MotionEvent?=null
    private var button: MotionEvent?=null
    private var deviceId: Int?=null

    fun deviceRemoved(id: Int) { if (deviceId == id) release() }

    fun forward(event: MotionEvent): Boolean {
        if (deviceId != null && deviceId != event.deviceId) release()
        deviceId=event.deviceId
        val action=event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { release(); return true }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            button?.recycle()
            button=if(action == MotionEvent.ACTION_BUTTON_PRESS) MotionEvent.obtain(event) else null
            return send(event)
        }
        if (action !in setOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP)) return false
        if (action == MotionEvent.ACTION_DOWN && last != null) release()
        val ids=(0 until event.pointerCount).map { event.getPointerId(it) }
        if (contacts.ids.any { it !in ids }) release()
        val recover=action == MotionEvent.ACTION_MOVE && ids.any { it !in contacts.ids }
        if (recover) release()
        val slots=runCatching { contacts.assign(ids) }.getOrElse { release(); return false }
        deviceId=event.deviceId
        val mapped=normalise(event,slots)
        if (recover) for(index in ids.indices) {
            val down=copyPointers(mapped,(0..index).toList(),if(index == 0) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            send(down);down.recycle()
        }
        if (action == MotionEvent.ACTION_MOVE && !recover) for(history in 0 until event.historySize) {
            val sample=normalise(event,slots,history)
            send(sample);sample.recycle()
        }
        val result=send(mapped)
        val lifted=if(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        last?.recycle()
        val active=ids.indices.filter { it != lifted }
        last=if(active.isEmpty()) null else copyPointers(mapped,active,MotionEvent.ACTION_MOVE)
        if(lifted >= 0) contacts.lift(ids[lifted])
        mapped.recycle()
        return result
    }

    private fun normalise(event: MotionEvent,slots: IntArray,history: Int=-1): MotionEvent {
        val device=event.device
        fun axis(value: Float,axis: Int,maximum: Float): Float {
            val range=device?.getMotionRange(axis,InputDevice.SOURCE_TOUCHPAD) ?: device?.getMotionRange(axis)
            val result=if(range != null && range.range > 0f) (value-range.min)*maximum/range.range else value
            return if(result.isFinite()) result.coerceIn(0f,maximum) else 0f
        }
        val properties=Array(event.pointerCount) { i -> MotionEvent.PointerProperties().also {
            event.getPointerProperties(i,it);it.id=slots[i]
        } }
        val coordinates=Array(event.pointerCount) { i -> MotionEvent.PointerCoords().also {
            if(history < 0) event.getPointerCoords(i,it) else event.getHistoricalPointerCoords(i,history,it)
            val x=if(history < 0) event.getX(i) else event.getHistoricalX(i,history)
            val y=if(history < 0) event.getY(i) else event.getHistoricalY(i,history)
            // This paired Android 17 AVF creates a 3000 x 3000 trackpad; its
            // sendTrackpadEvent forwards getX/getY without another rescale.
            it.x=axis(x,MotionEvent.AXIS_X,3000f);it.y=axis(y,MotionEvent.AXIS_Y,3000f)
        } }
        return MotionEvent.obtain(event.downTime,if(history < 0) event.eventTime else event.getHistoricalEventTime(history),
            if(history < 0) event.action else MotionEvent.ACTION_MOVE,event.pointerCount,properties,coordinates,
            event.metaState,event.buttonState,event.xPrecision,event.yPrecision,event.deviceId,event.edgeFlags,event.source,event.flags)
    }

    private fun copyPointers(event: MotionEvent,indices: List<Int>,action: Int): MotionEvent = MotionEvent.obtain(
        event.downTime,event.eventTime,action,indices.size,
        Array(indices.size) { i -> MotionEvent.PointerProperties().also { event.getPointerProperties(indices[i],it) } },
        Array(indices.size) { i -> MotionEvent.PointerCoords().also { event.getPointerCoords(indices[i],it) } },
        event.metaState,event.buttonState,event.xPrecision,event.yPrecision,event.deviceId,event.edgeFlags,event.source,event.flags)

    fun release() {
        last?.let { frame ->
            for(count in frame.pointerCount downTo 1) {
                val up=copyPointers(frame,(0 until count).toList(),if(count == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or ((count-1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
                send(up);up.recycle()
            }
            frame.recycle()
        }
        last=null;contacts.clear();deviceId=null
        button?.let { it.action=MotionEvent.ACTION_BUTTON_RELEASE;send(it);it.recycle() }
        button=null
    }
}
