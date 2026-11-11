package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PwaOdometerParityTest {
    @Test fun projectionMatchesPwaFixtures() {
        val source = requireNotNull(javaClass.getResourceAsStream("/pwa-odometer-fixtures.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixtures = JSONObject(source).getJSONArray("fixtures")
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val readings = fixture.getJSONArray("readings")
            val active = (0 until readings.length()).map { readings.getJSONObject(it) }
                .filter { it.isNull("deletedAt") }
                .map { Reading(Instant.parse(it.getString("date")).toEpochMilli(), it.getDouble("odometer")) }
            assertEquals(fixture.getString("name"), fixture.getDouble("expected"),
                estimateOdometer(active, fixture.getDouble("initial"), Instant.parse(fixture.getString("now")).toEpochMilli()), 0.0)
        }
    }
}
