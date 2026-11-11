package kr.carlog.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only SDK bridge, restricted to an emulator; no coordinates are logged or persisted on disk. */
@RunWith(AndroidJUnit4::class)
class PleosLocationCaptureTest {
    @SuppressLint("MissingPermission")
    @Test fun sdkLocationCanBePreservedAsReceiptTimedEventSamples() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit SDK integration probe required", InstrumentationRegistry.getArguments().getString("locationCaptureProbe") == "true")
        val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() == "1"
        }
        assumeTrue("Only an emulator may run this probe", emulator)
        val targetContext = instrumentation.targetContext
        assumeTrue("Only the isolated location probe app may access location", targetContext.packageName == "kr.carlog.pleos.locationprobe")
        instrumentation.uiAutomation.grantRuntimePermission(targetContext.packageName, "pleos.car.permission.FUSED_LOCATION")
        val sdk = FusedLocation(targetContext)
        val observation = CompletableDeferred<Pair<LocationData, Long>>()
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (location != null) observation.complete(location to System.currentTimeMillis())
                else observation.completeExceptionally(IllegalStateException("No emulator location"))
            }
            override fun onError(exception: Exception) {
                observation.completeExceptionally(IllegalStateException("Location SDK error: ${exception.javaClass.simpleName}"))
            }
        }
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CarlogDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = System.currentTimeMillis()
            override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
        }
        try {
            assertTrue("SDK unsupported", sdk.isSupported())
            instrumentation.runOnMainSync { sdk.initialize(); sdk.getLastKnownLocation(listener) }
            val (location, receivedAt) = withTimeout(15_000) { observation.await() }
            assertTrue(location.latitude.isFinite() && location.latitude in -90.0..90.0)
            assertTrue(location.longitude.isFinite() && location.longitude in -180.0..180.0)
            val car = VehicleEntity(name = "SDK 시험 차량")
            db.dao().insertVehicle(car)
            val capture = EventCapture(scope, RoomEventCaptureStore(db.dao()), clock, { fail(it) })
            capture.accept(Signal(receivedAt, "latitude", location.latitude, "deg", "RECEIVED"))
            capture.accept(Signal(receivedAt, "longitude", location.longitude, "deg", "RECEIVED"))
            val id = capture.start(car.id, "일반 이벤트", "SDK 에뮬레이터 시험", "VEHICLE")
            capture.interruptAll()
            val event = requireNotNull(db.dao().eventById(id))
            val samples = db.dao().samplesOnce(id)
            assertEquals("interrupted", event.status)
            assertEquals(setOf("latitude", "longitude"), samples.map { it.name }.toSet())
            assertTrue(samples.all { it.timestamp == receivedAt && it.timestampKind == "RECEIVED" })
            assertNotNull(eventLocation(samples, id, event.timestamp))
            val exported = parseEventJson(exportEventJson(event, samples))
            assertEquals("VEHICLE", exported.event.source)
            assertTrue(exported.samples.all { it.timestampKind == "RECEIVED" })
        } finally {
            try { instrumentation.runOnMainSync { sdk.release() } }
            finally { scope.cancel(); db.close() }
        }
    }
}
