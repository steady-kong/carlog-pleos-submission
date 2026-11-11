package kr.carlog.pleos.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The SDK does not supply sensor measurement time for these getters. */
data class ReceivedReading(val name: String, val value: Double, val unit: String, val receivedAtMillis: Long)
/** SDK enum names remain strings until a versioned cross-platform event contract exists. */
data class ReceivedState(val name: String, val sdkValue: String, val sdkVersion: String, val receivedAtMillis: Long)
class InvalidVehicleReadingException(name: String) : IllegalArgumentException("Invalid $name reading")

internal interface VehiclePort {
    fun initialize()
    fun capability(name: String, complete: (Boolean) -> Unit, failure: (Exception) -> Unit)
    fun read(name: String, complete: (Double?) -> Unit, failure: (Exception) -> Unit)
    fun readState(name: String, complete: (String?) -> Unit, failure: (Exception) -> Unit)
    fun tireThresholdAreas(complete: (Boolean, List<WheelArea>) -> Unit, failure: (Exception) -> Unit) { failure(UnsupportedOperationException("Tire threshold unavailable")) }
    fun tireThresholdCapability(area: WheelArea, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) { failure(UnsupportedOperationException("Tire threshold unavailable")) }
    fun tireThreshold(area: WheelArea, complete: (Double?) -> Unit, failure: (Exception) -> Unit) { failure(UnsupportedOperationException("Tire threshold unavailable")) }
    fun release()
}

/** Experimental one-shot bridge; it is not connected to the app or its event recorder. */
class PleosVehicleReader internal constructor(private val port: VehiclePort, private val now: () -> Long) {
    constructor(context: Context, now: () -> Long = System::currentTimeMillis) : this(SdkVehiclePort(context), now)

    private val lock = Any()
    private var initialized = false
    private var released = false
    private var pending = 0
    private var callsInProgress = 0

    /** Synchronous SDK callbacks can finish before the initiating SDK method returns. */
    private inline fun <T> sdkCall(action: () -> T): T {
        synchronized(lock) { callsInProgress++ }
        return try { action() } finally { synchronized(lock) { callsInProgress-- } }
    }

    fun initialize() = synchronized(lock) {
        check(!released && !initialized) { "Reader already initialized or released" }
        port.initialize()
        initialized = true
    }

