package pro.darc.viruaisor.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Same app process as VmService; secrets never enter Intents, saved state or logs. */
internal object GuestPasswordRuntime {
    enum class Status { Idle, Updating, Changed, Failed }
    private val mutableStatus=MutableStateFlow(Status.Idle)
    val status=mutableStatus.asStateFlow()
    private var owner: Any?=null
    private var generation=0
    private var update: ((String,(Boolean)->Unit)->Unit)?=null

    @Synchronized fun bind(owner: Any, update: (String,(Boolean)->Unit)->Unit) {
        this.owner=owner;this.update=update;generation++
        mutableStatus.value=Status.Idle
    }
    @Synchronized fun unbind(owner: Any) {
        if(this.owner !== owner) return
        this.owner=null;update=null;generation++
        mutableStatus.value=Status.Idle
    }
    @Synchronized fun request(password: String): Boolean {
        val send=update ?: return false
        if(mutableStatus.value == Status.Updating) return false
        GuestPasswordProtocol.command(password)
        val current=generation
        mutableStatus.value=Status.Updating
        try {
            send(password) { success ->
                synchronized(this) {
                    if(current == generation) mutableStatus.value=if(success) Status.Changed else Status.Failed
                }
            }
        } catch (_: Exception) {
            if(current == generation) mutableStatus.value=Status.Failed
            return false
        }
        return true
    }
}
