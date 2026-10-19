package pro.darc.viruaisor.root

internal object RootStorageAuthorization {
    const val DESCRIPTOR = "pro.darc.viruaisor.root.IStorageBackend.v1"
    const val START = 1
    const val ALIVE = 2
    const val STOP = 3

    fun enforce(appUid: Int, callerUid: Int, callerPid: Int, code: Int) {
        if (appUid % 100000 !in 10000..19999 || callerUid != appUid || callerPid <= 1 || code !in START..STOP) {
            throw SecurityException("Only this application's VM process may control fixed shared storage")
        }
    }
    fun verifyProcess(status: String, command: String, uid: Int, packageName: String) {
        val lines = status.lineSequence().toList()
        val ids = lines.singleOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
        val caps = lines.singleOrNull { it.startsWith("CapEff:") }?.substringAfter(':')?.trim()?.toULongOrNull(16)
        if (ids?.size != 4 || ids.any { it.toIntOrNull() != uid } || caps != 0uL ||
            command.split('\u0000').filter { it.isNotEmpty() } != listOf(pro.darc.viruaisor.AppIdentity.vmProcess(packageName))) {
            throw SecurityException("Shared storage requires the unprivileged application VM process")
        }
    }
    fun enforceOwner(uid: Int, pid: Int, token: Any, callerUid: Int, callerPid: Int, callerToken: Any) {
        if (uid != callerUid || pid != callerPid || token != callerToken) {
            throw SecurityException("Shared storage session belongs to another process or token")
        }
    }
}
