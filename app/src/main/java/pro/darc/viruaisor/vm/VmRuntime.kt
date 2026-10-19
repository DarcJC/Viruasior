package pro.darc.viruaisor.vm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update as updateState
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
                storage = VmStorageManager(context.applicationContext).state(result.second),
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

    internal fun setStorage(storage: VmStorageState) {
        Log.i("ViruaisorStorage","rootBytes=${storage.rootBytes} busy=${storage.busy} pending=${storage.recoveryPending} mounted=${storage.sharedMounted} detail=${storage.message}")
        update { it.copy(storage = storage) }
    }

    internal fun setStatus(status: VmStatus, detail: String? = null) = update {
        it.copy(status = status, detail = detail)
    }

    internal fun setDisplayModes(modes: DisplayConfigs) = update {
        it.copy(display = it.display.withConfirmed(modes.current).copy(modes=modes.available))
    }

    internal fun confirmDisplayMode(mode: DisplayMode) {
        update { it.copy(display=it.display.withConfirmed(mode)) }
        Log.i(TAG,"DISPLAY_MODE_CONFIRMED width=${mode.size.width} height=${mode.size.height} millihz=${mode.refreshMilliHz}")
    }

    internal fun setPresentedSize(size: DisplaySize) = update {
        if (!it.isActive) it else it.copy(display = it.display.withPresented(size))
    }

    internal fun requestResize(mode: DisplayMode) = update { state ->
        state.copy(display = state.display.copy(requested = mode.takeUnless { it == state.display.actual && it.size == state.display.presented }, error = null))
    }

    internal fun prepareDisplayConfig(config: VmDisplayConfig) = update {
        it.copy(bundle=it.bundle?.copy(display=config))
    }

    internal fun displayFailure(message: String) = update {
        it.copy(display = it.display.copy(requested = null, error = message))
    }

    internal fun resetDisplay() = update { it.copy(display = VmDisplayState()) }

    private inline fun update(transform: (VmUiState) -> VmUiState) {
        mutableState.updateState(transform)
        val state = mutableState.value
        Log.i(TAG, "status=${state.status} detail=${state.detail.orEmpty()}")
    }

    private const val TAG = "ViruaisorState"
}
