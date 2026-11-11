package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import org.json.JSONObject

class MaintenanceTest {
    private fun date(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun unknownOdometerKeepsCalendarAndHoursRulesAvailable() {
        val schedule = ScheduleEntity(vehicleId = "v", label = "오일", lastKm = 1000.0,
            intervalKm = 5000.0, intervalMonths = 1, lastDoneAt = date("2025-01-01T00:00:00Z"),
            intervalEngineHours = 100.0, category = "engine_oil")
        val now = date("2025-02-01T00:00:00Z")
        val calendar = scheduledMaintenanceDue(schedule, Double.NaN, now, emptyList(), emptyList(), ZoneOffset.UTC)
        assertEquals("일", calendar.due.unit)
        assertEquals(0.0, calendar.due.remaining!!, 0.0)
        val hours = scheduledMaintenanceDue(schedule.copy(intervalMonths = null), Double.NaN, now,
            listOf(EngineHoursEntry(date("2025-01-15T00:00:00Z"), 100.0)), emptyList(), ZoneOffset.UTC)
        assertEquals("시간", hours.due.unit)
        assertEquals(0.0, hours.due.remaining!!, 0.0)
        val distanceOnly = scheduledMaintenanceDue(schedule.copy(intervalMonths = null, intervalEngineHours = null),
            Double.NaN, now, emptyList(), emptyList(), ZoneOffset.UTC)
        assertNull(distanceOnly.due.remaining)
    }

    @Test fun overflowingDistanceIsFlaggedAndDoesNotHideUsableCalendarRule() {
        val schedule = ScheduleEntity(vehicleId = "v", label = "필터", lastKm = Double.MAX_VALUE,
            intervalKm = Double.MAX_VALUE, intervalMonths = 1, lastDoneAt = date("2025-01-01T00:00:00Z"))
        val result = scheduledMaintenanceDue(schedule, 10.0, date("2025-02-01T00:00:00Z"), emptyList(), emptyList(), ZoneId.of("UTC"))
        assertTrue(result.invalidConfiguration)
        assertEquals("일", result.due.unit)
        assertEquals(0.0, result.due.remaining!!, 0.0)
        val onlyDistance = scheduledMaintenanceDue(schedule.copy(intervalMonths = null), 10.0,
            date("2025-02-01T00:00:00Z"), emptyList(), emptyList(), ZoneId.of("UTC"))
        assertTrue(onlyDistance.invalidConfiguration)
        assertNull(onlyDistance.due.remaining)
    }

    @Test fun incompleteImportedScheduleIsFlaggedEvenWhenDistanceIsUsable() {
        val valid = ScheduleEntity(vehicleId = "v", label = "오일", lastKm = 1000.0, intervalKm = 5000.0)
        assertFalse(invalidScheduleConfiguration(valid))
        val missingDate = valid.copy(intervalMonths = 12)
        val due = scheduledMaintenanceDue(missingDate, 1500.0, 1_000_000L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertEquals(4500.0, due.due.remaining!!, 0.0)
        assertTrue(due.invalidConfiguration)
        assertTrue(invalidScheduleConfiguration(valid.copy(lastKm = -1.0)))
        assertTrue(invalidScheduleConfiguration(valid.copy(intervalKm = 0.0)))
        assertTrue(invalidScheduleConfiguration(valid.copy(intervalEngineHours = 100.0, lastDoneAt = 0L)))
        assertFalse(invalidScheduleConfiguration(valid.copy(intervalEngineHours = 100.0, lastDoneAt = 0L, category = "engine_oil")))
    }

    @Test fun maintenanceMatchesResultsExtractedFromPwaSource() {
        listOf("/pwa-maintenance-golden.json", "/pwa-maintenance-new-york-golden.json").forEach { resource ->
            val text = javaClass.getResourceAsStream(resource)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val document = JSONObject(text)
            val zone = ZoneId.of(document.getString("timezone"))
            val fixtures = document.getJSONArray("fixtures")
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val schedule = fixture.getJSONObject("schedule")
                val expected = fixture.getJSONObject("expected")
                fun number(key: String): Double? = if (schedule.has(key)) schedule.getDouble(key) else null
                val rule = MaintenanceRule(
                    intervalDistance = number("intervalDistance"), lastDoneOdometer = number("lastDoneOdometer"),
                    intervalMonths = if (schedule.has("intervalMonths")) schedule.getLong("intervalMonths") else null,
                    lastDoneAt = if (schedule.has("lastDoneDate")) date(schedule.getString("lastDoneDate")) else null,
                    intervalEngineHours = number("intervalEngineHours"),
                )
                val actual = maintenanceDue(rule, fixture.getDouble("odometer"), date(fixture.getString("now")),
                    if (fixture.has("engineHours")) fixture.getDouble("engineHours") else null, zone)
                val name = fixture.getString("name")
                assertEquals(name, expected.getString("status"), actual.status.name.lowercase(java.util.Locale.ROOT))
                val expectedUnit = when {
                    !expected.isNull("distance") -> "km"
                    !expected.isNull("days") -> "일"
                    expected.getString("reason").contains("시간") -> "시간"
                    else -> null
                }
                val expectedRemaining = when (expectedUnit) {
                    "km" -> expected.getDouble("distance")
                    "일" -> expected.getDouble("days")
                    "시간" -> Regex("^(\\d+)시간 남음$").find(expected.getString("reason"))!!.groupValues[1].toDouble()
                    else -> null
                }
                assertEquals(name, expectedUnit, actual.unit)
                if (expectedRemaining == null) assertNull(name, actual.remaining)
                else assertEquals("$resource $name", expectedRemaining, actual.remaining!!, 0.0)
            }
        }
    }

    @Test fun invalidExternalCalendarIntervalDoesNotCrashOrHideDistanceDue() {
        val schedule = ScheduleEntity(vehicleId = "v", label = "오일", lastKm = 0.0, intervalKm = 1000.0,
            lastDoneAt = 0L, intervalMonths = 9_007_199_254_740_991L)
        val result = scheduledMaintenanceDue(schedule, 1000.0, 0L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertTrue(result.invalidCalendarInterval)
        assertEquals(Due.DUE, result.due.status)
        assertEquals("km", result.due.unit)
        val onlyDate = scheduledMaintenanceDue(schedule.copy(intervalKm = 0.0), 0.0, 0L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertTrue(onlyDate.invalidCalendarInterval)
        assertNull(onlyDate.due.remaining)
        val valid = scheduledMaintenanceDue(schedule.copy(intervalMonths = 12), 0.0, 0L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertFalse(valid.invalidCalendarInterval)
    }

    @Test fun missingEngineHoursAreReportedEvenWhenDistanceIsAvailable() {
        val schedule = ScheduleEntity(vehicleId = "v", label = "오일", lastKm = 1000.0, intervalKm = 10000.0,
            lastDoneAt = 100L, intervalEngineHours = 100.0, category = "engine_oil")
        val missing = scheduledMaintenanceDue(schedule, 2000.0, 200L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertEquals(Due.OK, missing.due.status)
        assertEquals("km", missing.due.unit)
        assertTrue(missing.missingEngineHours)
        val measured = scheduledMaintenanceDue(schedule, 2000.0, 200L, listOf(EngineHoursEntry(150L, 95.0)), emptyList(), ZoneOffset.UTC)
        assertEquals(Due.SOON, measured.due.status)
        assertEquals("시간", measured.due.unit)
        assertFalse(measured.missingEngineHours)
        val noHoursRule = scheduledMaintenanceDue(schedule.copy(intervalEngineHours = null), 2000.0, 200L, emptyList(), emptyList(), ZoneOffset.UTC)
        assertFalse(noHoursRule.missingEngineHours)
    }

    @Test fun overflowingEngineHoursDoNotProduceMisleadingOverdueStatus() {
        val schedule = ScheduleEntity(vehicleId = "v", label = "오일", lastKm = 0.0, intervalKm = 10_000.0,
            lastDoneAt = 0L, intervalEngineHours = 100.0, category = "engine_oil")
        val fuels = listOf(EngineHoursEntry(1, Double.MAX_VALUE, true), EngineHoursEntry(2, Double.MAX_VALUE, true))
        val result = scheduledMaintenanceDue(schedule, 100.0, 3, fuels, emptyList(), ZoneOffset.UTC)
        assertTrue(result.missingEngineHours)
        assertEquals("km", result.due.unit)
        assertEquals(Due.OK, result.due.status)
        val reset = scheduledMaintenanceDue(schedule, 100.0, 4, fuels + EngineHoursEntry(3, 95.0), emptyList(), ZoneOffset.UTC)
        assertFalse(reset.missingEngineHours)
        assertEquals(Due.SOON, reset.due.status)
    }

    @Test fun dashboardPrioritizesUrgencyWithoutComparingDifferentUnits() {
        fun item(name: String, status: Due, remaining: Double, unit: String) = ScheduledDue(
            ScheduleEntity(vehicleId = "v", label = name, lastKm = 0.0, intervalKm = 1.0),
            MaintenanceDue(status, remaining, unit),
        )
        val items = listOf(item("여유", Due.OK, 2000.0, "km"), item("도래 일", Due.DUE, 0.0, "일"),
            item("도래 거리", Due.DUE, -10.0, "km"), item("지남", Due.OVERDUE, -20.0, "시간"))
        assertEquals(listOf("지남", "도래 일", "도래 거리", "여유"), prioritizeMaintenance(items).map { it.schedule.label })
        assertEquals("오늘", maintenanceDueLabel(items[1].due))
        assertEquals("10 km 초과", maintenanceDueLabel(items[2].due))
        assertEquals("2000 km 남음", maintenanceDueLabel(items[0].due))
        assertEquals("지남", dueStatusLabel(Due.OVERDUE))
    }

    @Test fun sameUnitSchedulesShowNearestFirstWithoutComparingDifferentUnits() {
        fun item(name: String, status: Due, remaining: Double?, unit: String?) = ScheduledDue(
            ScheduleEntity(vehicleId = "v", label = name, lastKm = 0.0, intervalKm = 1.0),
            MaintenanceDue(status, remaining, unit),
        )
        val values = listOf(item("거리 멀리", Due.OK, 9000.0, "km"), item("날짜 멀리", Due.OK, 120.0, "일"),
            item("거리 가까이", Due.OK, 2000.0, "km"), item("날짜 가까이", Due.OK, 30.0, "일"),
            item("기록 없음", Due.OK, null, null), item("임박", Due.SOON, 500.0, "km"))
        assertEquals(listOf("임박", "거리 가까이", "날짜 가까이", "거리 멀리", "날짜 멀리", "기록 없음"),
            prioritizeMaintenance(values).map { it.schedule.label })
        val overdue = listOf(item("조금 초과", Due.OVERDUE, -2000.0, "km"), item("많이 초과", Due.OVERDUE, -5000.0, "km"))
        assertEquals(listOf("많이 초과", "조금 초과"), prioritizeMaintenance(overdue).map { it.schedule.label })
    }

    @Test fun monthEndUsesLastDayAndMostUrgentInterval() {
        val rule = MaintenanceRule(intervalDistance = 10_000.0, lastDoneOdometer = 10_000.0,
            intervalMonths = 6, lastDoneAt = date("2024-08-31T00:00:00Z"))
        val status = maintenanceDue(rule, 11_000.0, date("2025-02-28T00:00:00Z"), zone = ZoneOffset.UTC)
        assertEquals(Due.DUE, status.status)
        assertEquals(0.0, status.remaining!!, 0.0)
        assertEquals("일", status.unit)
    }

    @Test fun mixedCumulativeAndIncrementalEngineHoursFollowLatestOilChange() {
        val fuels = listOf(EngineHoursEntry(1, 90.0), EngineHoursEntry(4, 100.0),
            EngineHoursEntry(5, 8.0, incremental = true), EngineHoursEntry(6, 110.0), EngineHoursEntry(7, 3.0, incremental = true))
        val changes = listOf(OilChange(2, 1000.0), OilChange(3, 2000.0), OilChange(8, 3000.0, deleted = true))
        assertEquals(113.0, engineHoursSinceOilChange(fuels, changes, 0), 0.0)
    }

    @Test fun engineHoursUseTenPercentOrFiveHourThreshold() {
        val rule = MaintenanceRule(intervalEngineHours = 100.0)
        assertEquals(Due.SOON, maintenanceDue(rule, 0.0, 0, 90.0).status)
        assertEquals(Due.DUE, maintenanceDue(rule, 0.0, 0, 110.0).status)
        assertEquals(Due.OVERDUE, maintenanceDue(rule, 0.0, 0, 111.0).status)
        assertEquals(Due.SOON, maintenanceDue(MaintenanceRule(intervalEngineHours = 20.0), 0.0, 0, 15.0).status)
    }

    @Test fun scheduleReplacementResetsHoursEvenWhenAnOlderOilRecordExists() {
        val fuels = listOf(EngineHoursEntry(20, 80.0), EngineHoursEntry(40, 5.0, incremental = true))
        val changes = listOf(OilChange(10, 1000.0))
        assertEquals(5.0, engineHoursSinceScheduleReplacement(fuels, changes, 30)!!, 0.0)
        assertNull(engineHoursSinceScheduleReplacement(fuels, changes, 50))
        assertEquals(85.0, engineHoursSinceScheduleReplacement(fuels, changes, null)!!, 0.0)
    }

    @Test fun newerOilMaintenanceCanResetHoursAfterScheduleReplacement() {
        val fuels = listOf(EngineHoursEntry(20, 80.0), EngineHoursEntry(40, 3.0, incremental = true))
        val changes = listOf(OilChange(10, 1000.0), OilChange(30, 2000.0))
        assertEquals(3.0, engineHoursSinceScheduleReplacement(fuels, changes, 15)!!, 0.0)
    }
}
