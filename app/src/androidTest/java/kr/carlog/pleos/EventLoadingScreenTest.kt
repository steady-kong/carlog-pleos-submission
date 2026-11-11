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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventLoadingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingIsNotReportedAsAnEmptyEvent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val gate = CompletableDeferred<Unit>()
        val dao = object : CarlogDao by db.dao() {
            override fun samples(id: String): Flow<List<SampleEntity>> = flow {
                gate.await()
                emitAll(db.dao().samples(id))
            }
        }
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, dao, MockVehicleDataSource()) as T
        })[CarlogViewModel::class.java]
        try {
            compose.setContent { MaterialTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
            compose.waitUntil(10_000) { vm.vehicleDetails.value != null }
            val event = EventEntity(vehicleId = requireNotNull(vm.vehicleDetails.value).id, timestamp = 100_000,
                category = "일반 이벤트", memo = "", source = "MOCK", status = "complete")
            runBlocking { db.dao().insertEvent(event) }
            compose.waitUntil(10_000) { vm.events.value.any { it.id == event.id } }
            compose.onNodeWithText("수집 종료").performScrollTo().performClick()
            compose.onNodeWithText("신호를 불러오는 중").assertExists()
            compose.onNodeWithText("기록된 신호가 없습니다.").assertDoesNotExist()
            gate.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("기록된 신호가 없습니다.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("저장된 신호 없음").assertExists()
        } finally {
            gate.complete(Unit)
            val job = vm.viewModelScope.coroutineContext[Job]
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            runBlocking { if (job != null) withTimeout(10_000) { job.join() } }
            db.close()
        }
    }
}
