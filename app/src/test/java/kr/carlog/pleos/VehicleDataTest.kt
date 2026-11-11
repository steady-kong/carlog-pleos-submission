package kr.carlog.pleos

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VehicleDataTest {
    @Test fun missingMockSignalDistinguishesStoppedStartingAndWaiting() {
        assertEquals("수집 중단 · 신호 없음", emptyMockSignalStatus(false, false))
        assertEquals("수집 전환 중 · 신호 없음", emptyMockSignalStatus(false, true))
        assertEquals("수집 전환 중 · 신호 없음", emptyMockSignalStatus(true, true))
        assertEquals("신호 대기 중", emptyMockSignalStatus(true, false))
    }
    @Test fun delayedOrStoppedMockSignalsAreMarkedAsLastValues() {
        val signal = Signal(100_000, "odometer", 1000.0, "km")
        assertEquals("수집 중", mockSignalStatus(signal, true, 110_000))
        assertEquals("신호 지연 · 마지막 값", mockSignalStatus(signal, true, 110_001))
        assertEquals("수집 중단 · 마지막 값", mockSignalStatus(signal, false, 100_000))
        assertEquals("신호 지연 · 마지막 값", mockSignalStatus(signal, true, 97_999))
        assertEquals("신호 지연 · 마지막 값", mockSignalStatus(signal.copy(timestamp = Long.MIN_VALUE), true, Long.MAX_VALUE))
    }

    @Test fun mockBatteryAndFuelAreDistinctTimestampedSignals() = runTest {
        val source = MockVehicleDataSource()
        val signals = source.signals().take(5).toList()
        assertEquals("MOCK", source.source)
        assertEquals(setOf("odometer", "fuel", "ev_battery_soc", "latitude", "longitude"), signals.map { it.name }.toSet())
        assertEquals(1, signals.map { it.timestamp }.distinct().size)
        val fuel = signals.single { it.name == "fuel" }
        val battery = signals.single { it.name == "ev_battery_soc" }
        assertEquals("%", fuel.unit)
        assertEquals("%", battery.unit)
        assertEquals(55.0, fuel.value, 0.0)
        assertEquals(75.0, battery.value, 0.0)
    }
}
