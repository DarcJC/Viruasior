package pro.darc.viruaisor.display

import android.hardware.HardwareBuffer
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/** The BLAST queue and its producer Binder are constructed ONLY in the UID-0 helper. */
internal class RootOwnedSurface private constructor(
    val surface: Surface,
    private val child: SurfaceControl,
    private val producerControl: SurfaceControl,
    private val width: Int,
    private val height: Int,
    private val setTrustedOverlay: Method,
    private val cleanup: RootDisplayCleanup,
) {
    private var frames = 0L

    fun show() {
        SurfaceControl.Transaction().use { transaction ->
            setTrustedOverlay.invoke(transaction, producerControl, true)
            transaction.setLayer(producerControl, 1)
                .setPosition(producerControl, 0f, 0f)
                .setScale(producerControl, 1f, 1f)
                .setCrop(producerControl, Rect(0, 0, width, height))
                .setVisibility(producerControl, true)
                .setVisibility(child, false)
                .apply()
        }
    }

    fun present(buffer: HardwareBuffer) {
        require(child.isValid) { "Display layer is no longer valid" }
        require(buffer.width == width && buffer.height == height && buffer.layers == 1 &&
            buffer.format == HardwareBuffer.RGBA_8888) { "Unexpected scanout HardwareBuffer" }
        val displayUsage = HardwareBuffer.USAGE_COMPOSER_OVERLAY or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        require(buffer.usage and displayUsage != 0L) { "HardwareBuffer is not displayable" }
        val bounds = Rect(0, 0, width, height)
        SurfaceControl.Transaction().use { transaction ->
            transaction.setBuffer(child, buffer)
                .setGeometry(child, bounds, bounds, 0)
                .setVisibility(child, true)
                .apply()
        }
        frames++
        if (frames == 1L || frames % 60L == 0L) {
            Log.i(TAG, "FRAME_POSTED_ROOT count=$frames path=surface-control")
        }
    }

    fun close(stopProducer: () -> Unit = {}) = cleanup.close(stopProducer)

    companion object {
        private const val TAG = "ViruaisorRootDisplay"

        /** On success owns the received parent HANDLE, never the parent's layer lifecycle. */
        fun create(parent: SurfaceControl, width: Int, height: Int): RootOwnedSurface {
            check(Process.myUid() == 0) { "BLAST queue must be created in the root helper" }
            require(parent.isValid) { "Invalid SurfaceView parent" }
            RootDisplaySize.validate(width, height)
            // Resolve the API-37 signatures before allocating resources. No exemptions/settings.
            val api = BlastApi()
            var child: SurfaceControl? = null
            var producerControl: SurfaceControl? = null
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
                val createdProducerControl = SurfaceControl.Builder()
                    .setName("ViruaisorRootDisplayProducer")
                    .setParent(parent)
                    .setBufferSize(width, height)
                    .setFormat(PixelFormat.RGBA_8888)
                    .setOpaque(true)
                    .setHidden(true)
                    .build()
                producerControl = createdProducerControl
                val createdQueue = api.create(producerControl, width, height)
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
                return RootOwnedSurface(
                    createdSurface, control, createdProducerControl, width, height,
                    api.setTrustedOverlay, cleanup(
                    control, producerControl, createdSurface, createdQueue, api.destroy,
                    parent::release,
                ))
            } catch (error: Throwable) {
                // Until create() returns, the caller retains responsibility for its parent handle.
                runCatching {
                    cleanup(child, producerControl, surface, queue, api.destroy, {}).close()
                }
                    .onFailure { if (it !== error) error.addSuppressed(it) }
                throw IllegalStateException("Root-owned BLAST creation failed: ${error.message}", error)
            }
        }

        private fun cleanup(
            child: SurfaceControl?, producerControl: SurfaceControl?, surface: Surface?,
            queue: Any?, destroy: Method, releaseParent: () -> Unit,
        ) = RootDisplayCleanup(
            hideAndUnparent = {
                SurfaceControl.Transaction().use { transaction ->
                    if (child != null && child.isValid) {
                        transaction.setVisibility(child, false).reparent(child, null)
                    }
                    if (producerControl != null && producerControl.isValid) {
                        transaction.setVisibility(producerControl, false)
                            .reparent(producerControl, null)
                    }
                    transaction.apply()
                }
            },
            releaseSurface = { surface?.release() },
            destroyQueue = { if (queue != null) destroy.invoke(queue) },
            releaseChild = {
                child?.release()
                producerControl?.release()
            },
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
        val setTrustedOverlay: Method = SurfaceControl.Transaction::class.java.getMethod(
            "setTrustedOverlay", SurfaceControl::class.java, Boolean::class.javaPrimitiveType,
        )

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
