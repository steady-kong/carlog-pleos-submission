package kr.carlog.pleos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VehicleManagementExchangeTest {
    private val car = VehicleEntity(id = "bcf4b06f-7654-4955-8d68-67c55fc01887", name = "차량", initialKm = 1000.0, maker = "현대", fuelType = "ev")
    private val timestamp = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
    private val fuel = RecordEntity(id = "5a18ab5a-0a1e-4c91-b208-208a9e5cbf1d", vehicleId = car.id, type = "주유", timestamp = timestamp,
        odometer = 1200.0, amount = 30.0, cost = 45000, label = "주유", full = true)
    private val expense = RecordEntity(id = "56f54253-f9b3-4c04-90d4-b6bd32b3cfa9", vehicleId = car.id, type = "지출", timestamp = timestamp + 1,
        odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 2000, label = "주차", category = "주차")
    private val schedule = ScheduleEntity(id = "70532abf-1669-47c3-926e-81307cb985ea", vehicleId = car.id, label = "오일", lastKm = 1000.0, intervalKm = 5000.0)

    @Test fun importDoesNotNormalizeLeapSecondsOrTwentyFourHourTimes() {
        val root = JSONObject(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(fuel), emptyList()), timestamp))
        for (invalid in listOf("2016-12-31T23:59:60Z", "2020-01-01T24:00:00Z")) {
            val value = JSONObject(root.toString())
            value.getJSONArray("records").getJSONObject(0).put("occurredAt", invalid)
            try { parseVehicleManagementJson(value.toString()); fail("timestamp must not be normalized") }
            catch (e: ExchangeTimestampException) { assertEquals("차량 기록 1번째 시각", e.location) }
        }
    }

    @Test fun exportPreservesMappedValuesAndUnknownMileage() {
        val json = JSONObject(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(expense, fuel), listOf(schedule)), timestamp))
        val example = javaClass.getResourceAsStream("/vehicle-records-v1.json")!!.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertTrue("documented example must match exported values", json.similar(example))
        assertEquals("carlog-vehicle-records", json.getString("format"))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("2025-01-01T00:00:00Z", json.getString("exportedAt"))
        assertEquals(car.id, json.getJSONObject("vehicle").getString("id"))
        val first = json.getJSONArray("records").getJSONObject(0)
        val second = json.getJSONArray("records").getJSONObject(1)
        assertEquals("fuel", first.getString("type")); assertEquals(30.0, first.getDouble("amountLiters"), 0.0)
        assertTrue(first.getBoolean("fullTank")); assertEquals(45000, first.getInt("costKrw"))
        assertTrue(second.isNull("odometerKm")); assertTrue(second.isNull("amountLiters"))
        assertEquals("parking", second.getString("expenseCategoryCode"))
        assertEquals(5000.0, json.getJSONArray("schedules").getJSONObject(0).getDouble("intervalKm"), 0.0)
    }

    @Test fun unknownCategoryIsPreservedWithoutInventingAWebCode() {
        val custom = expense.copy(category = "사용자 분류")
        val item = JSONObject(exportVehicleManagementJson(VehicleManagementExportData(car, listOf(custom), emptyList()), timestamp)).getJSONArray("records").getJSONObject(0)
        assertEquals("사용자 분류", item.getString("category")); assertTrue(item.isNull("expenseCategoryCode"))
    }

    @Test fun refusesUuidCollisionsAfterNormalization() {
        val duplicateRecord = fuel.copy(id = fuel.id.uppercase())
        val duplicateSchedule = schedule.copy(id = schedule.id.uppercase())
        val invalid = listOf(
            VehicleManagementExportData(car, listOf(fuel, duplicateRecord), emptyList()),
            VehicleManagementExportData(car, emptyList(), listOf(schedule, duplicateSchedule)),
        )
        invalid.forEach { snapshot ->
            try { exportVehicleManagementJson(snapshot, timestamp); fail("ambiguous export must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun rejectsForeignDeletedInvalidUnicodeAndUnsafeAmounts() {
        val invalid = listOf(fuel.copy(vehicleId = "other"), fuel.copy(deletedAt = timestamp),
            fuel.copy(label = "\uD800"), fuel.copy(cost = 9_007_199_254_740_992L))
        invalid.forEach { record ->
            try { exportVehicleManagementJson(VehicleManagementExportData(car, listOf(record), emptyList()), timestamp); fail("invalid export") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
