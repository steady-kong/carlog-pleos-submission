package kr.carlog.pleos

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Unrestricted means the platform reports no distraction optimization requirement;
 * it does not independently establish that the vehicle is safely parked. */
enum class VehicleUxState { Unavailable, Restricted, Unrestricted }

/** A fresh connection is owned by each collector and torn down on cancellation. */
fun vehicleUxRestrictions(context: Context): Flow<VehicleUxState> = callbackFlow {
    trySend(VehicleUxState.Unavailable)
    val main = Handler(Looper.getMainLooper())
    val closed = AtomicBoolean(false)
    var car: Car? = null
    var manager: CarUxRestrictionsManager? = null
    var generation = 0L
    val appContext = context.applicationContext
    try {
    withContext(Dispatchers.Main.immediate) {
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            try {
                car = Car.createCar(appContext, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                    if (!closed.get()) {
                        generation++
                        val callbackGeneration = generation
                        if (!ready) {
                            manager = null
                            trySend(VehicleUxState.Unavailable)
                        } else try {
                            val ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                            if (ux == null) {
                                trySend(VehicleUxState.Unavailable)
                            } else {
                                manager = ux
                                ux.registerListener { restrictions ->
                                    if (!closed.get() && generation == callbackGeneration) trySend(if (restrictions.isRequiresDistractionOptimization) VehicleUxState.Restricted else VehicleUxState.Unrestricted)
                                }
                                val restrictions = ux.currentCarUxRestrictions
                                trySend(if (restrictions.isRequiresDistractionOptimization) VehicleUxState.Restricted else VehicleUxState.Unrestricted)
                            }
                        } catch (_: RuntimeException) {
                            try { manager?.unregisterListener() } catch (_: RuntimeException) { }
                            manager = null
                            trySend(VehicleUxState.Unavailable)
                        }
                    }
                }
            } catch (_: RuntimeException) {
                trySend(VehicleUxState.Unavailable)
            } catch (_: LinkageError) {
                trySend(VehicleUxState.Unavailable)
            }
        }
    }
    awaitClose { }
    } finally {
        closed.set(true)
        main.post {
            try { manager?.unregisterListener() } catch (_: RuntimeException) { }
            try { car?.disconnect() } catch (_: RuntimeException) { }
            manager = null
            car = null
        }
    }
}.distinctUntilChanged()