    fun readOdometer(onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        requestReading("odometer", "km", onReading, onFailure)

    fun readFuelPercent(onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        requestReading("fuel", "%", onReading, onFailure)

    fun readEvBatteryStateOfCharge(onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        requestReading("ev_battery_soc", "%", onReading, onFailure)

    /** The SDK reports pressed as Boolean; 1 means pressed, 0 means not pressed. */
    fun readBrakePedal(onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        requestReading("brake_pedal_pressed", "boolean", onReading, onFailure)

    /** Returns the SDK areas without interpreting or removing any area values. */
    fun listLowTirePressureThresholdAreas(onAreas: (List<WheelArea>) -> Unit, onFailure: (Exception) -> Unit) {
        val areas = AtomicReference<List<WheelArea>?>(null)
        request("low_tire_pressure_threshold_areas", { _, complete, _ -> complete(areas.get()) },
            { value -> onAreas(requireNotNull(value)) }, onFailure,
            { _, complete, failure -> port.tireThresholdAreas({ readable, supported ->
                if (areas.compareAndSet(null, supported.toList())) complete(readable)
            }, failure) })
    }

    /** Low-pressure warning threshold, not a current tire-pressure measurement. */
    fun readLowTirePressureThreshold(area: WheelArea, onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        request("low_tire_pressure_threshold", { _, complete, failure -> port.tireThreshold(area, complete, failure) }, { value: Double? ->
            if (value != null && (!value.isFinite() || value < 0.0)) onFailure(InvalidVehicleReadingException("low_tire_pressure_threshold"))
            else onReading(value?.let { ReceivedReading("low_tire_pressure_threshold", it, "kPa", now()) })
        }, onFailure, { _, complete, failure -> port.tireThresholdCapability(area, complete, failure) })

    fun readDrivingMode(onState: (ReceivedState?) -> Unit, onFailure: (Exception) -> Unit) =
        requestState("driving_mode", onState, onFailure)

    fun readTurnSignal(onState: (ReceivedState?) -> Unit, onFailure: (Exception) -> Unit) =
        requestState("turn_signal", onState, onFailure)

    fun readEngineOilLevelState(onState: (ReceivedState?) -> Unit, onFailure: (Exception) -> Unit) =
        requestState("engine_oil_level", onState, onFailure)

    fun readBrakeFluidLevelState(onState: (ReceivedState?) -> Unit, onFailure: (Exception) -> Unit) =
        requestState("brake_fluid_level", onState, onFailure)

    private fun requestReading(name: String, unit: String, onReading: (ReceivedReading?) -> Unit, onFailure: (Exception) -> Unit) =
        request(name, port::read, { value ->
            val valid = value == null || (value.isFinite() && when (name) {
                "odometer" -> value >= 0.0
                "fuel", "ev_battery_soc" -> value in 0.0..100.0
                "brake_pedal_pressed" -> value == 0.0 || value == 1.0
                else -> false
            })
            if (!valid) onFailure(InvalidVehicleReadingException(name))
            else onReading(value?.let { ReceivedReading(name, it, unit, now()) })
        }, onFailure)

    private fun requestState(name: String, onState: (ReceivedState?) -> Unit, onFailure: (Exception) -> Unit) =
        request(name, port::readState, { value -> onState(value?.let { ReceivedState(name, it, "2.3.2", now()) }) }, onFailure)

    private fun <T> request(name: String, get: (String, (T?) -> Unit, (Exception) -> Unit) -> Unit, onValue: (T?) -> Unit, onFailure: (Exception) -> Unit,
        capability: (String, (Boolean) -> Unit, (Exception) -> Unit) -> Unit = port::capability) {
        synchronized(lock) {
            check(initialized && !released) { "Reader is not initialized" }
            pending++
        }
        // 0 = capability pending, 1 = getter pending, 2 = terminal.
        val phase = AtomicInteger(0)
        fun finish(expectedPhase: Int, action: () -> Unit) {
            if (!phase.compareAndSet(expectedPhase, 2)) return
            try { action() } finally { synchronized(lock) { pending-- } }
        }
        fun failCapability(error: Exception) = finish(0) { onFailure(error) }
        fun failGetter(error: Exception) = finish(1) { onFailure(error) }
        try {
            sdkCall { capability(name, { readable ->
                if (phase.compareAndSet(0, 1)) {
                    if (!readable) failGetter(UnsupportedOperationException("$name is not readable"))
                    else try {
                        sdkCall { get(name, { value -> finish(1) { onValue(value) } }, ::failGetter) }
                    } catch (e: Exception) { if (phase.get() == 2) throw e else failGetter(e) }
                }
            }, ::failCapability) }
        } catch (e: Exception) { if (phase.get() != 0) throw e else failCapability(e) }
    }

    /** Caller must also wait for SDK callback return; callbacks that never arrive keep this busy. */
    fun release() = synchronized(lock) {
        if (released) return@synchronized
        check(pending == 0 && callsInProgress == 0) { "SDK requests or calls are still pending" }
        if (initialized) port.release()
        released = true
    }
}

private class SdkVehiclePort(context: Context) : VehiclePort {
    private val vehicle = Vehicle(context.applicationContext)
    override fun initialize() = vehicle.initialize()
    override fun capability(name: String, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) {
        when (name) {
            "odometer" -> vehicle.getOdometer().checkCurrentDistanceDrivenCapabilities({ complete(it.accessType.readable()) }, failure)
            "fuel" -> vehicle.getFuel().checkFuelPercentCapabilities({ complete(it.accessType.readable()) }, failure)
            "ev_battery_soc" -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ complete(it.accessType.readable()) }, failure)
            "brake_pedal_pressed" -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ complete(it.accessType.readable()) }, failure)
            "driving_mode" -> vehicle.getDrivingMode().checkDrivingModeStateCapabilities({ complete(it.accessType.readable()) }, failure)
            "turn_signal" -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ complete(it.accessType.readable()) }, failure)
            "engine_oil_level" -> vehicle.getSafety().checkEngineOilLevelStateCapabilities({ config, _ -> complete(config.accessType.readable()) }, failure)
            "brake_fluid_level" -> vehicle.getSafety().checkBrakeFluidLevelStateCapabilities({ config, _ -> complete(config.accessType.readable()) }, failure)
            else -> error("Unknown signal")
        }
    }
    override fun read(name: String, complete: (Double?) -> Unit, failure: (Exception) -> Unit) {
        when (name) {
            "odometer" -> vehicle.getOdometer().getCurrentDistanceDriven({ complete(it?.toDouble()) }, failure)
            "fuel" -> vehicle.getFuel().getFuelPercent({ complete(it?.toDouble()) }, failure)
            "ev_battery_soc" -> vehicle.getEvBattery().getEvBatteryStateOfCharge({ complete(it?.toDouble()) }, failure)
            "brake_pedal_pressed" -> vehicle.getBrake().getCurrentBrakePedalStatus({ complete(it.brakeValue()) }, failure)
            else -> error("Unknown signal")
        }
    }
    override fun readState(name: String, complete: (String?) -> Unit, failure: (Exception) -> Unit) {
        when (name) {
            "driving_mode" -> vehicle.getDrivingMode().getCurrentDrivingModeState({ complete(it?.name) }, failure)
            "turn_signal" -> vehicle.getTurnSignal().getCurrentTurnSignalState({ complete(it?.name) }, failure)
            "engine_oil_level" -> vehicle.getSafety().getEngineOilLevelState({ complete(it?.name) }, failure)
            "brake_fluid_level" -> vehicle.getSafety().getBrakeFluidLevelState({ complete(it?.name) }, failure)
            else -> error("Unknown state")
        }
    }
    override fun tireThresholdAreas(complete: (Boolean, List<WheelArea>) -> Unit, failure: (Exception) -> Unit) {
        vehicle.getTire().checkLowTirePressureCapabilities({ config, supported ->
            complete(config.accessType.readable(), supported)
        }, failure)
    }
    override fun tireThresholdCapability(area: WheelArea, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) {
        vehicle.getTire().checkLowTirePressureCapabilities({ config, supported ->
            complete(config.accessType.readable() && supported.any { it == area })
        }, failure)
    }
    override fun tireThreshold(area: WheelArea, complete: (Double?) -> Unit, failure: (Exception) -> Unit) {
        vehicle.getTire().getLowTirePressure(area, { complete(it?.toDouble()) }, failure)
    }
    override fun release() = vehicle.release()
}

internal fun AccessType.readable() = this == AccessType.READ || this == AccessType.READ_WRITE
internal fun Boolean?.brakeValue(): Double? = this?.let { if (it) 1.0 else 0.0 }
