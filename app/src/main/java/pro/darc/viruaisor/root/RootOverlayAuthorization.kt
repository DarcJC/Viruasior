package pro.darc.viruaisor.root

internal class RootOverlayAuthorization(private val uid: Int) {
    fun enforce(caller: Int, transaction: Int) {
        if (uid % 100000 !in 10000..19999 || caller != uid || transaction !in setOf(PROBE, STOCK_EXEC, VENUS_EXEC, VENUS_DESKTOP)) {
            throw SecurityException("Only this full application UID may request an allowlisted overlay capability")
        }
    }
    companion object {
        const val DESCRIPTOR = "pro.darc.viruaisor.root.IOverlayLease.v2"
        const val PROBE = 1
        const val STOCK_EXEC = 2
        const val VENUS_EXEC = 3
        const val VENUS_DESKTOP = 4
        fun transactionFor(mode: String): Int = when (mode) {
            "normal", "timeout", "client-death", "helper-death" -> PROBE
            "stock-exec" -> STOCK_EXEC
            "venus-exec" -> VENUS_EXEC
            "venus-desktop" -> VENUS_DESKTOP
            else -> throw SecurityException("Unknown overlay capability")
        }
        fun validRequest(token: String, mode: String): Boolean =
            token.length == 32 && token.all { it in '0'..'9' || it in 'a'..'f' } &&
                mode in setOf("normal", "timeout", "client-death", "helper-death", "stock-exec", "venus-exec", "venus-desktop")
    }
}
