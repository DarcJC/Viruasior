package pro.darc.viruaisor.display

import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Process
import android.os.Debug
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method
import java.util.concurrent.Executor

/** The BLAST queue and its producer Binder are constructed ONLY in the UID-0 helper. */
internal class RootOwnedSurface private constructor(
    val surface: Surface,
    private val child: SurfaceControl,
    private val producerControl: SurfaceControl,
    private val width: Int,
    private val height: Int,
    private val setTrustedOverlay: Method,
    private val setColorTransform: Method,
    private val setCornerRadius: Method,
    private val cleanup: RootDisplayCleanup,
) {
    private var frames = 0L
    @Volatile private var closed = false
    private var lastSize = 0L
    private val buffers = RootDisplayBuffers<HardwareBuffer>(4)
    private val cadence = RootDisplayCadence()
    private val vsync = RootDisplayVsync()

    @Synchronized
    fun show() {
        SurfaceControl.Transaction().use { transaction ->
            setTrustedOverlay.invoke(transaction, producerControl, true)
            transaction.setLayer(producerControl, 1)
                .setPosition(producerControl, 0f, 0f)
                .setScale(producerControl, 1f, 1f)
                .setCrop(producerControl, Rect(0, 0, width, height))
                .setVisibility(producerControl, frames == 0L)
                .setVisibility(child, frames != 0L)
                .apply()
        }
    }

    @Synchronized
    fun present(buffer: HardwareBuffer, onPresented: (Long,Int,Int) -> Unit, onRelease: (SyncFence) -> Unit) {
        check(!closed) { "Stale display session" }
        require(child.isValid) { "Display layer is no longer valid" }
        RootDisplaySize.validate(buffer.width, buffer.height)
        require(buffer.layers == 1 &&
            buffer.format == HardwareBuffer.RGBA_8888) { "Unexpected scanout HardwareBuffer" }
        val displayUsage = HardwareBuffer.USAGE_COMPOSER_OVERLAY or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        require(buffer.usage and displayUsage == displayUsage) { "HardwareBuffer is not displayable" }
        val size = (buffer.width.toLong() shl 32) or buffer.height.toLong()
        val frame = frames+1
        val frameWidth = buffer.width
        val frameHeight = buffer.height
        // SurfaceComposerClient caches by native buffer ID and evicts when the
        // registered GraphicBuffer reference dies. Keep one bounded reference
        // per scanout buffer instead of rebuilding RenderEngine imports each frame.
        val highResolution = frameWidth.toLong() * frameHeight > 4_194_304L
        val retained = buffers.get(buffer.id, frameWidth, frameHeight) { retainHardwareBuffer(buffer) }
        vsync.setEnabled(true)
        val slot = cadence.plan(System.nanoTime(), size, vsync.phase,
            1_000_000_000L / if (highResolution) 60 else 120)
        if (slot != null) {
            // Back-pressure only this frame sink, at most one target period. Keep
            // the desired presentation one period ahead, never an unbounded queue.
            while (true) {
                val wait = slot.submitAtNanos - System.nanoTime()
                if (wait <= 0L) break
                Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
            }
        }
        SurfaceControl.Transaction().use { transaction ->
            if (slot != null) transaction.setDesiredPresentTimeNanos(slot.presentAtNanos)
            // Layer properties persist across buffer submissions. Resend geometry only
            // when the guest size changes, and keep every buffer's release callback.
            if (size != lastSize) {
                transaction.addTransactionCommittedListener(Executor { it.run() }) {
                    if (!closed) onPresented(frame,frameWidth,frameHeight)
                }
                val source = Rect(0, 0, buffer.width, buffer.height)
                transaction.setGeometry(child, source, Rect(0, 0, width, height), 0)
                    .setCrop(child, source)
            }
            if (frames == 0L) {
                setTrustedOverlay.invoke(transaction, child, true)
                // The qualified DRM ARGB/XRGB scanout has BGRA bytes in the Vulkan-
                // compatible RGBA AHB. Let the compositor swap R/B without touching
                // or copying pixels on the CPU, or allocating an extra blit target.
                setColorTransform.invoke(transaction, child,
                    floatArrayOf(0f,0f,1f, 0f,1f,0f, 1f,0f,0f), floatArrayOf(0f,0f,0f))
                // This device's HWC reports support for the R/B transform but displays
                // green while screenshots are correct. A subpixel rounded edge keeps
                // only this layer in RenderEngine, sampling this same AHB directly.
                setCornerRadius.invoke(transaction, child, CLIENT_COMPOSITION_RADIUS_PX)
                transaction.setLayer(child, 2)
                    .setVisibility(producerControl, false)
                    .setVisibility(child, true)
            }
            transaction.setBuffer(child, retained, null, onRelease).apply()
        }
        if (slot != null) cadence.submitted(slot)
        lastSize = size
        frames++
        if (frames == 1L || frames % 60L == 0L) {
            Log.i(TAG, "FRAME_POSTED_ROOT count=$frames path=surface-control binderProxies=${Debug.getBinderProxyObjectCount()}")
        }
    }

    fun close(stopProducer: () -> Unit = {}) {
        synchronized(this) {
            closed = true
            vsync.close()
        }
        try {
            cleanup.close(stopProducer)
        } catch (error: Throwable) {
            runCatching { synchronized(this) { buffers.close() } }
                .onFailure { if (it !== error) error.addSuppressed(it) }
            throw error
        }
        synchronized(this) { buffers.close() }
    }

    companion object {
        private const val TAG = "ViruaisorRootDisplay"
        private const val CLIENT_COMPOSITION_RADIUS_PX = 0.01f

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
                    api.setTrustedOverlay, api.setColorTransform, api.setCornerRadius, cleanup(
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
        val setColorTransform: Method = SurfaceControl.Transaction::class.java.getMethod(
            "setColorTransform", SurfaceControl::class.java, FloatArray::class.java, FloatArray::class.java,
        )
        val setCornerRadius: Method = SurfaceControl.Transaction::class.java.getMethod(
            "setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType,
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
