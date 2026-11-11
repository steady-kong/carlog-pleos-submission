package kr.carlog.pleos.location

import ai.pleos.playground.location.fused.model.LocationData
import org.junit.Assert.*
import org.junit.Test

class LocationObservationTest {
    private fun location(lat: Double, lon: Double, timestamp: Long = 1234) =
        LocationData(lat, lon, 0.0, 0f, 0f, timestamp)

    @Test fun preservesIndependentSdkAndReceiptTimes() {
        val result = requireNotNull(locationObservation(location(37.0, 127.0, 1234), 9876))
        assertEquals(1234L, result.sdkTimestampMillis)
        assertEquals(9876L, result.receivedAtMillis)
        assertEquals(37.0, result.latitude, 0.0)
        assertEquals(127.0, result.longitude, 0.0)
    }

    @Test fun rejectsMissingNonFiniteAndOutOfRangeCoordinates() {
        assertNull(locationObservation(null, 1))
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 90.01 to 0.0, 0.0 to -180.01)) {
            assertNull(locationObservation(location(lat, lon), 1))
        }
        assertNotNull(locationObservation(location(-90.0, 180.0, 0), 1))
    }
}
