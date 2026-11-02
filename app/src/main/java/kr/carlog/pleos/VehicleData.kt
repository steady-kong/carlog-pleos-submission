package kr.carlog.pleos

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

interface VehicleDataSource { val source: String; fun signals(): Flow<Signal> }
/** Deliberately simulated; never represents a real vehicle. */
class MockVehicleDataSource : VehicleDataSource {
    override val source = "MOCK"
    override fun signals(): Flow<Signal> = flow {
        var tick = 0
        while (true) {
            val t = System.currentTimeMillis()
            emit(Signal(t, "odometer", 12500.0 + tick / 3600.0, "km"))
            emit(Signal(t, "fuel", 55.0 - tick / 10000.0, "%"))
            emit(Signal(t, "latitude", 37.5665 + sin(tick / 100.0) * .001, "deg"))
            emit(Signal(t, "longitude", 126.9780 + sin(tick / 100.0) * .001, "deg"))
            tick++; delay(1000)
        }
    }
}
