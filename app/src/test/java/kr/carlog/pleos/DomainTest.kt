package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun fuelStatsAvoidNonFiniteRatiosAndOverflowingWeightedSums() {
        val overflowRatio = fuelStats(listOf(Fuel(1, 0.0, 1.0, true), Fuel(2, 1e100, 1e-300, true)))
        assertTrue(overflowRatio.points.isEmpty())
        assertNull(overflowRatio.average)
        val largeAmounts = fuelStats(listOf(Fuel(1, 0.0, 1.0, true), Fuel(2, 100.0, 1e308, true), Fuel(3, 200.0, 1e308, true)))
        assertEquals(2, largeAmounts.trustedCount)
        assertEquals(1e-306, largeAmounts.average!!, 1e-318)
        assertTrue(largeAmounts.recent!!.isFinite())
    }

    @Test fun recordPersistenceValidationRejectsInvalidValuesAndCrossTypeFields() {
        fun valid(type: String, km: Double?, amount: Double, cost: Long, hours: Double? = null,
            mode: String? = null, event: String? = null, result: String? = null) =
            validRecordInput(type, "기록", km, amount, cost, hours, mode, event, result)
        assertTrue(valid("주유", 100.0, 10.0, 20_000, 5.0, "incremental"))
        assertTrue(valid("지출", null, 0.0, 5_000))
        assertTrue(valid("정비", 100.0, 0.0, 10_000, event = "e", result = "확인"))
        assertTrue(valid("주행거리", 100.0, 0.0, 0))
        assertFalse(valid("주유", 100.0, Double.NaN, 20_000))
        assertFalse(valid("주유", 100.0, 0.0, 20_000))
        assertFalse(valid("주유", 100.0, 10.0, -1))
        assertFalse(valid("주유", Double.POSITIVE_INFINITY, 10.0, 1))
        assertFalse(valid("지출", null, 0.0, 1, hours = 1.0))
        assertFalse(valid("주유", 100.0, 10.0, 1, mode = "incremental"))
        assertFalse(valid("주유", 100.0, 10.0, 1, hours = 2.0, mode = "unknown"))
        assertFalse(valid("주유", 100.0, 10.0, 1, event = "e"))
        assertFalse(valid("주행거리", 100.0, 0.0, 1))
        assertFalse(validRecordInput("기타", "기록", 100.0, 0.0, 0, null, null, null, null))
        assertFalse(validRecordInput("정비", "  ", 100.0, 0.0, 0, null, null, null, null))
    }

    @Test fun mockOdometerRequiresExplicitOptInAndValidKm() {
        val record = RecordEntity(vehicleId = "v", type = "주유", timestamp = 1, odometer = 2000.0, amount = 1.0, cost = 1, label = "fuel")
        val missing = record.copy(id = "missing", odometer = 90000.0, odometerKnown = false)
        val deleted = record.copy(id = "deleted", odometer = 80000.0, deletedAt = 2)
        val records = listOf(record, missing, deleted)
        val mock = Signal(1, "odometer", 12500.0, "km")
        assertEquals(2000.0, maintenanceOdometer(1000.0, records, mock, false), 0.0)
        assertEquals(12500.0, maintenanceOdometer(1000.0, records, mock, true), 0.0)
        assertEquals(2000.0, maintenanceOdometer(1000.0, records, mock.copy(unit = "mi"), true), 0.0)
        assertEquals(2000.0, maintenanceOdometer(1000.0, records, mock.copy(value = Double.NaN), true), 0.0)
        assertEquals(2000.0, maintenanceOdometer(1000.0, records, mock, false, 3000.0, false), 0.0)
        assertEquals(3000.0, maintenanceOdometer(1000.0, records, mock, false, 3000.0, true), 0.0)
        assertEquals(12500.0, maintenanceOdometer(1000.0, records, mock, true, 3000.0, true), 0.0)
        assertEquals(2000.0, maintenanceOdometer(1000.0, records, mock, false, Double.NaN, true), 0.0)
    }
    @Test fun dashboardKeepsNewestPerSignalWhenSamplesArriveOutOfOrder() {
        val fuel = Signal(2000, "fuel", 50.0, "%")
        val odometer = Signal(1500, "odometer", 100.0, "km")
        var latest = updateLatestSignals(emptyMap(), fuel)
        latest = updateLatestSignals(latest, odometer)
        latest = updateLatestSignals(latest, Signal(1000, "fuel", 60.0, "%"))
        assertEquals(fuel, latest["fuel"])
        assertEquals(odometer, latest["odometer"])
        val newer = fuel.copy(timestamp = 3000, value = 49.0)
        assertEquals(newer, updateLatestSignals(latest, newer)["fuel"])
    }
    @Test fun fuelWeightedAverageAndRecentFollowWebRules() {
        val stats = fuelStats(listOf(Fuel(1, 100.0, 20.0, true), Fuel(2, 300.0, 20.0, true), Fuel(3, 600.0, 30.0, true), Fuel(4, 700.0, 10.0, false)))
        assertEquals(10.0, stats.average!!, 0.0001)
        assertEquals(10.0, stats.recent!!, 0.0001)
        assertEquals(false, stats.points.last().trusted)
    }
    @Test fun equalOdometerAndInvalidAmountAreIgnored() {
        assertTrue(fuelStats(listOf(Fuel(1, 10.0, 1.0, true), Fuel(2, 10.0, 3.0, true), Fuel(3, 20.0, 0.0, true))).points.isEmpty())
    }
    @Test fun backfilledAndDeletedRecordsMatchOriginalPwaFuelStatistics() {
        // Expected values were extracted by running the PWA's computeFuelStats on these inputs.
        val result = fuelStats(listOf(Fuel(4, 700.0, 10.0, false), Fuel(1, 100.0, 20.0, true),
            Fuel(2, 300.0, 10.0, true), Fuel(3, 600.0, 30.0, true), Fuel(5, 900.0, 10.0, true, deleted = true)))
        assertEquals(12.5, result.average!!, 0.0001)
        assertEquals(10.0, result.recent!!, 0.0001)
        assertEquals(20.0, result.best!!, 0.0001)
        assertEquals(10.0, result.worst!!, 0.0001)
        assertEquals(2, result.trustedCount)
        assertEquals(4, result.fillCount)
        assertEquals(listOf(true, true, false), result.points.map { it.trusted })
    }
    @Test fun partialFillsOnlyOfferAReferenceWithoutTrustedExtremes() {
        val result = fuelStats(listOf(Fuel(2, 300.0, 20.0, false), Fuel(1, 100.0, 10.0, true)))
        assertNull(result.average); assertNull(result.best); assertNull(result.worst)
        assertEquals(10.0, result.recent!!, 0.0001)
        assertEquals(0, result.trustedCount)
        assertEquals(2, result.fillCount)
    }
    @Test fun distanceThresholdBoundaries() {
        assertEquals(Due.OK, distanceDue(1001.0)); assertEquals(Due.SOON, distanceDue(1000.0)); assertEquals(Due.DUE, distanceDue(0.0)); assertEquals(Due.DUE, distanceDue(-1000.0)); assertEquals(Due.OVERDUE, distanceDue(-1001.0))
    }
    @Test fun projectionRequiresFourteenDaysAndCapsDailyDistance() {
        val day = 86_400_000L
        assertEquals(100.0, estimateOdometer(listOf(Reading(0, 0.0), Reading(13 * day, 100.0)), 0.0, 14 * day), 0.0)
        assertEquals(4500.0, estimateOdometer(listOf(Reading(0, 0.0), Reading(14 * day, 4200.0)), 0.0, 15 * day), 0.0)
        assertEquals(3_000_000_300.0, estimateOdometer(listOf(Reading(0, 3_000_000_000.0), Reading(14 * day, 3_000_000_280.0)), 0.0, 15 * day), 0.0)
    }
    @Test fun dashboardProjectionUsesOnlyValidActiveMileageHistory() {
        val day = 86_400_000L
        val first = RecordEntity(vehicleId = "car", type = "주행거리", timestamp = 0, odometer = 100.0, amount = 0.0, cost = 0, label = "입력")
        val later = first.copy(id = "later", timestamp = 14 * day, odometer = 240.0)
        assertNull(projectedOdometer(listOf(first, later.copy(deletedAt = 1)), 15 * day))
        assertNull(projectedOdometer(listOf(first, later.copy(odometerKnown = false)), 15 * day))
        assertEquals(250.0, projectedOdometer(listOf(first, later), 15 * day)!!, 0.0)
    }
    @Test fun eventWindowIncludesBoundariesAndRejectsOutliers() {
        val window = eventWindow(100_000)
        assertTrue(40_000L in window); assertTrue(160_000L in window)
        assertFalse(39_999L in window); assertFalse(160_001L in window)
    }
    @Test fun bufferRetainsInclusiveBoundaryAndOrdersLateSamples() {
        val buffer = RingBufferManager(); buffer.add(Signal(100_000, "a", 1.0, "x")); buffer.add(Signal(40_000, "b", 2.0, "x")); buffer.add(Signal(39_999, "c", 3.0, "x"))
        assertEquals(listOf("b", "a"), buffer.snapshot(100_000).map { it.name })
    }
    @Test fun bufferKeepsDistinctSignalsAtSameTimeAndRejectsConflicts() {
        val buffer = RingBufferManager(100)
        val fuel = Signal(1000, "fuel", 50.0, "%")
        buffer.add(fuel); buffer.add(Signal(1000, "speed", 20.0, "km/h")); buffer.add(fuel)
        assertEquals(listOf("fuel", "speed"), buffer.snapshot(1000).map { it.name })
        try { buffer.add(fuel.copy(unit = "L")); fail("conflicting unit") } catch (_: ConflictingSignalException) { }
        buffer.add(Signal(1100, "fuel", 49.0, "%"))
        assertEquals(3, buffer.snapshot(1100).size)
        buffer.add(Signal(1101, "fuel", 48.0, "%"))
        assertEquals(listOf(1100L, 1101L), buffer.snapshot(1101).map { it.timestamp })
        buffer.clear(); assertTrue(buffer.snapshot(1101).isEmpty())
    }
    @Test fun bufferCapacityRejectsOverflowWithoutEvictingUnexpiredSamples() {
        val buffer = RingBufferManager(windowMs = 100, maxSamples = 2)
        buffer.add(Signal(1_000, "a", 1.0, "x")); buffer.add(Signal(1_000, "b", 2.0, "x"))
        buffer.add(Signal(1_000, "a", 1.0, "x"))
        try { buffer.add(Signal(1_001, "c", 3.0, "x")); fail("capacity exceeded") }
        catch (_: SignalBufferCapacityException) { }
        assertEquals(listOf("a", "b"), buffer.snapshot(1_001).map { it.name })
        buffer.add(Signal(1_101, "c", 3.0, "x"))
        assertEquals(listOf("c"), buffer.snapshot(1_101).map { it.name })
        buffer.clear()
        buffer.add(Signal(1_101, "d", 4.0, "x")); buffer.add(Signal(1_101, "e", 5.0, "x"))
        assertEquals(2, buffer.snapshot(1_101).size)
    }

    @Test fun bufferHandlesExtremeTimestampsAndEmptyRanges() {
        val buffer = RingBufferManager(100)
        buffer.add(Signal(Long.MIN_VALUE + 5, "fuel", 1.0, "%"))
        assertEquals(1, buffer.snapshot(Long.MIN_VALUE + 5).size)
        assertTrue(buffer.snapshotRange(2, 1).isEmpty())
    }
    @Test fun denseGraphKeepsTimeOrderBoundariesAndExtremaWithoutChangingSamples() {
        val at = 100_000L
        val samples = (0..2_000).map { index ->
            SampleEntity("event", at - 60_000 + index * 60L, "signal", when (index) {
                720 -> -999.0
                1_280 -> 999.0
                else -> index.toDouble() / 1_000
            }, "unit")
        }
        val plotted = graphSamples(samples.reversed(), at)
        assertTrue(plotted.size <= 480)
        assertEquals(samples.first(), plotted.first())
        assertEquals(samples.last(), plotted.last())
        assertTrue(plotted.any { it.value == -999.0 })
        assertTrue(plotted.any { it.value == 999.0 })
        assertEquals(plotted.sortedBy { it.timestamp }, plotted)
        assertEquals(2_001, samples.size)
        assertEquals(samples.take(3), graphSamples(samples.take(3).reversed(), at))
    }

    @Test fun chartHandlesConstantTinyAndVeryLargeRanges() {
        assertEquals(0.5, chartFraction(42.0, 42.0, 42.0), 0.0)
        assertEquals(0.0, chartFraction(-1e308, -1e308, 1e308), 0.0)
        assertEquals(0.5, chartFraction(0.0, -1e308, 1e308), 0.0)
        assertEquals(1.0, chartFraction(1e308, -1e308, 1e308), 0.0)
        assertEquals(0.5, chartFraction(1e-300, 0.0, 2e-300), 1e-12)
    }
}
