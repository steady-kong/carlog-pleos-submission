package kr.carlog.pleos

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eventKeepsCollectingAcrossTabSwitches() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val store = ViewModelStore()
        val signals = MutableSharedFlow<Signal>(extraBufferCapacity = 8)
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = signals
        }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, db.dao(), source) as T
        })[CarlogViewModel::class.java]
        val job = vm.viewModelScope.coroutineContext[Job]
        try {
            val ux = mutableStateOf(VehicleUxState.Unavailable)
            compose.setContent { CarlogTheme { Screen(vm, uxState = ux.value) } }
            compose.onNodeWithText("차량의 사용 제한 상태를 확인할 수 없어 사용할 수 없습니다.").assertExists()
            compose.onNodeWithText("지금 이벤트 기록").assertDoesNotExist()
            compose.runOnIdle { ux.value = VehicleUxState.Restricted }
            compose.onNodeWithText("차량의 주행 제한이 활성화되어 사용할 수 없습니다.").assertExists()
            compose.onNodeWithText("지금 이벤트 기록").assertDoesNotExist()
            compose.runOnIdle { ux.value = VehicleUxState.Unrestricted }
            compose.waitUntil(10_000) { vm.captureReady.value && signals.subscriptionCount.value > 0 }
            check(signals.tryEmit(Signal(System.currentTimeMillis(), "fuel", 10.0, "%")))
            compose.waitUntil(10_000) { vm.latest.value["fuel"]?.value == 10.0 }
            compose.onNodeWithText("지금 이벤트 기록").performClick()
            compose.waitUntil(10_000) { vm.events.value.any { it.status == "collecting" } }
            val event = vm.events.value.single()
            compose.onNodeWithText("차량 관리").performClick()
            compose.waitUntil(10_000) { System.currentTimeMillis() > event.timestamp }
            val managementTime = System.currentTimeMillis()
            check(signals.tryEmit(Signal(managementTime, "fuel", 20.0, "%")))
            compose.waitUntil(10_000) { runBlocking { db.dao().samplesOnce(event.id).any { it.value == 20.0 } } }
            compose.onNodeWithText("이벤트").performClick()
            compose.waitUntil(10_000) { System.currentTimeMillis() > managementTime }
            check(signals.tryEmit(Signal(System.currentTimeMillis(), "fuel", 30.0, "%")))
            compose.waitUntil(10_000) { runBlocking { db.dao().samplesOnce(event.id).any { it.value == 30.0 } } }
            check(vm.captureReady.value && vm.events.value.single().status == "collecting")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { job?.let { withTimeout(10_000) { it.join() } } }
            db.close()
        }
    }

    @Test fun navigationButtonsShowTheirSections() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val store = ViewModelStore()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = flow<Signal> { awaitCancellation() }
        }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, db.dao(), source) as T
        })[CarlogViewModel::class.java]
        val job = vm.viewModelScope.coroutineContext[Job]
        try {
            compose.setContent { CarlogTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
            compose.onNodeWithText("Carlog").assertExists()
            compose.onNodeWithText("아직 기록된 이벤트가 없습니다.").assertExists()
            compose.onNodeWithText("임시 공유 파일 정리").assertDoesNotExist()
            compose.onNodeWithText("테스트 도구 펼치기").performScrollTo().performClick()
            compose.onNodeWithText("임시 공유 파일 정리").assertExists()
            compose.onNodeWithText("이벤트 JSON 파일 가져오기").assertExists()
            compose.onNodeWithText("테스트 도구 접기").performScrollTo().performClick()
            compose.onNodeWithText("이벤트 JSON 파일 가져오기").assertDoesNotExist()
            compose.onNodeWithText("차량 관리").performClick()
            compose.onNodeWithText("소모품 목록").performClick()
            compose.onNodeWithText("등록된 소모품 주기가 없습니다.").assertExists()
            compose.onNodeWithText("소모품 기록 추가").performClick()
            compose.onNodeWithText("교체 주기 km (선택)").assertExists()
            compose.onNodeWithText("정비 목록").performScrollTo().performClick()
            compose.onNodeWithText("정비 기록 추가").performScrollTo().performClick()
            compose.onNodeWithText("정비 결과 (선택)").assertExists()
            compose.onNodeWithText("주유 목록").performScrollTo().performClick()
            compose.onNodeWithText("주유 기록 추가").performScrollTo().performClick()
            compose.onNodeWithText("주유량 L").assertExists()
            compose.onNodeWithText("기록 입력 접기").performScrollTo().performClick()
            compose.onNodeWithText("최근 기록 없음").assertExists()
            compose.onNodeWithText("차량 기록 없음").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("첫 기록 추가").performScrollTo().performClick()
            compose.onNode(hasText("주유") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("기록 입력 접기").performScrollTo().performClick()
            compose.onNodeWithText("기록 추가").performScrollTo().performClick()
            compose.onNode(hasText("주유") and hasClickAction()).assertIsDisplayed()
            compose.onNodeWithText("이벤트").performClick()
            compose.onNodeWithText("시뮬레이션 데이터 · MOCK").assertIsDisplayed()
            compose.onNodeWithText("지금 이벤트 기록").assertIsDisplayed()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { job?.let { withTimeout(10_000) { it.join() } } }
            db.close()
        }
    }
}
