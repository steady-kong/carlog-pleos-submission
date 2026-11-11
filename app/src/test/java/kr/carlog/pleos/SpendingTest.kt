package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject

class SpendingTest {
    @Test fun totalsDoNotOverflowWhenManyValidAmountsAreAdded() {
        val carId = "v"
        val large = 9_007_199_254_740_991L
        val entries = (0 until 1100).map { index ->
            RecordEntity(id = index.toString(), vehicleId = carId, type = "지출", timestamp = 0,
                odometer = index.toDouble(), amount = 0.0, cost = large, label = "비용", category = "기타")
        }
        val result = spendingStats(entries, ZoneId.of("UTC"))
        val expected = java.math.BigInteger.valueOf(large).multiply(java.math.BigInteger.valueOf(1100))
        assertTrue(expected > java.math.BigInteger.valueOf(Long.MAX_VALUE))
        assertEquals(expected, result.total)
        assertEquals(expected, result.other)
        assertEquals(expected, result.byCategory.single().total)
        assertEquals(expected, result.monthly.single().total)
        assertTrue(result.costPerKm!! > 0.0)
    }

    @Test fun spendingMatchesResultsExtractedFromPwaSource() {
        val text = javaClass.getResourceAsStream("/pwa-spending-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val data = JSONObject(text)
        val inputs = data.getJSONArray("records")
        val records = (0 until inputs.length()).map { index ->
            val input = inputs.getJSONObject(index)
            val type = when (input.getString("type")) {
                "fuel" -> "주유"; "maintenance" -> "정비"; "expense" -> "지출"; else -> "주행거리"
            }
            RecordEntity(vehicleId = "v", type = type, timestamp = Instant.parse(input.getString("date")).toEpochMilli(),
                odometer = input.optDouble("odometer", 0.0), odometerKnown = input.has("odometer"),
                amount = if (type == "주유") 1.0 else 0.0, cost = input.getLong("cost"), label = type,
                category = if (input.has("category")) appExpenseCategory(input.getString("category")) else null,
                deletedAt = if (input.optBoolean("deleted")) 1 else null)
        }
        val fixtures = data.getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val expected = fixture.getJSONObject("expected")
            val start = if (fixture.isNull("start")) null else Instant.parse(fixture.getString("start")).toEpochMilli()
            val actual = spendingStats(records, ZoneId.of("UTC"), start)
            assertEquals(expected.getLong("total"), actual.total.toLong())
            assertEquals(expected.getLong("fuel"), actual.fuel.toLong())
            assertEquals(expected.getLong("maintenance"), actual.maintenance.toLong())
            assertEquals(expected.getDouble("distance"), actual.distance, 0.0)
            if (expected.isNull("costPerKm")) assertNull(actual.costPerKm)
            else assertEquals(expected.getDouble("costPerKm"), actual.costPerKm!!, 0.000001)
            val categories = expected.getJSONArray("categories")
            assertEquals((0 until categories.length()).map { categories.getJSONObject(it).getString("label") }, actual.byCategory.map { it.label })
            assertEquals((0 until categories.length()).map { categories.getJSONObject(it).getLong("amount") }, actual.byCategory.map { it.total.toLong() })
            val monthly = expected.getJSONArray("monthly")
            assertEquals((0 until monthly.length()).map { MonthlySpend(monthly.getJSONObject(it).getString("month"), monthly.getJSONObject(it).getLong("total")) }, actual.monthly)
        }
    }

