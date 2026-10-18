package pro.darc.viruaisor.display

import android.content.Intent
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.SurfaceControl
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.system.Os
import java.io.FileInputStream
import java.security.MessageDigest
import pro.darc.viruaisor.root.RootOverlayArtifactPolicy

/** Non-daemon helper: ONLY display attach/detach; no shell, files, grants, input, or VM lifecycle RPC. */
class RootDisplayService : RootService() {
    private lateinit var authorization: RootDisplayAuthorization
    private val connector = Android17AvfDisplayConnector()
    private val lock = Any()
    private var display: IBinder? = null
    private var ownedSurface: RootOwnedSurface? = null
    private var ownedCursor: RootCursorSurface? = null
    private var sessionToken: IBinder? = null
    private var sessionDeath: IBinder.DeathRecipient? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "viruaisor-root-display-watchdog").apply { isDaemon = true }
    }

    override fun onCreate() {
        check(Process.myUid() == 0) { "Display helper must be a root child" }
        check(packageName == "pro.darc.viruaisor") { "Unexpected root display package" }
        authorization = RootDisplayAuthorization(applicationInfo.uid)
        Log.i(TAG, "Helper started: uid=${Process.myUid()} pid=${Process.myPid()} allowedUid=${applicationInfo.uid} context=${securityContext()}")
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Authenticate BEFORE deserializing caller data or clearing Binder identity.
            authorization.enforce(getCallingUid(), code)
            require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null) { "Synchronous display RPC required" }
            if (code == RootDisplayAuthorization.INTERFACE_TRANSACTION) {
                reply.writeString(RootDisplayAuthorization.DESCRIPTOR)
                return true
            }
            data.enforceInterface(RootDisplayAuthorization.DESCRIPTOR)
            val token = requireNotNull(data.readStrongBinder()) { "Missing display session token" }
            val parent = if (code == RootDisplayAuthorization.ATTACH) {
                requireNotNull(data.readTypedObject(SurfaceControl.CREATOR)) { "Missing SurfaceView parent" }
            } else null
            var parentTransferred = false
            try {
                val width = if (parent != null) data.readInt() else 0
                val height = if (parent != null) data.readInt() else 0
                val observer = if (parent != null) requireNotNull(data.readStrongBinder()) { "Missing frame observer" } else null
                data.enforceNoDataAvail()
                val identity = clearCallingIdentity()
                try {
                    boundedOperation {
                        synchronized(lock) {
                            if (parent != null) {
                                require(parent.isValid) { "Invalid SurfaceView parent" }
                                RootDisplaySize.validate(width, height)
                                requireSingleCrosvm()
                                removeSurface()
                                val death = IBinder.DeathRecipient {
                                    runCatching {
                                        boundedOperation {
                                            synchronized(lock) {
                                                if (sessionToken == token) removeSurface()
                                            }
                                        }
                                    }.onFailure { Log.w(TAG, "Dead display session cleanup failed", it) }
                                }
                                token.linkToDeath(death, 0)
                                var pending: RootOwnedSurface? = null
                                var pendingCursor: RootCursorSurface? = null
                                var requestedDisplay: IBinder? = null
                                try {
                                    val owned = RootOwnedSurface.create(parent, width, height)
                                    pending = owned
                                    parentTransferred = true
                                    val cursor=RootCursorSurface.create(parent,width,height)
                                    pendingCursor=cursor
                                    val binder = connector.waitForDisplayService()
                                    check(token.isBinderAlive) { "Display session died during startup" }
                                    requestedDisplay = binder
                                    val frameSink = object : Binder() {
                                        private var calls = 0L

                                        init {
                                            attachInterface(null, FRAME_SINK_DESCRIPTOR)
                                        }

                                        override fun onTransact(
                                            code: Int,
                                            data: Parcel,
                                            reply: Parcel?,
                                            flags: Int,
                                        ): Boolean {
                                            check(getCallingUid() == applicationInfo.uid) {
                                                "Frame producer UID mismatch"
                                            }
                                            if (code == IBinder.INTERFACE_TRANSACTION) {
                                                return super.onTransact(code, data, reply, flags)
                                            }
                                            require(code == PRESENT_FRAME &&
                                                flags and IBinder.FLAG_ONEWAY == 0 && reply != null) {
                                                "Unsupported frame transaction"
                                            }
                                            data.enforceInterface(FRAME_SINK_DESCRIPTOR)
                                            calls++
                                            if (calls == 1L) Log.i(TAG, "FRAME_SINK_RECEIVED uid=${getCallingUid()}")
                                            val released = requireNotNull(data.readStrongBinder()) { "Missing buffer release callback" }
                                            val buffer = HardwareBuffer.CREATOR.createFromParcel(data)
                                            try {
                                                data.enforceNoDataAvail()
                                                val frameIdentity = clearCallingIdentity()
                                                try {
                                                    cursor.setGuestSize(buffer.width,buffer.height)
                                                    owned.present(buffer, { frame,w,h ->
                                                        sendPresented(requireNotNull(observer), frame,w,h)
                                                    }) { fence ->
                                                        sendBufferRelease(released, fence)
                                                    }
                                                } finally {
                                                    restoreCallingIdentity(frameIdentity)
                                                }
                                                reply.writeNoException()
                                            } catch (error: Exception) {
                                                if (calls <= 2 || calls % 60L == 0L) Log.e(TAG,"AHB frame submission failed",error)
                                                throw error
                                            } finally {
                                                buffer.close()
                                            }
                                            return true
                                        }
                                    }
                                    connector.setSurface(binder, owned.surface, frameSink)
                                    connector.setCursorSurface(binder,cursor.surface)
                                    val pipe=RootCursorSurface.positionChannel()
                                    try {
                                        connector.setCursorStream(binder,pipe[1])
                                        cursor.startPositions(pipe[0])
                                    } catch(error: Throwable) {
                                        pipe[0].close()
                                        throw error
                                    } finally { pipe[1].close() }
                                    check(token.isBinderAlive) { "Display session died during attachment" }
                                    owned.show()
                                    display = binder
                                    ownedSurface = owned
                                    ownedCursor = cursor
                                    sessionToken = token
                                    sessionDeath = death
                                    pending = null
                                    pendingCursor = null
                                } catch (error: Throwable) {
                                    runCatching { token.unlinkToDeath(death, 0) }
                                    runCatching { requestedDisplay?.let { connector.removeCursor(it) } }
                                    runCatching { pendingCursor?.close() }
                                    runCatching {
                                        pending?.close { requestedDisplay?.let { connector.removeSurface(it) } }
                                    }.onFailure { if (it !== error) error.addSuppressed(it) }
                                    throw error
                                }
                                Log.i(TAG, "Root-owned BLAST Surface attached: ${width}x$height ownerPid=${Process.myPid()}")
                            } else if (sessionToken == token) {
                                // An old UI instance must not remove a newer instance's attachment.
                                removeSurface()
                            }
                        }
                    }
                } finally {
                    restoreCallingIdentity(identity)
                }
                reply.writeNoException()
                // Diagnostic only: bootstrap is restricted by libsu, not authenticated by this text.
                reply.writeString("uid=${Process.myUid()} pid=${Process.myPid()} context=${securityContext()} queue=root-owned")
                return true
            } finally {
                if (!parentTransferred) parent?.release()
            }
        }
    }

    override fun onDestroy() {
        runCatching { boundedOperation { synchronized(lock) { removeSurface() } } }
            .onFailure { Log.w(TAG, "Display cleanup failed", it) }
        watchdog.shutdownNow()
        Log.i(TAG, "Helper destroyed; normal libsu root process exits when its last service is gone")
    }

    private fun sendBufferRelease(callback: IBinder, fence: SyncFence) {
        // SurfaceFlinger invokes this on a callback thread. Never wait for the GPU
        // or hold the session lock; transfer the actual release fence to crosvm.
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val flattened = Parcel.obtain()
        try {
            data.writeInterfaceToken("pro.darc.viruaisor.display.IBufferRelease")
            fence.writeToParcel(flattened, 0)
            flattened.setDataPosition(0)
            val valid = flattened.readBoolean()
            if (valid) {
                requireNotNull(flattened.readFileDescriptor()).use {
                    BufferReleaseProtocol.write(data,it)
                }
            } else BufferReleaseProtocol.write(data,null)
            if (callback.transact(1, data, reply, 0)) reply.readException()
        } catch (error: Exception) {
            Log.w(TAG, "Buffer release delivery failed", error)
        } finally {
            fence.close()
            flattened.recycle()
            reply.recycle()
            data.recycle()
        }
    }

    private fun sendPresented(observer: IBinder, frame: Long, width: Int, height: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("pro.darc.viruaisor.display.IDisplayFrame")
            data.writeLong(frame)
            data.writeInt(width)
            data.writeInt(height)
            if (observer.transact(1,data,reply,0)) reply.readException()
        } catch (error: Exception) {
            Log.w(TAG,"Frame observer delivery failed",error)
        } finally { data.recycle(); reply.recycle() }
    }

    private fun requireSingleCrosvm() {
        // waitDisplayService has NO VM ownership argument. Fail closed on competing VMs instead
        // of pretending UID authentication also authenticates the global display resource.
        val crosvm = CrosvmProcessGuard(privateCandidate=::isPrivateCandidate).requireSingle()
        requireOwnedCrosvm(crosvm)
        validateExecutable(crosvm,desktopOnly=false)
        Log.i(TAG, "Single-VM lab guard: crosvm pid=${crosvm.name}; internal display ownership remains unverifiable")
    }

    private fun requireOwnedCrosvm(process: File) {
        val uidLine=File(process,"status").readLines().single { it.startsWith("Uid:") }
        val ids=uidLine.substringAfter(':').trim().split(Regex("\\s+"))
        check(ids.size == 4 && ids.all { it.toIntOrNull() == applicationInfo.uid }) { "Display VM is not owned by this application" }
        check(File(process,"cmdline").readText().substringBefore('\u0000') == "crosvm_default") { "Unexpected display VM" }
        val context=File(process,"attr/current").readText().trim().trimEnd('\u0000')
        val domain="u:r:untrusted_app_all_virtmgr_crosvm:s0"
        check(context == domain || context.startsWith("$domain:")) { "Unexpected VM security domain" }
    }

    private fun isPrivateCandidate(process: File,path: String): Boolean {
        val user=applicationInfo.uid / 100000
        if (!Regex("^/data/user/$user/pro\\.darc\\.viruaisor/files/venus-overlay-lab/[0-9a-f]{32}\\.snapshot$").matches(path)) return false
        requireOwnedCrosvm(process)
        validateExecutable(process,desktopOnly=true)
        return true
    }

    private fun validateExecutable(process: File,desktopOnly: Boolean) {
        FileInputStream(File(process,"exe")).use { input ->
            val stat=Os.fstat(input.fd)
            val digest=MessageDigest.getInstance("SHA-256")
            val bytes=ByteArray(65536)
            while (true) { val n=input.read(bytes); if (n < 0) break; digest.update(bytes,0,n) }
            val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!desktopOnly && hash == RootOverlayArtifactPolicy.CROSVM_SHA256) {
                check(stat.st_uid == 0 && stat.st_size == 13593816L && stat.st_mode and 0xfff == 0x1ed) { "Unexpected stock VM executable metadata" }
            } else RootOverlayArtifactPolicy.enforceDesktopCandidate(hash,stat.st_size,stat.st_uid,stat.st_mode)
        }
    }

    private fun removeSurface() {
        val binder = display
        val owned = ownedSurface
        val cursor = ownedCursor
        val token = sessionToken
        val death = sessionDeath
        display = null
        ownedSurface = null
        ownedCursor = null
        sessionToken = null
        sessionDeath = null
        if (token != null && death != null) runCatching { token.unlinkToDeath(death, 0) }
        try {
            if (binder != null) connector.removeCursor(binder)
        } finally {
            try { cursor?.close() } finally {
                if (owned != null) owned.close {
                    if (binder != null) connector.removeSurface(binder)
                }
            }
        }
    }

    private fun boundedOperation(action: () -> Unit) {
        // Binder calls cannot be safely interrupted. Kill ONLY this disposable helper on a hang.
        val timeout = watchdog.schedule({
            Log.e(TAG, "Display operation exceeded ${OPERATION_TIMEOUT_SECONDS}s; exiting helper")
            Process.killProcess(Process.myPid())
        }, OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            action()
        } finally {
            timeout.cancel(false)
        }
    }

    private fun securityContext(): String = runCatching {
        File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
    }.getOrDefault("unknown")

    private companion object {
        const val TAG = "ViruaisorRootDisplay"
        const val OPERATION_TIMEOUT_SECONDS = 15L
        const val PRESENT_FRAME = 1
        const val FRAME_SINK_DESCRIPTOR = "pro.darc.viruaisor.display.IRootFrameSink"
    }
}
