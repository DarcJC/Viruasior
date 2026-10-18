package pro.darc.viruaisor.display

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer

/** Reads the display clock for this frame sink, stopping when it is closed. */
internal class RootDisplayVsync : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enabled = false
    @Volatile private var closed = false
    @Volatile private var failed = false
    @Volatile var phase: RootDisplayCadence.Phase? = null
        private set
    private var choreographer: Choreographer? = null // Main looper only.
    private var logged = false

    private val callback = object : Choreographer.VsyncCallback {
        override fun onVsync(data: Choreographer.FrameData) {
            if (!enabled || closed) return
            try {
                val timelines = data.frameTimelines
                var interval = Long.MAX_VALUE
                for (index in 1 until timelines.size) {
                    val delta = timelines[index].expectedPresentationTimeNanos -
                        timelines[index - 1].expectedPresentationTimeNanos
                    if (delta > 0L) interval = minOf(interval, delta)
                }
                if (interval != Long.MAX_VALUE) {
                    // FrameData is valid only in this callback; keep primitive values.
                    phase = RootDisplayCadence.Phase(
                        data.preferredFrameTimeline.expectedPresentationTimeNanos, interval)
                    if (!logged) {
                        logged = true
                        Log.i("ViruaisorRootDisplay", "VSYNC_CLOCK_READY intervalNanos=$interval")
                    }
                }
                if (enabled && !closed) choreographer?.postVsyncCallback(this)
            } catch (error: RuntimeException) {
                fail(error)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (closed || failed || enabled == value) return
        enabled = value
        if (!value) phase = null
        main.post {
            try {
                val clock = choreographer ?: if (enabled && !closed)
                    Choreographer.getInstance().also { choreographer = it } else return@post
                clock.removeVsyncCallback(callback)
                logged = false
                if (enabled && !closed) clock.postVsyncCallback(callback)
            } catch (error: RuntimeException) {
                fail(error)
            }
        }
    }

    private fun fail(error: RuntimeException) {
        failed = true
        enabled = false
        phase = null
        Log.w("ViruaisorRootDisplay", "Display clock unavailable", error)
    }

    override fun close() {
        if (closed) return
        closed = true
        enabled = false
        phase = null
        main.post { choreographer?.removeVsyncCallback(callback) }
    }
}
