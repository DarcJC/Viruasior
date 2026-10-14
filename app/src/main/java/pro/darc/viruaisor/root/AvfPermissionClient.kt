package pro.darc.viruaisor.root

/** Closed capability list: there is no caller-supplied package, permission name, or shell command. */
enum class AvfPermission(val androidName: String) {
    MANAGE_VM("android.permission.MANAGE_VIRTUAL_MACHINE"),
    CUSTOM_VM("android.permission.USE_CUSTOM_VIRTUAL_MACHINE"),
}

data class AvfPermissionChange(
    val permission: AvfPermission,
    val wasGranted: Boolean,
    val isGranted: Boolean,
    val error: String? = null,
)

internal class AvfPermissionClient(
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor(),
) {
    fun grantMissing(
        suPath: String?,
        userId: Int,
        isGranted: (AvfPermission) -> Boolean,
        beforeGrant: (AvfPermission) -> Unit,
    ): List<AvfPermissionChange> {
        require(userId >= 0) { "Invalid Android user ID" }
        return AvfPermission.entries.map { permission ->
            if (isGranted(permission)) {
                AvfPermissionChange(permission, wasGranted = true, isGranted = true)
            } else if (suPath == null) {
                AvfPermissionChange(permission, false, false, "请先在 KernelSU 授权 Root。")
            } else {
                // Journal BEFORE mutation, including ambiguous timeout/partial-success cases.
                beforeGrant(permission)
                val error = runCommand(suPath, "grant", userId, listOf(permission))
                val verified = isGranted(permission)
                AvfPermissionChange(
                    permission, wasGranted = false, isGranted = verified,
                    error = if (verified) null else error ?: "普通应用进程验证未通过。",
                )
            }
        }
    }

    /** Revokes only the caller's journal of originally absent permissions, never preexisting grants. */
    fun revokeAdded(suPath: String, userId: Int, added: Set<AvfPermission>): String? {
        require(userId >= 0) { "Invalid Android user ID" }
        if (added.isEmpty()) return null
        return runCommand(suPath, "revoke", userId, added.sortedBy { it.ordinal })
    }

    private fun runCommand(suPath: String, operation: String, userId: Int, permissions: List<AvfPermission>): String? {
        return try {
            // Each command targets exactly this package and the current Android user. No settings,
            // AppOps, SELinux policy, filesystem permissions, or VM operations belong in this path.
            val script = permissions.joinToString("; ") {
                "/system/bin/pm $operation --user $userId $PACKAGE_NAME ${it.androidName}"
            }
            val result = executor.execute(listOf(suPath, "-c", script), COMMAND_TIMEOUT_SECONDS)
            when {
                result.timedOut -> "AVF 权限操作超时；请重新检测权限状态。"
                result.exitCode != 0 -> result.output.trim().take(400).ifEmpty { "pm $operation failed (${result.exitCode})" }
                else -> null
            }
        } catch (error: Exception) {
            error.message ?: error.javaClass.simpleName
        }
    }

    internal companion object {
        const val PACKAGE_NAME = "pro.darc.viruaisor"
        const val COMMAND_TIMEOUT_SECONDS = 20L
    }
}
