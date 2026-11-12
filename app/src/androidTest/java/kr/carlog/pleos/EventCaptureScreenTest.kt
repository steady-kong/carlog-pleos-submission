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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class EventCaptureScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedRequestWhileFirstWriteIsPendingCreatesOnlyOneEvent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val actualDao = db.dao()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val callbacks = AtomicInteger()
        val dao = object : CarlogDao by actualDao {
            override suspend fun startEvent(event: EventEntity, samples: List<SampleEntity>) {
                if (writes.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                actualDao.startEvent(event, samples)
            }
        }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CarlogViewModel(app, dao, MockVehicleDataSource()) as T
        })[CarlogViewModel::class.java]
        try {
            compose.setContent { CarlogTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
            compose.waitUntil(15_000) { vm.captureReady.value }
            compose.onNodeWithText("지금 이벤트 기록").performClick()
            compose.waitUntil(10_000) { entered.isCompleted }
            compose.onNodeWithText("기록 시작 중").assertIsNotEnabled()
            compose.runOnIdle { vm.mark("일반 이벤트", "") { callbacks.incrementAndGet() } }
            assertEquals(1, writes.get())
            release.complete(Unit)
            compose.waitUntil(10_000) { vm.events.value.size == 1 && !vm.markRequesting.value }
            assertEquals(1, writes.get())
            assertEquals(0, callbacks.get())
            compose.onNodeWithText("지금 이벤트 기록").performClick()
            compose.waitUntil(10_000) { vm.events.value.size == 2 }
            assertEquals(2, writes.get())
        } finally {
            release.complete(Unit)
            val job = vm.viewModelScope.coroutineContext[Job]
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { if (job != null) withTimeout(10_000) { job.join() } }
            db.close()
        }
    }

    @Test fun oneTouchRecordsThenCategoryAndMemoCanBeEdited() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CarlogViewModel(app, db.dao(), MockVehicleDataSource()) as T
        })[CarlogViewModel::class.java]
        try {
            compose.setContent { CarlogTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
            compose.waitUntil(15_000) { vm.captureReady.value }
            compose.waitUntil(10_000) { vm.oldestPreEventSampleAge.value != null }
            compose.onNodeWithText("현재 사전 버퍼: 가장 오래된 신호", substring = true).assertExists()
            compose.onNodeWithText("지금 이벤트 기록").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.events.value.isNotEmpty() }
            val event = vm.events.value.single()
            compose.onNodeWithText("사후 데이터 수집 중").assertExists()
            compose.onNodeWithText("아직 기록된 이벤트가 없습니다.").assertDoesNotExist()
            assertEquals("일반 이벤트", event.category)
            assertEquals("", event.memo)
            compose.onNodeWithContentDescription("이벤트 1,", substring = true).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("저장된 사전 신호: 가장 오래된 신호", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("분류·메모 수정").performScrollTo().performClick()
            compose.onNodeWithText("도로 상태").performClick()
            compose.onNodeWithText("메모").performTextInput("노면 요철")
            compose.onNodeWithText("저장").performClick()
            compose.waitUntil(10_000) { vm.events.value.single().category == "도로 상태" && vm.events.value.single().memo == "노면 요철" }
            assertEquals(event.id, vm.events.value.single().id)

        } finally {
            val job = vm.viewModelScope.coroutineContext[Job]
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { if (job != null) withTimeout(10_000) { job.join() } }
            db.close()
        }
    }
}
