package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VehicleManagementImportTest {
    private fun example(): String = javaClass.getResourceAsStream("/vehicle-records-v1.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun rejected(json: String) {
        try { parseVehicleManagementJson(json); fail("invalid data must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun documentedExampleRoundTripsWithoutChangingMeaning() {
        val parsed = parseVehicleManagementJson(example())
        assertEquals("차량", parsed.vehicle.name)
        assertEquals(listOf("주유", "지출"), parsed.records.map { it.type })
        assertFalse(parsed.records.last().odometerKnown)
        assertEquals("주차", parsed.records.last().category)
        assertEquals("오일", parsed.schedules.single().label)
        val exported = exportVehicleManagementJson(parsed, java.time.Instant.parse("2025-01-01T00:00:00Z").toEpochMilli())
        assertTrue(JSONObject(example()).similar(JSONObject(exported)))
    }

    @Test fun duplicateIdsUnknownFieldsAndCategoryMismatchesAreRejected() {
        val duplicate = JSONObject(example())
        duplicate.getJSONArray("records").put(duplicate.getJSONArray("records").getJSONObject(0))
        rejected(duplicate.toString())
        val extra = JSONObject(example()).put("extra", true)
        rejected(extra.toString())
        val category = JSONObject(example())
        category.getJSONArray("records").getJSONObject(1).put("expenseCategoryCode", "tax")
        rejected(category.toString())
        rejected(example().replaceFirst("{", "{\"format\":\"wrong\","))
    }

    @Test fun unsafeOrFractionalIntegerAndInvalidTimesAreRejected() {
        val badCost = JSONObject(example())
        badCost.getJSONArray("records").getJSONObject(0).put("costKrw", 9_007_199_254_740_992L)
        rejected(badCost.toString())
        rejected(example().replaceFirst("45000", "45000.1"))
        rejected(example().replaceFirst("2025-01-01T00:00:00Z", "2025-01-01T09:00:00+09:00"))
        rejected(example().replaceFirst("2025-01-01T00:00:00Z", "2025-01-01T00:00:00.0001Z"))
    }
}
