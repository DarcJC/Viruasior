package pro.darc.viruaisor.display

import android.os.IBinder
import android.os.DeadObjectException
import android.os.Parcel
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Display seam between Android UI ownership and the platform-specific VM presentation path.
 * Implementations own all Surface attachment details; VM lifecycle code does not know about them.
 */
data class DisplayTarget(
    val surface: Surface,
    val parent: SurfaceControl?,
    val bufferWidth: Int,
    val bufferHeight: Int,
)

interface DisplayBackend {
    val name: String
    fun attach(target: DisplayTarget, onAttached: () -> Unit)
    fun detach(surface: Surface)
}

interface VmInputBackend {
    fun setTouchScale(scale: Float)
    fun sendTouch(event: MotionEvent): Boolean
    fun sendMouse(event: MotionEvent): Boolean
    fun sendKey(scanCode: Short, down: Boolean): Boolean
}

/** UI-side input seam; the VM service binds its live engine without exposing lifecycle ownership. */
object InputBackendRegistry {
    @Volatile private var active: VmInputBackend? = null

    fun bind(backend: VmInputBackend) {
        active = backend
    }

    fun unbind(backend: VmInputBackend) {
        if (active === backend) active = null
    }

    fun setTouchScale(scale: Float) = active?.setTouchScale(scale)
    fun sendTouch(event: MotionEvent): Boolean = active?.sendTouch(event) == true
    fun sendMouse(event: MotionEvent): Boolean = active?.sendMouse(event) == true
    fun sendKey(scanCode: Short, down: Boolean): Boolean = active?.sendKey(scanCode, down) == true
}

/** Android 17 direct-Surface adapter backed by the internal crosvm display AIDL protocol. */
class DirectAvfSurfaceBackend(
    private val connector: AvfDisplayConnector = Android17AvfDisplayConnector(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "viruaisor-display").apply { isDaemon = true }
    },
) : DisplayBackend {
    override val name: String = "AVF direct Surface"

    private val lock = Any()
    private var surface: Surface? = null
    private var generation = 0L
    private var displayBinder: IBinder? = null

    override fun attach(target: DisplayTarget, onAttached: () -> Unit) {
        val surface = target.surface
        val requestedGeneration = synchronized(lock) {
            this.surface = surface
            displayBinder = null
            ++generation
        }
        bindWhenAvailable(surface, requestedGeneration, onAttached)
    }

    override fun detach(surface: Surface) {
        val binder = synchronized(lock) {
            if (this.surface !== surface) return
            this.surface = null
            generation++
            displayBinder.also { displayBinder = null }
        }
        binder?.let { runCatching { connector.removeSurface(it) } }
    }

    private fun bindWhenAvailable(
        expectedSurface: Surface,
        expectedGeneration: Long,
        onAttached: () -> Unit,
    ) {
        executor.execute {
            runCatching { connector.waitForDisplayService() }
                .onSuccess { binder ->
                    val attach = synchronized(lock) {
                        if (surface === expectedSurface && generation == expectedGeneration) {
                            displayBinder = binder
                            true
                        } else {
                            false
                        }
                    }
                    if (!attach) return@onSuccess
                    runCatching {
                        binder.linkToDeath(
                            {
                                val next = synchronized(lock) {
                                    if (displayBinder === binder) displayBinder = null
                                    surface?.let { it to generation }
                                }
                                next?.let { (currentSurface, currentGeneration) ->
                                    bindWhenAvailable(currentSurface, currentGeneration, onAttached)
                                }
                            },
                            0,
                        )
                        connector.setSurface(binder, expectedSurface)
                        val stillAttached = synchronized(lock) {
                            surface === expectedSurface && generation == expectedGeneration
                        }
                        if (stillAttached) onAttached() else connector.removeSurface(binder)
                    }.onFailure { error ->
                        Log.e(TAG, "Could not attach the AVF display Surface", error)
                    }
                }
                .onFailure { error -> Log.e(TAG, "Could not obtain the AVF display service", error) }
        }
    }

    private companion object {
        const val TAG = "ViruaisorDisplay"
    }
}

/** Small internal seam used to isolate the version-specific binder protocol in tests. */
interface AvfDisplayConnector {
    fun waitForDisplayService(): IBinder
    fun setSurface(displayBinder: IBinder, surface: Surface)
    fun removeSurface(displayBinder: IBinder)
}

/**
 * Android 17 implementation of the internal display AIDL calls. Transaction numbers and
 * descriptors are deliberately local to this adapter so a future public AVF display API or a
 * stream decoder can replace it without changing VM lifecycle code.
 */
class Android17AvfDisplayConnector : AvfDisplayConnector {
    override fun waitForDisplayService(): IBinder {
        val virtualization = NativeBinderLookup.waitForVirtualizationService()
            ?: error("The AVF binder service is unavailable.")

        return transact(virtualization, INTERNAL_DESCRIPTOR, WAIT_DISPLAY_SERVICE) { reply ->
            reply.readStrongBinder() ?: error("AVF returned no display binder.")
        }
    }

    override fun setSurface(displayBinder: IBinder, surface: Surface) {
        transact(displayBinder, DISPLAY_DESCRIPTOR, SET_SURFACE, write = { data ->
            data.writeTypedObject(surface, 0)
            data.writeBoolean(false)
        }) { Unit }
    }

    override fun removeSurface(displayBinder: IBinder) {
        try {
            transact(displayBinder, DISPLAY_DESCRIPTOR, REMOVE_SURFACE, write = { data ->
                data.writeBoolean(false)
            }) { Unit }
        } catch (_: DeadObjectException) {
            // VM exit can precede Surface teardown. The producer is already gone;
            // the caller still releases every local queue/layer/parent handle.
            Log.i("ViruaisorRootDisplay", "Display producer already exited before Surface removal")
        }
    }

    private fun <T> transact(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit = {},
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(binder.transact(code, data, reply, 0)) {
                "Binder transaction $code was not handled."
            }
            reply.readException()
            read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val INTERNAL_DESCRIPTOR =
            "android.system.virtualizationservice_internal.IVirtualizationServiceInternal"
        const val DISPLAY_DESCRIPTOR = "android.crosvm.ICrosvmAndroidDisplayService"
        const val WAIT_DISPLAY_SERVICE = 18
        const val SET_SURFACE = 1
        const val REMOVE_SURFACE = 3
    }
}

private object NativeBinderLookup {
    init {
        System.loadLibrary("viruaisor_native")
    }

    external fun waitForVirtualizationService(): IBinder?
}

/** Future decoder-backed fallback for devices without direct Surface presentation. */
class StreamSurfaceBackend : DisplayBackend {
    override val name: String = "Stream fallback"
    @Volatile private var attachedSurface: Surface? = null

    override fun attach(target: DisplayTarget, onAttached: () -> Unit) {
        attachedSurface = target.surface
        onAttached()
    }

    override fun detach(surface: Surface) {
        if (attachedSurface === surface) attachedSurface = null
    }
}

object DisplayBackendRegistry {
    val active: DisplayBackend = DirectAvfSurfaceBackend()
}
