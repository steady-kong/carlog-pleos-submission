package kr.carlog.pleos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job

/** One capture owner per app process; no claim of cross-process coordination. */
internal object CaptureSessionLease {
    private var owner: Any? = null
    private val screens = mutableSetOf<Any>()

    @Synchronized fun register(candidate: Any) { screens.add(candidate) }
    @Synchronized fun unregister(candidate: Any) { screens.remove(candidate) }
    private var switchOwner: Any? = null
    private val switching = MutableStateFlow(false)

    @Synchronized fun beginVehicleSwitch(candidate: Any): Boolean {
        if (switchOwner != null || owner !== candidate || screens.size != 1 || candidate !in screens) return false
        switchOwner = candidate
        switching.value = true
        return true
    }
    @Synchronized fun endVehicleSwitch(candidate: Any) {
        if (switchOwner === candidate) {
            switchOwner = null
            switching.value = false
        }
    }
    suspend fun awaitVehicleSwitch() { switching.first { !it } }

    @Synchronized fun acquire(candidate: Any): Boolean {
        if (owner != null && owner !== candidate) return false
        owner = candidate
        return true
    }

    @Synchronized fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }

    /** Keep ownership until cancelled children have finished their cleanup. */
    fun unregisterWhenComplete(candidate: Any, job: Job) {
        job.invokeOnCompletion {
            synchronized(this) {
                release(candidate)
                unregister(candidate)
            }
        }
    }
}
