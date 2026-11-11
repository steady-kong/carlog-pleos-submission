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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLeaseLifecycleTest {
    @Test fun closingScreenWaitsForSourceCleanupBeforeAnotherCanCollect() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CarlogDatabase::class.java).build()
        val firstStore = ViewModelStore()
        val secondStore = ViewModelStore()
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val source = object : VehicleDataSource {
            override val source = "MOCK"
            override fun signals() = flow<Signal> {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); finishCleanup.await() } }
            }
        }
        fun factory(dataSource: VehicleDataSource) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CarlogViewModel(app, db.dao(), dataSource) as T
        }
        var firstJob: Job? = null
        var secondJob: Job? = null
        try {
            val first = ViewModelProvider(firstStore, factory(source))[CarlogViewModel::class.java]
            firstJob = first.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { started.await() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear() }
            withTimeout(10_000) { cleaning.await() }
            val second = ViewModelProvider(secondStore, factory(MockVehicleDataSource()))[CarlogViewModel::class.java]
            secondJob = second.viewModelScope.coroutineContext[Job]
            withTimeout(10_000) { second.captureTransitioning.first { !it } }
            assertFalse(second.captureReady.value)
            finishCleanup.complete(Unit)
            withTimeout(10_000) { requireNotNull(firstJob).join() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { second.setMockCollection(true) }
            withTimeout(10_000) { second.captureReady.first { it } }
            assertTrue(second.captureReady.value)
        } finally {
            finishCleanup.complete(Unit)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { firstStore.clear(); secondStore.clear() }
            firstJob?.let { withTimeout(10_000) { it.join() } }
            secondJob?.let { withTimeout(10_000) { it.join() } }
            db.close()
        }
    }
}
