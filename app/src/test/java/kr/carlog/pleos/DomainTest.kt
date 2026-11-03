package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun fuelWeightedAverageAndRecentFollowWebRules() {
        val stats = fuelStats(listOf(Fuel(1, 100.0, 20.0, true), Fuel(2, 300.0, 20.0, true), Fuel(3, 600.0, 30.0, true), Fuel(4, 700.0, 10.0, false)))
        assertEquals(10.0, stats.average!!, 0.0001)
        assertEquals(10.0, stats.recent!!, 0.0001)
        assertEquals(false, stats.points.last().trusted)
    }
    @Test fun equalOdometerAndInvalidAmountAreIgnored() {
        assertTrue(fuelStats(listOf(Fuel(1, 10.0, 1.0, true), Fuel(2, 10.0, 3.0, true), Fuel(3, 20.0, 0.0, true))).points.isEmpty())
    }
    @Test fun distanceThresholdBoundaries() {
        assertEquals(Due.OK, distanceDue(1001.0)); assertEquals(Due.SOON, distanceDue(1000.0)); assertEquals(Due.DUE, distanceDue(0.0)); assertEquals(Due.DUE, distanceDue(-1000.0)); assertEquals(Due.OVERDUE, distanceDue(-1001.0))
    }
    @Test fun projectionRequiresFourteenDaysAndCapsDailyDistance() {
        val day = 86_400_000L
        assertEquals(100.0, estimateOdometer(listOf(Reading(0, 0.0), Reading(13 * day, 100.0)), 0.0, 14 * day), 0.0)
        assertEquals(4500.0, estimateOdometer(listOf(Reading(0, 0.0), Reading(14 * day, 4200.0)), 0.0, 15 * day), 0.0)
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
}
