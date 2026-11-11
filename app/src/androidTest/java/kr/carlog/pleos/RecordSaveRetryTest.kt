package kr.carlog.pleos

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordSaveRetryTest {
    @Test fun resultLostAfterCommitCanBeRetriedWithoutCreatingDuplicate() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val dao = db.dao()
        val store = ViewModelStore()
        var failAfterCommit = true
        val flaky = object : CarlogDao by dao {
            override suspend fun insertRecordOnce(value: RecordEntity): Boolean {
                val result = dao.insertRecordOnce(value)
                if (failAfterCommit) { failAfterCommit = false; throw IllegalStateException("result lost after commit") }
                return result
            }
        }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, flaky, MockVehicleDataSource()) as T
        })[CarlogViewModel::class.java]
        val job = vm.viewModelScope.coroutineContext[Job]
        try {
            val car = withTimeout(10_000) { vm.vehicleDetails.filterNotNull().first() }
            fun submit() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm.saveRecord("지출", "주차", null, 0.0, 1000, false, "주차", null, null, null, null, null, null, null) { }
            }
            submit()
            withTimeout(10_000) { vm.recordError.filterNotNull().first { it.contains("결과를 확인하지 못했습니다") } }
            withTimeout(10_000) { vm.recordSaving.first { !it } }
            val first = dao.recordsForExport(car.id).single()
            submit()
            withTimeout(10_000) { vm.recordError.first { it == null } }
            withTimeout(10_000) { vm.recordSaving.first { !it } }
            assertEquals(listOf(first), dao.recordsForExport(car.id))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            if (job != null) withTimeout(10_000) { job.join() }
            db.close()
        }
    }
}
