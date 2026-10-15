package pro.darc.viruaisor.gpu

/** The disposable worker must not depend on a delayed callback after dropping FGS. */
internal object DisposableProbeExit {
    fun finish(stopForeground: () -> Unit, stopService: () -> Unit, terminateSelf: () -> Unit) {
        try {
            stopForeground()
            stopService()
        } finally {
            terminateSelf()
        }
    }
}
