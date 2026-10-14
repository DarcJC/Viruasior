package pro.darc.viruaisor.display

/** Testable authorization boundary; full user-scoped UID, never appId/modulo UID. */
internal class RootDisplayAuthorization(private val applicationUid: Int) {
    init {
        require(applicationUid >= 10000) { "Expected an ordinary application UID" }
    }

    fun enforce(callingUid: Int, transaction: Int) {
        if (callingUid != applicationUid) throw SecurityException("Root display caller UID mismatch")
        if (transaction != ATTACH && transaction != DETACH && transaction != INTERFACE_TRANSACTION) {
            throw SecurityException("Unsupported root display capability")
        }
    }

    companion object {
        // V2 delegates a parent SurfaceControl, never an app-owned producer Surface.
        const val DESCRIPTOR = "pro.darc.viruaisor.display.IRootDisplay.v2"
        const val ATTACH = 1
        const val DETACH = 2
        const val INTERFACE_TRANSACTION = 0x5f4e5446
    }
}
