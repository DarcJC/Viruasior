package pro.darc.viruaisor.root

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Keeps an in-flight KernelSU request alive across Activity recreation, without prompting twice. */
object RootRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<RootAccess>(RootAccess.Idle)
    val state: StateFlow<RootAccess> = mutableState.asStateFlow()

    fun request(onlyIfIdle: Boolean = false) {
        val current = mutableState.value
        if (current is RootAccess.Requesting || (onlyIfIdle && current !is RootAccess.Idle)) return
        if (!mutableState.compareAndSet(current, RootAccess.Requesting)) return
        Log.i(TAG, "Requesting root from app UID via su")
        scope.launch {
            val result = try {
                RootAccessClient().request()
            } catch (error: Exception) {
                RootAccess.Unavailable("Root 检测失败：${error.message ?: error.javaClass.simpleName}")
            }
            mutableState.value = result
            when (result) {
                is RootAccess.Granted -> Log.i(
                    TAG,
                    "Root granted: ${result.implementation}; ${result.identity}; SELinux=${result.selinuxMode}",
                )
                is RootAccess.Denied -> Log.w(TAG, result.reason)
                is RootAccess.Unavailable -> Log.w(TAG, result.reason)
                else -> Unit
            }
        }
    }

    private const val TAG = "ViruaisorRoot"
}
