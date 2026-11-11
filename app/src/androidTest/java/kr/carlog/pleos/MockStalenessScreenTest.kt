package kr.carlog.pleos

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockStalenessScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: CarlogDatabase
    private lateinit var store: ViewModelStore
    private lateinit var vm: CarlogViewModel

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        store = ViewModelStore()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = flow {
                emit(Signal(System.currentTimeMillis(), "odometer", 12500.0, "km"))
                awaitCancellation()
            }
        }
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, db.dao(), source) as T
        })[CarlogViewModel::class.java]
        compose.setContent { CarlogTheme { Screen(vm, uxState = VehicleUxState.Unrestricted) } }
        compose.waitUntil(10_000) { vm.latest.value.containsKey("odometer") }
    }

    @After fun cleanup() { store.clear(); db.close() }

    @Test fun silentMockSourceEventuallyShowsStaleOdometerAndBasis() {
        compose.onNodeWithText("차량 관리").performClick()
        compose.onNodeWithText("차량 및 통계 상세 보기").performScrollTo().performClick()
        compose.onNodeWithText("차량 테스트 도구 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("시뮬레이션 주행거리를 교체 판정에 적용").performScrollTo()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("시뮬레이션 적용", substring = true).assertExists()
        compose.waitUntil(16_000) { (vm.oldestPreEventSampleAge.value ?: 0) > 11_000 }
        compose.onNodeWithText("시뮬레이션 마지막 값 적용", substring = true).assertExists()
        compose.onNodeWithText("신호 지연 · 마지막 값", substring = true).assertExists()
    }
}