    @Test fun categoriesIncludeFuelAndMaintenanceWithoutMergingSameNamedExpenses() {
        val records = listOf(
            RecordEntity(vehicleId = "v", type = "주유", timestamp = 1, odometer = 1.0, amount = 1.0, cost = 30_000, label = "주유"),
            RecordEntity(vehicleId = "v", type = "정비", timestamp = 2, odometer = 2.0, amount = 0.0, cost = 10_000, label = "정비"),
            RecordEntity(vehicleId = "v", type = "지출", timestamp = 3, odometer = 3.0, amount = 0.0, cost = 20_000, label = "기타", category = "주유"),
            RecordEntity(vehicleId = "v", type = "지출", timestamp = 4, odometer = 4.0, amount = 0.0, cost = 0, label = "주차", category = "주차"),
        )
        val stats = spendingStats(records, ZoneId.of("UTC"))
        assertEquals(60_000L, stats.total.toLong())
        assertEquals(listOf("fuel", "expense:주유", "maintenance"), stats.byCategory.map { it.key })
        assertEquals(listOf(30_000L, 20_000L, 10_000L), stats.byCategory.map { it.total.toLong() })
    }
    @Test fun standaloneMileageAffectsDistanceButNotSpending() {
        val records = listOf(
            RecordEntity(vehicleId = "v", type = "주행거리", timestamp = 1, odometer = 900.0, amount = 0.0, cost = 0, label = "처음"),
            RecordEntity(vehicleId = "v", type = "주유", timestamp = 2, odometer = 1_000.0, amount = 30.0, cost = 50_000, label = "주유"),
            RecordEntity(vehicleId = "v", type = "주행거리", timestamp = 3, odometer = 1_500.0, amount = 0.0, cost = 0, label = "나중"),
            RecordEntity(vehicleId = "v", type = "주행거리", timestamp = 4, odometer = 9_000.0, amount = 0.0, cost = 0, label = "삭제", deletedAt = 5),
        )
        val stats = spendingStats(records, ZoneId.of("UTC"))
        assertEquals(50_000L, stats.total.toLong())
        assertEquals(600.0, stats.distance, 0.0)
        assertEquals(50_000.0 / 600, stats.costPerKm!!, 0.0001)
        assertEquals(1_500.0, maintenanceOdometer(0.0, records, null, false), 0.0)
        assertEquals(500.0, spendingStats(records, ZoneId.of("UTC"), 2).distance, 0.0)
    }
    @Test fun expenseWithoutMileageContributesCostButNotDistance() {
        val records = listOf(
            RecordEntity(vehicleId = "v", type = "주유", timestamp = 1, odometer = 1000.0, amount = 30.0, cost = 50000, label = "주유"),
            RecordEntity(vehicleId = "v", type = "지출", timestamp = 2, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 2000, label = "주차"),
            RecordEntity(vehicleId = "v", type = "정비", timestamp = 3, odometer = 1500.0, amount = 0.0, cost = 10000, label = "정비"),
        )
        val stats = spendingStats(records, ZoneId.of("UTC"))
        assertEquals(62000L, stats.total.toLong())
        assertEquals(500.0, stats.distance, 0.0)
        assertEquals(124.0, stats.costPerKm!!, 0.0)
    }
    @Test fun periodUsesLocalMonthStartAndClampsMonthEndLikePwa() {
        val now = ZonedDateTime.parse("2025-05-31T12:30:00+09:00[Asia/Seoul]")
        assertEquals(Instant.parse("2025-04-30T15:00:00Z").toEpochMilli(), spendingPeriodStart(SpendingPeriod.THIS_MONTH, now))
        assertEquals(Instant.parse("2025-02-28T03:30:00Z").toEpochMilli(), spendingPeriodStart(SpendingPeriod.THREE_MONTHS, now))
        assertNull(spendingPeriodStart(SpendingPeriod.ALL, now))
    }

    @Test fun periodIncludesStartAndFiltersTotalsDistanceAndMonthsTogether() {
        val start = Instant.parse("2025-03-01T00:00:00Z").toEpochMilli()
        val records = listOf(
            RecordEntity(vehicleId = "v", type = "주유", timestamp = start - 1, odometer = 10.0, amount = 1.0, cost = 100, label = "old"),
            RecordEntity(vehicleId = "v", type = "정비", timestamp = start, odometer = 100.0, amount = 0.0, cost = 200, label = "start"),
            RecordEntity(vehicleId = "v", type = "지출", timestamp = start + 1, odometer = 200.0, amount = 0.0, cost = 300, label = "new", category = "통행료"),
        )
        val stats = spendingStats(records, ZoneId.of("UTC"), start)
        assertEquals(500L, stats.total.toLong())
        assertEquals(100.0, stats.distance, 0.0)
        assertEquals(5.0, stats.costPerKm!!, 0.0)
        assertEquals(listOf(MonthlySpend("2025-03", 500L)), stats.monthly)
    }
    @Test fun totalsAndMileageExcludeSoftDeletedRecords() {
        val t = Instant.parse("2025-02-28T15:00:00Z").toEpochMilli()
        val records = listOf(
            RecordEntity(vehicleId = "v", type = "주유", timestamp = t, odometer = 1000.0, amount = 40.0, cost = 60000, label = "fuel"),
            RecordEntity(vehicleId = "v", type = "정비", timestamp = t, odometer = 1500.0, amount = 0.0, cost = 10000, label = "care"),
            RecordEntity(vehicleId = "v", type = "지출", timestamp = t, odometer = 1400.0, amount = 0.0, cost = 2000, label = "toll", category = "통행료"),
            RecordEntity(vehicleId = "v", type = "주유", timestamp = t, odometer = 5000.0, amount = 20.0, cost = 30000, label = "deleted", deletedAt = t),
        )
        val stats = spendingStats(records, ZoneId.of("Asia/Seoul"))
        assertEquals(72000L, stats.total.toLong())
        assertEquals(2000L, stats.byCategory.single { it.key == "expense:통행료" }.total.toLong())
        assertEquals(listOf("주유", "정비", "통행료"), stats.byCategory.map { it.label })
        assertEquals(500.0, stats.distance, 0.0)
        assertEquals(144.0, stats.costPerKm!!, 0.0)
        assertEquals(listOf(MonthlySpend("2025-03", 72000L)), stats.monthly)
    }
}
