package kr.carlog.pleos

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

interface VehicleDataSource { val source: String; fun signals(): Flow<Signal> }
fun emptyMockSignalStatus(collecting: Boolean, transitioning: Boolean): String = when {
    transitioning -> "수집 전환 중 · 신호 없음"
    collecting -> "신호 대기 중"
    else -> "수집 중단 · 신호 없음"
}
/** Mock emits once a second; this threshold describes the mock, not Pleos SDK timing. */
fun mockSignalStatus(signal: Signal, collecting: Boolean, now: Long): String {
    if (!collecting) return "수집 중단 · 마지막 값"
    val age = try { Math.subtractExact(now, signal.timestamp) } catch (_: ArithmeticException) { return "신호 지연 · 마지막 값" }
    return if (age > 10_000 || age < -2_000) "신호 지연 · 마지막 값" else "수집 중"
}
/** Deliberately simulated; never represents a real vehicle. */
class MockVehicleDataSource : VehicleDataSource {
    override val source = "MOCK"
    override fun signals(): Flow<Signal> = flow {
        var tick = 0
        while (true) {
            val t = System.currentTimeMillis()
            emit(Signal(t, "odometer", 12500.0 + tick / 3600.0, "km"))
            emit(Signal(t, "fuel", (55.0 - tick / 10000.0).coerceIn(0.0, 100.0), "%"))
            emit(Signal(t, "ev_battery_soc", 75.0 + 2.0 * sin(tick / 300.0), "%"))
            emit(Signal(t, "latitude", 37.5665 + sin(tick / 100.0) * .001, "deg"))
            emit(Signal(t, "longitude", 126.9780 + sin(tick / 100.0) * .001, "deg"))
            tick++; delay(1000)
        }
    }
}
