package pro.darc.viruaisor.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.darc.viruaisor.vm.VmRuntime

sealed interface AvfPermissionSetup {
    data object Idle : AvfPermissionSetup
    data object Working : AvfPermissionSetup
    data class Complete(val changes: List<AvfPermissionChange>) : AvfPermissionSetup
    data class Failed(val reason: String) : AvfPermissionSetup
}

/** Explicit setup/rollback only. Ordinary VM paths never execute su. */
object AvfPermissionRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AvfPermissionSetup>(AvfPermissionSetup.Idle)
    val state: StateFlow<AvfPermissionSetup> = mutableState.asStateFlow()

    fun grantMissing(context: Context) = launchOperation(context) { app ->
        val root = RootRuntime.state.value as? RootAccess.Granted
        val changes = AvfPermissionClient().grantMissing(
            suPath = root?.suPath,
            userId = currentUserId,
            isGranted = { permission -> hasPermission(app, permission) },
            beforeGrant = { permission ->
                val journal = readJournal(app) + permission
                writeJournal(app, journal)
                Log.i(TAG, "Grant attempt user=$currentUserId package=${app.packageName} permission=${permission.androidName}; originally absent")
            },
        )
        changes.forEach { change ->
            Log.i(TAG, "${change.permission.androidName}: before=${change.wasGranted} after=${change.isGranted} error=${change.error.orEmpty()}")
        }
        VmRuntime.refreshSupport(app)
        AvfPermissionSetup.Complete(changes)
    }

    fun revokeAdded(context: Context) = launchOperation(context) { app ->
        check(!VmRuntime.state.value.isActive) { "请先停止虚拟机，再撤销权限。" }
        val root = RootRuntime.state.value as? RootAccess.Granted
            ?: error("请先在 KernelSU 授权 Root。")
        val journal = readJournal(app)
        val added = journal.filterTo(mutableSetOf()) { hasPermission(app, it) }
        Log.i(TAG, "Rollback user=$currentUserId package=${app.packageName} added=$added; Android may terminate this app on revocation")
        val error = AvfPermissionClient().revokeAdded(root.suPath, currentUserId, added)
        // Android may kill the app on revoke. The durable journal survives and is reconciled on retry.
        writeJournal(app, journal.filterTo(mutableSetOf()) { hasPermission(app, it) })
        VmRuntime.refreshSupport(app)
        if (error != null) AvfPermissionSetup.Failed(error)
        else AvfPermissionSetup.Complete(AvfPermission.entries.map {
            AvfPermissionChange(it, wasGranted = it in added, isGranted = hasPermission(app, it))
        })
    }

    private fun launchOperation(context: Context, operation: suspend (Context) -> AvfPermissionSetup) {
        val current = mutableState.value
        if (current is AvfPermissionSetup.Working) return
        if (!mutableState.compareAndSet(current, AvfPermissionSetup.Working)) return
        val app = context.applicationContext
        scope.launch {
            mutableState.value = try {
                check(app.packageName == AvfPermissionClient.PACKAGE_NAME) { "Unexpected package" }
                operation(app)
            } catch (error: Exception) {
                Log.e(TAG, "AVF permission setup failed", error)
                AvfPermissionSetup.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun hasPermission(context: Context, permission: AvfPermission): Boolean =
        context.packageManager.checkPermission(permission.androidName, context.packageName) == PackageManager.PERMISSION_GRANTED

    private fun readJournal(context: Context): Set<AvfPermission> {
        val names = context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
            .getStringSet("added_user_$currentUserId", emptySet()).orEmpty()
        return AvfPermission.entries.filterTo(mutableSetOf()) { it.androidName in names }
    }

    private fun writeJournal(context: Context, permissions: Set<AvfPermission>) {
        check(context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE).edit()
            .putStringSet("added_user_$currentUserId", permissions.mapTo(mutableSetOf()) { it.androidName })
            .commit()) { "Cannot persist AVF permission rollback journal; aborting mutation" }
    }

    // Android's UID layout reserves 100000 app IDs per user; no hidden UserHandle APIs are needed.
    private val currentUserId: Int get() = Process.myUid() / 100000
    private const val JOURNAL = "avf_permission_journal"
    private const val TAG = "ViruaisorPermissions"
}
