package pro.darc.viruaisor.display

import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Process
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/** The BLAST queue and its producer Binder are constructed ONLY in the UID-0 helper. */
internal class RootOwnedSurface private constructor(
    val surface: Surface,
    private val child: SurfaceControl,
    private val cleanup: RootDisplayCleanup,
) {
    fun show() {
        SurfaceControl.Transaction().use { transaction ->
            transaction.setVisibility(child, true).apply()
        }
    }

    fun close(stopProducer: () -> Unit = {}) = cleanup.close(stopProducer)

    companion object {
        /** On success owns the received parent HANDLE, never the parent's layer lifecycle. */
        fun create(parent: SurfaceControl, width: Int, height: Int): RootOwnedSurface {
            check(Process.myUid() == 0) { "BLAST queue must be created in the root helper" }
            require(parent.isValid) { "Invalid SurfaceView parent" }
            RootDisplaySize.validate(width, height)
            // Resolve the API-37 signatures before allocating resources. No exemptions/settings.
            val api = BlastApi()
            var child: SurfaceControl? = null
            var queue: Any? = null
            var surface: Surface? = null
            try {
                val control = SurfaceControl.Builder()
                    .setName("ViruaisorRootDisplay")
                    .setParent(parent)
                    .setBufferSize(width, height)
                    .setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(true)
                    .setHidden(true)
                    .build()
                child = control
                val createdQueue = api.create(control, width, height)
                queue = createdQueue
                val createdSurface = api.createSurface.invoke(createdQueue) as? Surface
                    ?: error("BLAST did not return a Surface")
                surface = createdSurface
                require(createdSurface.isValid) { "Invalid root-owned Surface" }
                SurfaceControl.Transaction().use { transaction ->
                    // Fixed-holder scaling/cropping is inherited from the SurfaceView container.
                    transaction.setLayer(control, 1)
                        .setPosition(control, 0f, 0f)
                        .setScale(control, 1f, 1f)
                        .setCrop(control, Rect(0, 0, width, height))
                        .apply()
                }
                return RootOwnedSurface(createdSurface, control, cleanup(
                    control, createdSurface, createdQueue, api.destroy, parent::release,
                ))
            } catch (error: Throwable) {
                // Until create() returns, the caller retains responsibility for its parent handle.
                runCatching { cleanup(child, surface, queue, api.destroy, {}).close() }
                    .onFailure { if (it !== error) error.addSuppressed(it) }
                throw IllegalStateException("Root-owned BLAST creation failed: ${error.message}", error)
            }
        }

        private fun cleanup(
            child: SurfaceControl?, surface: Surface?, queue: Any?, destroy: Method,
            releaseParent: () -> Unit,
        ) = RootDisplayCleanup(
            hideAndUnparent = {
                if (child != null && child.isValid) SurfaceControl.Transaction().use { transaction ->
                    transaction.setVisibility(child, false).reparent(child, null).apply()
                }
            },
            releaseSurface = { surface?.release() },
            destroyQueue = { if (queue != null) destroy.invoke(queue) },
            releaseChild = { child?.release() },
            releaseParentHandle = releaseParent,
        )
    }

    /** Hidden APIs are isolated in this helper-only class, not linked from the ordinary app. */
    private class BlastApi {
        private val type = Class.forName("android.graphics.BLASTBufferQueue")
        private val constructor = type.getConstructor(String::class.java, Boolean::class.javaPrimitiveType)
        private val update = type.getMethod("update", SurfaceControl::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val createSurface: Method = type.getMethod("createSurface") // No compositor handle in parcel.
        val destroy: Method = type.getMethod("destroy")

        fun create(child: SurfaceControl, width: Int, height: Int): Any {
            val queue = constructor.newInstance("ViruaisorRootDisplay", true)
            try {
                update.invoke(queue, child, width, height, PixelFormat.RGBA_8888)
            } catch (error: Throwable) {
                runCatching { destroy.invoke(queue) }.onFailure { if (it !== error) error.addSuppressed(it) }
                throw error
            }
            return queue
        }
    }
}
