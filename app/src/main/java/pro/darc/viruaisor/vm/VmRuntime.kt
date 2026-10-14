package pro.darc.viruaisor.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object VmRuntime {
    private val initializationMutex = Mutex()
    private val mutableState = MutableStateFlow(VmUiState())
    val state: StateFlow<VmUiState> = mutableState.asStateFlow()

    suspend fun initialize(context: Context) {
        initializationMutex.withLock {
            if (mutableState.value.support !is AvfSupport.Checking) return
            val result = withContext(Dispatchers.IO) {
                val support = AvfCapability.check(context.applicationContext)
                val bundleResult = runCatching { VmBundleStore(context.applicationContext).currentBundle() }
                Triple(support, bundleResult.getOrNull(), bundleResult.exceptionOrNull())
            }
            mutableState.value = mutableState.value.copy(
                support = result.first,
                bundle = result.second,
                detail = result.third?.rootMessage(),
            )
        }
    }

    suspend fun refreshSupport(context: Context) {
        initializationMutex.withLock {
            val support = withContext(Dispatchers.IO) {
                AvfCapability.check(context.applicationContext)
            }
            mutableState.value = mutableState.value.copy(support = support)
        }
    }

    internal fun setImporting() = update {
        it.copy(status = VmStatus.Importing, detail = "Importing bundle into filesDir/vms/default …")
    }

    internal fun setBundle(bundle: VmBundle) = update {
        it.copy(status = VmStatus.Stopped, bundle = bundle, detail = "VM bundle imported.")
    }

    internal fun setStatus(status: VmStatus, detail: String? = null) = update {
        it.copy(status = status, detail = detail)
    }

    private inline fun update(transform: (VmUiState) -> VmUiState) {
        mutableState.value = transform(mutableState.value)
        val state = mutableState.value
        Log.i(TAG, "status=${state.status} detail=${state.detail.orEmpty()}")
    }

    private const val TAG = "ViruaisorState"
}
