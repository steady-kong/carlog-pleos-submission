package kr.carlog.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordDetailsTest {
    @Test fun dashboardSummaryPreservesUnknownMileageAndShowsUsefulFuelAndExpenseAmounts() {
        val fuel = RecordEntity(vehicleId = "v", type = "주유", timestamp = 1, odometer = 1200.0, amount = 20.0, cost = 34000, label = "주유소")
        val expense = RecordEntity(vehicleId = "v", type = "지출", timestamp = 2, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 3000, label = "주차")
        val distance = RecordEntity(vehicleId = "v", type = "주행거리", timestamp = 3, odometer = 1300.0, amount = 0.0, cost = 0, label = "계기판")
        assertEquals("주유 주유소 · 20.0 L · 1200.0 km · 34000원", dashboardRecordSummary(fuel))
        assertEquals("지출 주차 · 주행거리 미입력 · 3000원", dashboardRecordSummary(expense))
        assertEquals("주행거리 계기판 · 1300.0 km", dashboardRecordSummary(distance))
    }
    private val fuel = RecordEntity(vehicleId = "v", type = "주유", timestamp = 0,
        odometer = 1000.0, amount = 35.5, cost = 50_000, label = "주유")

    @Test fun fuelShowsVolumeFillAndHoursMode() {
        assertEquals("주유량 35.5 L · 가득 주유 아님", recordDetails(fuel))
        assertEquals("주유량 35.5 L · 가득 주유 · 엔진 가동시간 5.0 시간 (증가분)",
            recordDetails(fuel.copy(full = true, engineHours = 5.0, engineHoursMode = "incremental")))
        assertEquals("주유량 35.5 L · 가득 주유 아님 · 엔진 가동시간 20.0 시간 (교체 후 누적)",
            recordDetails(fuel.copy(engineHours = 20.0)))
    }

    @Test fun expenseAndMaintenanceShowStoredClassification() {
        assertEquals("분류: 주차", recordDetails(fuel.copy(type = "지출", category = "주차")))
        assertEquals("분류: 기타", recordDetails(fuel.copy(type = "지출", category = null)))
        assertEquals("엔진오일", recordDetails(fuel.copy(type = "정비", category = "engine_oil")))
        assertNull(recordDetails(fuel.copy(type = "정비", category = null)))
    }
}
