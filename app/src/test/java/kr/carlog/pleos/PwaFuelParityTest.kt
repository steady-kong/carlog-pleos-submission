package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PwaFuelParityTest {
    @Test fun fuelStatisticsMatchReadOnlyPwaOutputs() {
        val text = requireNotNull(javaClass.getResourceAsStream("/pwa-fuel-fixtures.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val fixtures = JSONObject(text).getJSONArray("fixtures")
        fun checkNumber(name: String, expected: JSONObject, field: String, actual: Double?) {
            if (expected.isNull(field)) assertNull("$name $field", actual)
            else {
                assertNotNull("$name $field", actual)
                assertEquals("$name $field", expected.getDouble(field), actual!!, 1e-9)
            }
        }
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val name = fixture.getString("name")
            val input = fixture.getJSONArray("fuels")
            val fuels = (0 until input.length()).map { index ->
                val item = input.getJSONObject(index)
                Fuel(Instant.parse(item.getString("date")).toEpochMilli(), item.getDouble("odometer"),
                    item.getDouble("amount"), item.getBoolean("isFullTank"), !item.isNull("deletedAt"))
            }
            val actual = fuelStats(fuels)
            val expected = fixture.getJSONObject("expected")
            checkNumber(name, expected, "avgTrusted", actual.average)
            checkNumber(name, expected, "recent", actual.recent)
            checkNumber(name, expected, "best", actual.best)
            checkNumber(name, expected, "worst", actual.worst)
            assertEquals(name, expected.getInt("trustedCount"), actual.trustedCount)
            assertEquals(name, expected.getInt("fillCount"), actual.fillCount)
            val points = expected.getJSONArray("points")
            assertEquals(name, points.length(), actual.points.size)
            actual.points.forEachIndexed { index, point ->
                val expectedPoint = points.getJSONObject(index)
                assertEquals(name, expectedPoint.getDouble("distance"), point.distance, 1e-9)
                assertEquals(name, expectedPoint.getDouble("amount"), point.amount, 1e-9)
                assertEquals(name, expectedPoint.getDouble("kmPerL"), point.kmPerL, 1e-9)
                assertEquals(name, expectedPoint.getBoolean("trusted"), point.trusted)
            }
        }
    }
}
