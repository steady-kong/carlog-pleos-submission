package kr.carlog.pleos

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MileageScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: CarlogDatabase
    private lateinit var store: ViewModelStore
    private lateinit var vm: CarlogViewModel

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        store = ViewModelStore()
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CarlogViewModel(app, db.dao(), MockVehicleDataSource()) as T
        })[CarlogViewModel::class.java]
        compose.setContent { MaterialTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
        compose.waitUntil(10_000) { vm.vehicleDetails.value != null }
        compose.onNodeWithText("차량 관리").performClick()
        // The dashboard starts with the detailed statistics and input form collapsed.
        compose.onNodeWithText("기록 주행거리").assertExists()
        compose.onNodeWithText("신뢰 평균 연비:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("항목 / 메모").assertDoesNotExist()
        compose.onNodeWithText("차량 및 통계 상세 보기").performScrollTo().performClick()
        compose.onNodeWithText("기록 추가").performScrollTo().performClick()
        compose.onNodeWithText("항목 / 메모").assertExists()
    }

    @After fun cleanup() {
        val job = vm.viewModelScope.coroutineContext[Job]
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        runBlocking { if (job != null) withTimeout(10_000) { job.join() } }
        db.close()
    }

    @Test fun estimatedMileageIsOnlyUsedForMaintenanceWhenSelected() {
        val car = requireNotNull(vm.vehicleDetails.value)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        runBlocking {
            db.dao().insertRecord(RecordEntity(vehicleId = car.id, type = "주행거리", timestamp = now - 15 * day,
                odometer = 1000.0, amount = 0.0, cost = 0, label = "기록 1"))
            db.dao().insertRecord(RecordEntity(vehicleId = car.id, type = "주행거리", timestamp = now - day,
                odometer = 1140.0, amount = 0.0, cost = 0, label = "기록 2"))
            db.dao().insertSchedule(ScheduleEntity(vehicleId = car.id, label = "시험 필터", lastKm = 1000.0, intervalKm = 200.0))
        }
        compose.waitUntil(10_000) { vm.records.value.size == 2 && vm.schedules.value.isNotEmpty() }
        compose.onNodeWithText("시험 필터: 60 km 남음", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("추정 주행거리를 교체 판정에 적용").assertDoesNotExist()
        compose.onNodeWithText("차량 테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("추정 주행거리를 교체 판정에 적용").performScrollTo()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.onNodeWithText("시험 필터: 50 km 남음", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("교체 판정 후보", substring = true).assertExists()
    }

    @Test fun demoFuelEntriesAndScheduleUpdateDashboard() {
        fun enterFuel(name: String, distance: String, liters: String, day: String) {
            compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput(name)
            compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput(distance)
            compose.onNodeWithText("날짜 YYYY-MM-DD (비우면 오늘)").performScrollTo().performTextInput(day)
            compose.onNodeWithText("주유량 L").performScrollTo().performTextInput(liters)
            compose.onNodeWithText("금액 원").performScrollTo().performTextInput("45000")
            compose.onNodeWithText("기록 저장").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.records.value.any { it.label == name } }
        }
        enterFuel("시연 첫 주유", "12500", "30", "2025-01-01")
        enterFuel("시연 두 번째 주유", "12800", "30", "2025-01-02")
        compose.onNodeWithText("신뢰 평균 연비: 10.0 km/L").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("소모품").performScrollTo().performClick()
        compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput("시연 필터")
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("12500")
        compose.onNodeWithText("교체 주기 km (선택)").performScrollTo().performTextInput("1000")
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.schedules.value.any { it.label == "시연 필터" } }
        compose.onNodeWithText("시연 필터: 700 km 남음", substring = true).assertExists()
    }

    @Test fun walletDistinguishesGeneralExpenseTotalFromItsCategories() {
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val now = System.currentTimeMillis()
        runBlocking {
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "주유", timestamp = now,
                odometer = 100.0, amount = 10.0, cost = 10_000, label = "주유"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "지출", timestamp = now,
                odometer = 100.0, amount = 0.0, cost = 2_000, label = "주차", category = "주차"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "지출", timestamp = now,
                odometer = 100.0, amount = 0.0, cost = 3_000, label = "기타", category = "기타"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "지출", timestamp = now,
                odometer = 100.0, amount = 0.0, cost = 4_000, label = "분류명 충돌", category = "주유"))
        }
        compose.waitUntil(10_000) { vm.records.value.size == 4 }
        compose.onNodeWithText("전체 지출 19000원 · 주유 10000원 · 정비 0원 · 일반 지출 9000원").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반 지출 · 주차 2000원", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반 지출 · 기타 3000원", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반 지출 · 주유 4000원", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun dashboardShowsThreeRecentActiveRecordsWithTheirUsefulValues() {
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val car = VehicleEntity(name = "다른 차량")
        runBlocking {
            db.dao().insertVehicle(car)
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "주행거리", timestamp = 1, odometer = 1000.0, amount = 0.0, cost = 0, label = "오래된 기록"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "정비", timestamp = 2, odometer = 1200.0, amount = 0.0, cost = 20000, label = "정비소"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "지출", timestamp = 3, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 3000, label = "주차"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "주유", timestamp = 4, odometer = 1300.0, amount = 20.0, cost = 34000, label = "주유소"))
            db.dao().insertRecord(RecordEntity(vehicleId = vehicleId, type = "지출", timestamp = 5, odometer = 0.0, amount = 0.0, cost = 1, label = "삭제된 기록", deletedAt = 6))
            db.dao().insertRecord(RecordEntity(vehicleId = car.id, type = "지출", timestamp = 6, odometer = 0.0, amount = 0.0, cost = 1, label = "다른 차량 기록"))
        }
        compose.waitUntil(10_000) { vm.records.value.size == 4 }
        val recent = hasText("최근 기록:", substring = true)
        compose.onNode(recent and hasText("주유 주유소 · 20.0 L · 1300.0 km · 34000원", substring = true)).assertExists()
        compose.onNode(recent and hasText("지출 주차 · 주행거리 미입력 · 3000원", substring = true)).assertExists()
        compose.onNode(recent and hasText("정비 정비소 · 1200.0 km · 20000원", substring = true)).assertExists()
        compose.onAllNodesWithText("최근 기록:", substring = true).assertCountEquals(3)
    }

    @Test fun webBackupPreviewShowsReceiptLinkWarningsWithoutChangingData() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val car = requireNotNull(vm.vehicleDetails.value)
        val existing = RecordEntity(vehicleId = car.id, type = "지출", timestamp = 100, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 100, label = "기존")
        val data = org.json.JSONObject()
            .put("vehicles", org.json.JSONArray().put(org.json.JSONObject().put("id", "web-car").put("nickname", "웹 차량")))
            .put("fuelLogs", org.json.JSONArray().put(org.json.JSONObject().put("id", "fuel-1").put("vehicleId", "web-car").put("receiptImageId", "missing")))
            .put("maintenanceLogs", org.json.JSONArray().put(org.json.JSONObject().put("id", "maintenance-1").put("vehicleId", "web-car")
                .put("items", org.json.JSONArray().put(org.json.JSONObject()).put(org.json.JSONObject()))))
            .put("receiptImages", org.json.JSONArray().put(org.json.JSONObject().put("id", "receipt-1").put("vehicleId", "web-car").put("refType", "fuel").put("refId", "missing")))
        val contents = org.json.JSONObject().put("app", "carlog").put("schemaVersion", 1).put("data", data).toString()
        val file = java.io.File.createTempFile("backup-receipts-", ".json", app.cacheDir)
        try {
            runBlocking { db.dao().insertRecord(existing) }
            file.writeText(contents, Charsets.UTF_8)
            compose.runOnIdle { vm.previewPwaFile(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.backupPreview.value != null && !vm.backupLoading.value }
            compose.onNodeWithText("활성 영수증 중 원본 기록 연결을 확인할 수 없는 항목 전체 확인 불가 (있는 자료 기준 1건)").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("활성 주유·정비·지출 중 영수증 연결을 확인할 수 없는 항목 전체 확인 불가 (있는 자료 기준 1건)").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("활성 정비 중 복수 항목 1건 · 항목 배열 확인 불가 0건", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("분류를 확인할 수 없는 활성 지출 확인 불가").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("활성 주행거리: 직접 입력 확인 불가 · 다른 기록에서 파생 확인 불가 · 출처 미확인 확인 불가").performScrollTo().assertIsDisplayed()
            assertEquals(contents, file.readText(Charsets.UTF_8))
            assertEquals(existing, runBlocking { db.dao().recordById(existing.id) })
            assertEquals(1, runBlocking { db.dao().records(car.id).first().size })
        } finally { file.delete() }
    }

    @Test fun importPickerCancellationMessagesAppearOnPleosProbe() {
        org.junit.Assume.assumeTrue("Pleos 가져오기 선택기 진단은 별도 실행", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("importPickerProbe") == "true")
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("이벤트 JSON 파일 가져오기").performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.exchangeMessage.value?.contains("선택이 취소되었거나") == true }
        compose.onNodeWithText("이벤트 JSON 선택이 취소되었거나", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNodeWithText("차량 테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("차량 관리 JSON 가져오기").performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.vehicleExportMessage.value?.contains("선택이 취소되었거나") == true }
        compose.onNodeWithText("차량 관리 JSON 선택이 취소되었거나", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Carlog 웹 백업 미리보기").performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.backupMessage.value?.contains("선택이 취소되었거나") == true }
        compose.onNodeWithText("웹 백업 선택이 취소되었거나", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun incompleteImportedScheduleShowsWarningBeforeAndAfterImport() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val car = requireNotNull(vm.vehicleDetails.value)
        val schedule = ScheduleEntity(vehicleId = car.id, label = "교체일 미입력", lastKm = 1000.0, intervalKm = 5000.0, intervalMonths = 12)
        val file = java.io.File.createTempFile("schedule-preview-", ".json", app.cacheDir)
        try {
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(car, emptyList(), listOf(schedule)), 100), Charsets.UTF_8)
            compose.runOnIdle { vm.previewVehicleImport(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value != null && !vm.vehicleImportBusy.value }
            compose.onNodeWithText("설정 확인이 필요한 소모품 주기 1건이 있습니다.", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("가져오기 적용").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id } && !vm.vehicleImportBusy.value }
            compose.onNodeWithText("교체 주기 설정이 불완전하거나 유효하지 않습니다.", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(schedule, runBlocking { db.dao().scheduleById(schedule.id) })
            compose.onNodeWithText("누락된 교체일 입력").performScrollTo().performClick()
            compose.onNodeWithText("마지막 교체일 (YYYY-MM-DD)").performTextInput("2020-01-01")
            compose.onNodeWithText("저장").performClick()
            compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id && it.lastDoneAt != null } }
            val updated = requireNotNull(runBlocking { db.dao().scheduleById(schedule.id) })
            assertEquals(schedule.lastKm, updated.lastKm, 0.0)
            assertEquals(java.time.LocalDate.parse("2020-01-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), updated.lastDoneAt!!)
            assertTrue(runBlocking { db.dao().records(car.id).first().isEmpty() })
            compose.onNodeWithText("교체 주기 설정이 불완전하거나 유효하지 않습니다.", substring = true).assertDoesNotExist()
        } finally { file.delete() }
    }

    @Test fun vehicleImportPreviewBlocksDifferentVehicleAndAllowsMatchingVehicle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val current = requireNotNull(vm.vehicleDetails.value)
        val file = java.io.File.createTempFile("vehicle-preview-", ".json", app.cacheDir)
        try {
            val other = current.copy(id = java.util.UUID.randomUUID().toString(), name = "다른 차량")
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(other, emptyList(), emptyList()), 100), Charsets.UTF_8)
            compose.runOnIdle { vm.previewVehicleImport(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value != null && !vm.vehicleImportBusy.value }
            compose.onNodeWithText("파일의 차량을 별도로 선택한 뒤 기록을 가져올 수 있습니다. 기존 차량에 병합하지 않습니다.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("가져오기 적용").assertIsNotEnabled()
            compose.onNodeWithText("취소").performScrollTo().performClick()
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(current.copy(name = "다른 이름"), emptyList(), emptyList()), 100), Charsets.UTF_8)
            compose.runOnIdle { vm.previewVehicleImport(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value != null && !vm.vehicleImportBusy.value }
            compose.onNodeWithText("차량 정보가 다릅니다.", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("가져오기 적용").assertIsNotEnabled()
            compose.onNodeWithText("취소").performScrollTo().performClick()
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(current, emptyList(), emptyList()), 100), Charsets.UTF_8)
            compose.runOnIdle { vm.previewVehicleImport(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value != null && !vm.vehicleImportBusy.value }
            compose.onNodeWithText("가져오기 적용").performScrollTo().assertIsEnabled()
        } finally { file.delete() }
    }

    @Test fun restoreFileVehicleAndReturnToOriginalWithoutOverwritingRecords() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val original = requireNotNull(vm.vehicleDetails.value)
        val old = RecordEntity(vehicleId = original.id, type = "지출", timestamp = 100, odometer = 100.0, amount = 0.0, cost = 1, label = "기존")
        val source = VehicleEntity(name = "가져온 차량")
        val fuel = RecordEntity(vehicleId = source.id, type = "주유", timestamp = 100, odometer = 1000.0, amount = 10.0, cost = 15000, label = "주유")
        val file = java.io.File.createTempFile("vehicle-restore-", ".json", app.cacheDir)
        try {
            runBlocking { db.dao().insertRecord(old) }
            file.writeText(exportVehicleManagementJson(VehicleManagementExportData(source, listOf(fuel), emptyList()), 100), Charsets.UTF_8)
            compose.runOnIdle { vm.previewVehicleImport(android.net.Uri.fromFile(file)) }
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value != null && !vm.vehicleImportBusy.value && vm.captureReady.value }
            compose.onNodeWithText("파일 차량 등록·선택").performScrollTo().performClick()
            compose.onNodeWithText("차량 선택 확인").performClick()
            assertEquals(original.id, vm.vehicleDetails.value?.id)
            compose.onNodeWithText("이벤트").performClick()
            compose.onNodeWithText("테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Mock 수집 중단").performScrollTo().performClick()
            compose.waitUntil(10_000) { !vm.captureReady.value && !vm.captureTransitioning.value }
            compose.onNodeWithText("차량 관리").performClick()
            compose.onNodeWithText("파일 차량 등록·선택").performScrollTo().performClick()
            compose.onNodeWithText("차량 선택 확인").performClick()
            compose.waitUntil(10_000) { vm.vehicleDetails.value?.id == source.id && !vm.vehicleSwitching.value }
            compose.onNodeWithText("가져오기 적용").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { vm.vehicleImportPreview.value == null && !vm.vehicleImportBusy.value }
            assertEquals(fuel, runBlocking { db.dao().recordById(fuel.id) })
            assertEquals(old, runBlocking { db.dao().recordById(old.id) })
            compose.onNodeWithText("이벤트").performClick()
            compose.onNodeWithText("Mock 수집 다시 시작").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.captureReady.value }
            compose.onNodeWithText("Mock 수집 중단").performScrollTo().performClick()
            compose.waitUntil(10_000) { !vm.captureReady.value && !vm.captureTransitioning.value }
            compose.waitUntil(10_000) { vm.vehicles.value.size == 2 }
            compose.onNodeWithText("차량 관리").performClick()
            compose.onNodeWithText("${original.name} · ${original.id} 선택").performScrollTo().performClick()
            compose.onNodeWithText("차량 선택 확인").performClick()
            compose.waitUntil(10_000) { vm.vehicleDetails.value?.id == original.id && !vm.vehicleSwitching.value }
            assertEquals(original.id, runBlocking { db.dao().vehicleForStartup()?.id })
            assertEquals(fuel, runBlocking { db.dao().recordById(fuel.id) })
        } finally { file.delete() }
    }

    @Test fun importedHugeCalendarIntervalShowsWarningAndOtherDueRule() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val schedule = ScheduleEntity(vehicleId = carId, label = "날짜 오류 시험", lastKm = 0.0, intervalKm = 1000.0,
            intervalMonths = 9_007_199_254_740_991L, lastDoneAt = 0L)
        runBlocking { db.dao().insertSchedule(schedule) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id } }
        compose.onNodeWithText("날짜 오류 시험:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("날짜 교체 주기를 계산할 수 없습니다. 주기를 수정해 주세요.").performScrollTo().assertIsDisplayed()
    }

    @Test fun dashboardShowsExactSpendingBeyondLongRange() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        runBlocking {
            repeat(2) { index -> db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "지출", timestamp = 100L + index,
                odometer = index.toDouble(), amount = 0.0, cost = Long.MAX_VALUE, label = "큰 비용", category = "기타")) }
        }
        compose.waitUntil(10_000) { vm.records.value.size == 2 }
        compose.onNodeWithText("전체 지출 18446744073709551614원", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun timelineAndReportDistinguishSimulationFromUnverifiedVehicleSource() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = carId, timestamp = 100_000L, category = "차량 이상", memo = "표시 시험", source = "VEHICLE", status = "complete")
        runBlocking { db.dao().insertEvent(event) }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        compose.onNodeWithText("VEHICLE, 실차 여부 미확인", substring = true).performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText("VEHICLE, 실차 여부 미확인", substring = true).assertCountEquals(2)
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Carlog 정비 참고 리포트", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Carlog 정비 참고 리포트", substring = true).assertTextContains("VEHICLE, 실차 여부 미확인", substring = true)
    }

    @Test fun dashboardShowsThreeNewestVehicleRecords() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        runBlocking {
            (1..4).forEach { index -> db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "주행거리", timestamp = index.toLong(), odometer = index.toDouble(), amount = 0.0, cost = 0, label = "기록$index")) }
        }
        compose.waitUntil(10_000) { vm.records.value.size == 4 }
        compose.onAllNodesWithText("최근 기록:", substring = true).assertCountEquals(3)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        compose.onNodeWithText("최근 기록: ${format.format(java.util.Date(4))} · 주행거리 기록4", substring = true).assertExists()
        compose.onNodeWithText("최근 기록: ${format.format(java.util.Date(1))} · 주행거리 기록1", substring = true).assertDoesNotExist()
    }

    @Test fun vehicleDetailsCanBeEditedWithoutChangingVehicleId() {
        val originalId = requireNotNull(vm.vehicleDetails.value).id
        compose.onNodeWithText("차량 정보 수정").performScrollTo().performClick()
        compose.onNodeWithText("제조사 (선택)").performScrollTo().performTextInput("현대")
        compose.onNodeWithText("모델 (선택)").performScrollTo().performTextInput("아이오닉")
        compose.onNodeWithText("연식 (선택)").performScrollTo().performTextInput("2024")
        compose.onNodeWithText("전기").performScrollTo().performClick()
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.vehicleDetails.value?.model == "아이오닉" }
        assertTrue(vm.vehicleDetails.value?.id == originalId)
        compose.onNodeWithText("현대 · 아이오닉 · 2024년 · 전기").performScrollTo().assertExists()
    }

    @Test fun staleVehicleEditPreservesInputAndObservesOtherChanges() {
        val original = requireNotNull(vm.vehicleDetails.value)
        compose.onNodeWithText("차량 정보 수정").performScrollTo().performClick()
        compose.onNodeWithText("차량 이름").performTextReplacement("내 입력")
        runBlocking { db.dao().updateVehicleInfo(original.id, "다른 화면", original.initialKm, original.maker, original.model, original.year, original.fuelType) }
        compose.waitUntil(10_000) { vm.vehicleDetails.value?.name == "다른 화면" }
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.vehicleError.value?.contains("다른 화면에서 차량 정보가 변경") == true }
        compose.onNodeWithText("차량 이름").assertTextContains("내 입력")
        assertEquals("다른 화면", runBlocking { db.dao().vehicleById(original.id)!!.name })
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("차량 정보 수정").performScrollTo().performClick()
        compose.onNodeWithText("차량 이름").assertTextContains("다른 화면").performTextReplacement("다시 저장")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.vehicleDetails.value?.name == "다시 저장" }
    }

    @Test fun mockCollectionCanBeStoppedAndRestarted() {
        compose.onNodeWithText("이벤트").performClick()
        compose.waitUntil(10_000) { vm.captureReady.value && vm.latest.value.isNotEmpty() }
        compose.onNodeWithText("테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("Mock 수집 중단").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.captureReady.value && !vm.captureTransitioning.value }
        compose.onNodeWithText("사전 신호 수집 중단").assertExists()
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNodeWithText("시뮬레이션 주행거리 수집 중단 · 신호 없음").assertExists()
        compose.onNodeWithText("시뮬레이션 연료 수집 중단 · 신호 없음").assertExists()
        compose.onNodeWithText("시뮬레이션 EV 배터리 수집 중단 · 신호 없음").assertExists()
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("Mock 수집 다시 시작").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.captureReady.value && vm.latest.value.isNotEmpty() }
        compose.onNodeWithText("Mock 수집 중단").assertExists()
    }

    @Test fun failedRecordDeletionKeepsConfirmationAndEditInput() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val record = RecordEntity(vehicleId = carId, type = "주행거리", timestamp = 1, odometer = 100.0, amount = 0.0, cost = 0, label = "거리")
        runBlocking { db.dao().insertRecord(record) }
        compose.waitUntil(10_000) { vm.records.value.any { it.id == record.id } }
        compose.onNodeWithText("편집").performScrollTo().performClick()
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextReplacement("123")
        compose.onNodeWithText("삭제").performScrollTo().performClick()
        runBlocking { db.dao().softDeleteRecord(record.id, carId, System.currentTimeMillis()) }
        compose.onAllNodesWithText("삭제").onLast().performClick()
        compose.waitUntil(10_000) { vm.recordError.value != null }
        compose.onNodeWithText("기록 삭제").assertExists()
        compose.onAllNodesWithText("기록을 삭제하지 못했습니다.", substring = true).onFirst().assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("123").assertExists()
    }

    @Test fun failedScheduleUpdateKeepsDialogAndInput() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val schedule = ScheduleEntity(vehicleId = carId, label = "시험 오일", lastKm = 1_000.0, intervalKm = 10_000.0)
        runBlocking { db.dao().insertSchedule(schedule) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id } }
        compose.onNodeWithText("주기 수정").performScrollTo().performClick()
        compose.onNodeWithText("항목").performTextReplacement("변경할 오일")
        runBlocking { db.dao().softDeleteSchedule(schedule.id, carId, System.currentTimeMillis()) }
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.recordError.value != null }
        compose.onNodeWithText("교체 주기 수정").assertExists()
        compose.onNodeWithText("변경할 오일").assertExists()
        compose.onAllNodesWithText("교체 주기를 수정하지 못했습니다.", substring = true).onFirst().assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("교체 주기 수정").assertDoesNotExist()
    }

    @Test fun standaloneMileageCanBeEnteredEditedAndDeleted() {
        compose.waitUntil(10_000) { vm.latest.value.containsKey("ev_battery_soc") }
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("정차 중 사용").assertExists()
        compose.onNodeWithText("지금 이벤트 기록").assertIsEnabled()
        compose.onNodeWithText("최근 60초 중 가장 오래된 신호:", substring = true).assertExists()
        compose.onNodeWithText("연속 수집을 뜻하지 않음", substring = true).assertExists()
        val previousSignalTime = requireNotNull(vm.latest.value["ev_battery_soc"]).timestamp
        compose.onNodeWithText("차량 관리").performClick()
        compose.waitUntil(10_000) { (vm.latest.value["ev_battery_soc"]?.timestamp ?: 0L) > previousSignalTime }
        compose.onNodeWithText("시뮬레이션 EV 배터리", substring = true).assertExists()
        compose.onNodeWithText("시뮬레이션 연료 ", substring = true).assertExists()
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("Mock 수집을 중단하면 진행 중인 이벤트도 중단되고", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNodeWithText("주행거리", useUnmergedTree = false).performScrollTo().performClick()
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("10000")
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.any { it.type == "주행거리" && it.odometer == 10_000.0 } }
        compose.onNodeWithText("편집").performScrollTo().performClick()
        compose.onNode(hasText("주유") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("지출") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextReplacement("10500")
        compose.onNodeWithText("수정 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.any { it.type == "주행거리" && it.odometer == 10_500.0 } }
        compose.onNodeWithText("삭제").performScrollTo().performClick()
        compose.onNodeWithText("기록 삭제").assertExists()
        compose.onAllNodesWithText("삭제").onLast().performClick()
        compose.waitUntil(10_000) { vm.records.value.none { it.type == "주행거리" } }
    }

    @Test fun walletShowsFuelMaintenanceAndGeneralExpenseCategories() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        runBlocking {
            db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "주유", timestamp = 1, odometer = 100.0, amount = 10.0, cost = 30_000, label = "fuel"))
            db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "정비", timestamp = 2, odometer = 200.0, amount = 0.0, cost = 10_000, label = "repair"))
            db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "지출", timestamp = 3, odometer = 0.0, odometerKnown = false, amount = 0.0, cost = 2_000, label = "toll", category = "통행료"))
        }
        compose.waitUntil(10_000) { vm.records.value.size == 3 }
        compose.onNodeWithText("  주유 30000원").performScrollTo().assertExists()
        compose.onNodeWithText("  정비 10000원").performScrollTo().assertExists()
        compose.onNodeWithText("  일반 지출 · 통행료 2000원").performScrollTo().assertExists()
    }

    @Test fun olderTimelineEventCanBeExpandedAndOpened() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        runBlocking {
            repeat(21) { index ->
                db.dao().insertEvent(EventEntity(vehicleId = carId, timestamp = index.toLong(),
                    category = if (index == 0) "위치 메모" else "일반 이벤트",
                    memo = if (index == 0) "오래된 위치 기록" else "", source = "MOCK", status = "complete"))
            }
        }
        compose.waitUntil(10_000) { vm.events.value.size == 21 }
        compose.onNodeWithText(" · 위치 메모 · 수집 종료", substring = true).assertDoesNotExist()
        compose.onNodeWithText("타임라인 더 보기 (1개 남음)").performScrollTo().performClick()
        compose.onNodeWithText(" · 위치 메모 · 수집 종료", substring = true).performScrollTo().performClick()
        compose.onNode(hasText("발생 ", substring = true) and hasText("오래된 위치 기록", substring = true)).performScrollTo().assertExists()
    }

    @Test fun olderRecordsCanBeExpandedAndEdited() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        runBlocking {
            repeat(21) { index ->
                db.dao().insertRecord(RecordEntity(vehicleId = carId, type = "주행거리", timestamp = index.toLong(), odometer = index.toDouble(), amount = 0.0, cost = 0, label = "history-$index"))
            }
        }
        compose.waitUntil(10_000) { vm.records.value.size == 21 }
        compose.onNodeWithText("history-0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("기록 더 보기 (1개 남음)").performScrollTo().performClick()
        compose.onNodeWithText("history-0", substring = true).performScrollTo().assertExists()
        compose.onAllNodesWithText("편집").onLast().performScrollTo().performClick()
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextReplacement("99")
        compose.onNodeWithText("수정 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.single { it.label == "history-0" }.odometer == 99.0 }
    }

    @Test fun staleEditIsRejectedWithoutLosingFormInput() {
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val original = RecordEntity(vehicleId = vehicleId, type = "주행거리", timestamp = 1, odometer = 1000.0, amount = 0.0, cost = 0, label = "동시 편집")
        runBlocking { db.dao().insertRecord(original) }
        compose.waitUntil(10_000) { vm.records.value.any { it.id == original.id } }
        compose.onNodeWithText("편집").performScrollTo().performClick()
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextReplacement("1100")
        runBlocking { db.dao().updateRecord(original.copy(odometer = 1050.0, label = "다른 화면의 변경")) }
        compose.waitUntil(10_000) { vm.records.value.any { it.label == "다른 화면의 변경" } }
        compose.onNodeWithText("수정 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.recordError.value?.contains("다른 화면에서 기록이 변경") == true }
        compose.onNodeWithText("주행거리 km").performScrollTo().assertTextContains("1100")
        assertEquals(1050.0, runBlocking { db.dao().activeRecord(original.id)!!.odometer }, 0.0)
        compose.onNodeWithText("수정 취소").performScrollTo().performClick()
        assertTrue(vm.recordError.value == null)
    }

    @Test fun staleEventEditPreservesOtherChangesAndUserInput() {
        compose.onNodeWithText("이벤트").performClick()
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = vehicleId, timestamp = 100_000, category = "차량 이상", memo = "원본", source = "MOCK", status = "complete")
        runBlocking { db.dao().insertEvent(event) }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        compose.onNodeWithText(" · 차량 이상 · 수집 종료", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("분류·메모 수정").performScrollTo().performClick()
        compose.onNodeWithText("메모").performTextReplacement("내 입력")
        runBlocking { db.dao().updateEventDetails(event.id, vehicleId, "도로 상태", "다른 화면") }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id && it.memo == "다른 화면" } }
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.eventEditError.value?.contains("다른 화면에서 이벤트가 변경") == true }
        compose.onNodeWithText("메모").assertTextContains("내 입력")
        assertEquals("다른 화면", runBlocking { db.dao().eventById(event.id)?.memo })
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("분류·메모 수정").performScrollTo().performClick()
        compose.onNodeWithText("메모").assertTextContains("다른 화면").performTextReplacement("다시 저장")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id && it.memo == "다시 저장" } }
    }

    @Test fun staleScheduleEditPreservesUserInputAndOtherChange() {
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val schedule = ScheduleEntity(vehicleId = vehicleId, label = "오일", lastKm = 1000.0, intervalKm = 5000.0, updatedAt = 10)
        runBlocking { db.dao().insertSchedule(schedule) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id } }
        compose.onNodeWithText("주기 수정").performScrollTo().performClick()
        compose.onNodeWithText("항목").performTextReplacement("내 입력")
        runBlocking { db.dao().updateSchedule(schedule.id, vehicleId, "다른 화면", 6000.0, null, null, 10) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.label == "다른 화면" } }
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.recordError.value?.contains("교체 주기를 수정하지 못했습니다") == true }
        compose.onNodeWithText("항목").assertTextContains("내 입력")
        assertEquals("다른 화면", runBlocking { db.dao().scheduleById(schedule.id)!!.label })
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("주기 수정").performScrollTo().performClick()
        compose.onNodeWithText("항목").assertTextContains("다른 화면").performTextReplacement("다시 저장")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { vm.schedules.value.any { it.label == "다시 저장" } }
    }

    @Test fun staleScheduleDeleteDoesNotRemoveChangedSchedule() {
        val vehicleId = requireNotNull(vm.vehicleDetails.value).id
        val schedule = ScheduleEntity(vehicleId = vehicleId, label = "오일", lastKm = 1000.0, intervalKm = 5000.0)
        runBlocking { db.dao().insertSchedule(schedule) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.id == schedule.id } }
        compose.onNodeWithText("주기 삭제").performScrollTo().performClick()
        runBlocking { db.dao().updateSchedule(schedule.id, vehicleId, "변경된 오일", 6000.0, null, null, 10) }
        compose.waitUntil(10_000) { vm.schedules.value.any { it.label == "변경된 오일" } }
        compose.onNodeWithText("삭제").performClick()
        compose.waitUntil(10_000) { vm.recordError.value?.contains("교체 주기를 삭제하지 못했습니다") == true }
        assertEquals("변경된 오일", runBlocking { db.dao().scheduleById(schedule.id)!!.label })
        assertEquals(null, runBlocking { db.dao().scheduleById(schedule.id)!!.deletedAt })
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("주기 삭제").performScrollTo().performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.waitUntil(10_000) { vm.schedules.value.none { it.id == schedule.id } }
    }

    @Test fun olderEventCanBeLinkedToNewMaintenance() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val events = (0..10).map { index ->
            EventEntity(vehicleId = carId, timestamp = index * 86_400_000L, category = "차량 이상", memo = "event-$index", source = "MOCK", status = "complete")
        }
        runBlocking { events.forEach { db.dao().insertEvent(it) } }
        compose.waitUntil(10_000) { vm.events.value.size == 11 }
        val oldestText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(events.first().timestamp)) + " · 차량 이상"
        val oldestOption = "$oldestText · ID ${events.first().id} · 메모: event-0"
        compose.onNode(hasText("정비") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText(oldestOption).assertDoesNotExist()
        compose.onNodeWithText("이벤트 더 보기 (1개 남음)").performScrollTo().performClick()
        compose.onNodeWithText(oldestOption).performScrollTo().performClick()
        compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput("repair-old-event")
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("12345")
        compose.onNodeWithText("정비 결과 (선택)").performScrollTo().performTextInput("checked")
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.any { it.label == "repair-old-event" } }
        val repair = vm.records.value.single { it.label == "repair-old-event" }
        assertTrue(repair.relatedEventId == events.first().id && repair.repairOutcome == "checked")
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("$oldestText · 수집 종료", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Carlog 정비 참고 리포트", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Carlog 정비 참고 리포트", substring = true).assertTextContains("repair-old-event · checked", substring = true)
    }

    @Test fun sameSecondEventsCanBeDistinguishedWhenLinkingRepair() {
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val first = EventEntity(vehicleId = carId, timestamp = 100_000, category = "차량 이상", memo = "첫 번째", source = "MOCK", status = "complete")
        val second = first.copy(id = java.util.UUID.randomUUID().toString(), memo = "두 번째")
        runBlocking { db.dao().insertEvent(first); db.dao().insertEvent(second) }
        compose.waitUntil(10_000) { vm.events.value.size == 2 }
        val secondLabel = "ID ${second.id} · 메모: 두 번째"
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("메모: 첫 번째").performScrollTo().assertExists()
        compose.onNodeWithText("메모: 두 번째").performScrollTo().assertExists()
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNode(hasText("정비") and hasClickAction()).performScrollTo().performClick()
        val secondOption = hasText(secondLabel, substring = true) and hasClickAction()
        compose.onAllNodes(secondOption).onLast().performScrollTo().performClick()
        compose.onNodeWithText("연결됨:", substring = true).assertTextContains(second.id, substring = true)
        compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput("확인 점검")
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("12345")
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.any { it.label == "확인 점검" } }
        assertEquals(second.id, vm.records.value.single { it.label == "확인 점검" }.relatedEventId)
    }

    @Test fun denseEventGraphExplainsDownsamplingAndPreservesStoredSamples() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = carId, timestamp = 100_000, category = "차량 이상", memo = "dense", source = "MOCK", status = "complete")
        val samples = (0 until 1_000).map { index -> SampleEntity(event.id, 40_000L + index * 100L, "fuel", index.toDouble(), "%") }
        runBlocking { db.dao().startEvent(event, samples) }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        compose.onNodeWithText(" · 차량 이상 · 수집 종료", substring = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("그래프는 1000개 중", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("각 시간 구간의 처음·마지막·최솟값·최댓값", substring = true).performScrollTo().assertExists()
        assertTrue(runBlocking { db.dao().samplesOnce(event.id).size } == 1_000)
    }

    @Test fun reportPickerCancellationIsShownInScreenProbe() {
        compose.onNodeWithText("이벤트").performClick()
        org.junit.Assume.assumeTrue("Pleos 문서 선택기 화면 진단은 별도 실행", androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("reportScreenPickerProbe") == "true")
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = carId, timestamp = 100_000L, category = "차량 이상", memo = "report picker", source = "MOCK", status = "complete")
        runBlocking { db.dao().insertEvent(event) }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        compose.onNodeWithText(" · 차량 이상 · 수집 종료", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("파일 저장").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("파일 저장").performClick()
        compose.waitUntil(15_000) { vm.reportExportMessage.value?.contains("취소되었거나") == true }
        val messages = compose.onAllNodesWithText("파일 저장이 취소되었거나 문서 선택기를 사용할 수 없습니다.", substring = true)
        assertTrue(messages.fetchSemanticsNodes().indices.any { messages[it].isDisplayed() })
        assertTrue(!vm.reportExportBusy.value)
    }

    @Test fun eventGraphAndReportUseReadableSignalLabels() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = carId, timestamp = 100_000L, category = "차량 이상", memo = "test", source = "MOCK", status = "complete")
        runBlocking {
            db.dao().startEvent(event, listOf(
                SampleEntity(event.id, 99_000L, "fuel", 55.0, "%"),
                SampleEntity(event.id, 99_000L, "ev_battery_soc", 75.0, "%"),
            ))
        }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        val timeline = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(event.timestamp)) + " · 차량 이상 · 수집 종료"
        compose.onNodeWithText(timeline, substring = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("이벤트 발생 시점 또는 이전의 마지막 관측값").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("센서 시계열 · 왼쪽 -60초 / 중앙 발생 / 오른쪽 +60초").assertDoesNotExist()
        compose.onNodeWithText("상세 데이터 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("연료 잔량 (fuel) · 55.0 % · 1970-01-01T00:01:39Z (UTC) · 시각 종류 미확인").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("연료 잔량 (fuel) · 55.0 % · 1970-01-01T00:01:39Z (UTC) · 시각 종류 미확인").assertExists()
        compose.onNodeWithText("연료 잔량 (%) · 시각 종류 미확인").performScrollTo().assertExists()
        compose.onNodeWithText("EV 배터리 충전율 (%) · 시각 종류 미확인").performScrollTo().performClick()
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("현재 등록 차량 정보: 내 차량", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("현재 등록 차량 정보: 내 차량", substring = true).assertExists()
        compose.onNodeWithText("EV 배터리 충전율 (ev_battery_soc) 75.0", substring = true).assertExists()
        compose.onNodeWithText("파일 저장").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("이벤트 목록으로", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("센서 시계열 · 왼쪽 -60초 / 중앙 발생 / 오른쪽 +60초").assertDoesNotExist()
    }

    @Test fun mapActionRequiresAMatchingValidCoordinatePair() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val valid = EventEntity(vehicleId = carId, timestamp = 100_000L, category = "일반 이벤트", memo = "valid location", source = "MOCK", status = "complete")
        val mismatched = EventEntity(vehicleId = carId, timestamp = 200_000L, category = "일반 이벤트", memo = "mismatched location", source = "MOCK", status = "complete")
        runBlocking {
            db.dao().startEvent(valid, listOf(SampleEntity(valid.id, 99_000, "latitude", 37.5, "deg"), SampleEntity(valid.id, 99_000, "longitude", 127.0, "deg")))
            db.dao().startEvent(mismatched, listOf(SampleEntity(mismatched.id, 199_000, "latitude", 37.5, "deg"), SampleEntity(mismatched.id, 198_000, "longitude", 127.0, "deg")))
        }
        compose.waitUntil(10_000) { vm.events.value.size == 2 }
        fun open(event: EventEntity) {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(event.timestamp))
            compose.onNodeWithText(timestamp).performScrollTo().performClick()
        }
        open(valid)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("지도 앱에서 위치 열기").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("37.5, 127.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이벤트 목록으로", substring = true).performScrollTo().performClick()
        open(mismatched)
        compose.onNodeWithText("상세 데이터 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("위치 신호가 부족하거나", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("지도 앱에서 위치 열기").assertDoesNotExist()
    }

    @Test fun graphSeparatesMeasurementAndReceiptTimeSeries() {
        compose.onNodeWithText("이벤트").performClick()
        val carId = requireNotNull(vm.vehicleDetails.value).id
        val event = EventEntity(vehicleId = carId, timestamp = 100_000L, category = "차량 이상", memo = "time kinds", source = "MOCK", status = "complete")
        runBlocking { db.dao().startEvent(event, listOf(
            SampleEntity(event.id, 99_000, "fuel", 10.0, "%", "MEASURED"),
            SampleEntity(event.id, 100_000, "fuel", 90.0, "%", "RECEIVED"),
            SampleEntity(event.id, 101_000, "fuel", 91.0, "%", "RECEIVED"),
        )) }
        compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
        compose.onNodeWithText(" · 차량 이상 · 수집 종료", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("상세 데이터 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("연료 잔량 (fuel) · 10.0 % · 1970-01-01T00:01:39Z (UTC) · 측정 시각").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("연료 잔량 (fuel) · 90.0 % · 1970-01-01T00:01:40Z (UTC) · 수신 시각").assertExists()
        compose.onNodeWithText("연료 잔량 (%) · 측정 시각").performScrollTo().performClick()
        compose.onNodeWithText("범위: 10.0–10.0 %").assertExists()
        compose.onNodeWithText("선택 신호: 발생 전·시점 1개 · 이후 0개", substring = true).assertExists()
        compose.onNodeWithText("연료 잔량 (%) · 수신 시각").performClick()
        compose.onNodeWithText("범위: 90.0–91.0 %").assertExists()
        compose.onNodeWithText("선택 신호: 발생 전·시점 1개 · 이후 1개", substring = true).assertExists()
        compose.onNodeWithText("1970-01-01T00:01:40Z ~ 1970-01-01T00:01:41Z", substring = true).assertExists()
    }

    @Test fun mockEventButtonSavesSamplesAndShowsTimelineAndReport() {
        compose.onNodeWithText("이벤트").performClick()
        compose.waitUntil(10_000) { vm.captureReady.value && vm.latest.value.containsKey("ev_battery_soc") }
        compose.onNodeWithText("지금 이벤트 기록").performClick()
        compose.waitUntil(10_000) { vm.events.value.any { it.status == "collecting" } }
        val eventId = vm.events.value.single().id
        compose.onNodeWithText("· 일반 이벤트 · 수집 중", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("상세 데이터 보기").performScrollTo().performClick()
        compose.onNodeWithText("센서 시계열 · 왼쪽 -60초 / 중앙 발생 / 오른쪽 +60초").performScrollTo().assertExists()
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Carlog 정비 참고 리포트", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("정비 참고 리포트").assertExists()
        compose.onNodeWithText("닫기").performClick()
        compose.waitUntil(75_000) { vm.events.value.any { it.id == eventId && it.status == "complete" } }
        val event = vm.events.value.single { it.id == eventId }
        val samples = runBlocking { db.dao().samplesOnce(eventId) }
        assertTrue(samples.any { it.timestamp <= event.timestamp })
        assertTrue(samples.any { it.timestamp > event.timestamp })
        assertTrue(samples.any { it.name == "ev_battery_soc" && it.unit == "%" })
        assertTrue(samples.any { it.name == "fuel" && it.unit == "%" })
        assertTrue(samples.all { it.timestamp in (event.timestamp - 60_000)..(event.timestamp + 60_000) })
    }
}
