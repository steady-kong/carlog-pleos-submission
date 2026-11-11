package kr.carlog.pleos

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real elapsed time on Mock data; no claim of continuous samples or real-car support. */
@RunWith(AndroidJUnit4::class)
class FullEventDemoTest {
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
        compose.setContent { CarlogTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
        compose.waitUntil(15_000) { vm.captureReady.value }
    }

    @After fun cleanup() {
        val job = vm.viewModelScope.coroutineContext[Job]
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        runBlocking { if (job != null) withTimeout(10_000) { job.join() } }
        db.close()
    }

    @Test fun mockWindowPersistsBeforeAndAfterAndShowsReport() {
        assumeTrue("Full two-minute demo is opt-in", InstrumentationRegistry.getArguments().getString("fullEventDemo") == "true")
        compose.onNodeWithText("시뮬레이션 데이터 · MOCK").assertExists()
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNodeWithText("기록 추가").performScrollTo().performClick()
        fun enterFuel(name: String, distance: String, day: String) {
            compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput(name)
            compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput(distance)
            compose.onNodeWithText("날짜 YYYY-MM-DD (비우면 오늘)").performScrollTo().performTextInput(day)
            compose.onNodeWithText("주유량 L").performScrollTo().performTextInput("30")
            compose.onNodeWithText("금액 원").performScrollTo().performTextInput("45000")
            compose.onNodeWithText("기록 저장").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.records.value.any { it.label == name } }
        }
        enterFuel("시연 첫 주유", "12500", "2025-01-01")
        enterFuel("시연 두 번째 주유", "12800", "2025-01-02")
        compose.onNodeWithText("차량 및 통계 상세 보기").performScrollTo().performClick()
        compose.onNodeWithText("신뢰 평균 연비: 10.0 km/L").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("소모품") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput("시연 필터")
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("12500")
        compose.onNodeWithText("교체 주기 km (선택)").performScrollTo().performTextInput("1000")
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.schedules.value.any { it.label == "시연 필터" } }
        compose.onNodeWithText("시연 필터: 700 km 남음", substring = true).performScrollTo().assertIsDisplayed()
        compose.waitUntil(75_000) { (vm.oldestPreEventSampleAge.value ?: 0) >= 59_000 }
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("지금 이벤트 기록").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.events.value.any { it.status == "collecting" } }
        val id = vm.events.value.single().id
        compose.waitUntil(75_000) { vm.events.value.any { it.id == id && it.status == "complete" } }
        val event = vm.events.value.single { it.id == id }
        val samples = runBlocking { db.dao().samplesOnce(id) }
        assertTrue(samples.all { it.timestamp in (event.timestamp - 60_000)..(event.timestamp + 60_000) })
        assertTrue(samples.any { it.timestamp <= event.timestamp - 58_000 })
        assertTrue(samples.any { it.timestamp >= event.timestamp + 58_000 })
        assertTrue(samples.any { it.name == "fuel" })
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNode(hasText("정비") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("항목 / 메모").performScrollTo().performTextInput("시연 점검")
        compose.onNodeWithText("주행거리 km").performScrollTo().performTextInput("12500")
        compose.onNodeWithText("정비 결과 (선택)").performScrollTo().performTextInput("점검 완료")
        val eventOption = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(event.timestamp)) + " · 일반 이벤트"
        compose.onNodeWithText("$eventOption · ID ${event.id} · 메모: 메모 없음").performScrollTo().performClick()
        compose.onNodeWithText("기록 저장").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.records.value.any { it.label == "시연 점검" && it.relatedEventId == id && it.repairOutcome == "점검 완료" } }
        compose.onNodeWithText("이벤트").performClick()
        compose.onNodeWithText("수집 종료").performScrollTo().performClick()
        compose.onNodeWithText("상세 데이터 보기").performScrollTo().performClick()
        compose.onNodeWithText("센서 시계열 · 왼쪽 -60초 / 중앙 발생 / 오른쪽 +60초").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("점은 저장된 관측값이며 점 사이의 변화는 확인되지 않았습니다.").assertExists()
        compose.onNodeWithText("선택 신호 관측 시각 · 발생 대비", substring = true).assertExists()
        compose.onNodeWithText("정비 참고 리포트 보기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("발생 후 마지막 관측값:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("발생 후 마지막 관측값:", substring = true).assertExists()
        compose.onNodeWithText("Carlog 정비 참고 리포트", substring = true).assertTextContains("시연 점검 · 점검 완료", substring = true)
        compose.onNodeWithText("Carlog 정비 참고 리포트", substring = true).assertTextContains("연료 잔량 (fuel) · % · 시각 종류 미확인: 전·시점", substring = true)
        compose.onNodeWithText("Carlog 정비 참고 리포트", substring = true).assertTextContains("발생 대비", substring = true)
        compose.onNodeWithText("닫기").performClick()
    }
}
