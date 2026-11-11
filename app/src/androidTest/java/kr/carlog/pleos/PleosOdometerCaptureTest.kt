package kr.carlog.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SDK experiment only; the emulator and its instrumentation process own the SDK instance. */
@RunWith(AndroidJUnit4::class)
class PleosOdometerCaptureTest {
    @Test fun sdkOdometerCanBePreservedAsReceiptTimedEventSample() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit SDK integration probe required", InstrumentationRegistry.getArguments().getString("odometerCaptureProbe") == "true")
        val emulator = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() == "1"
        }
        assumeTrue("Only an emulator may run this probe", emulator)
        val context = instrumentation.targetContext
        assumeTrue("Only the isolated vehicle probe app may access the SDK", context.packageName == "kr.carlog.pleos.vehicleprobe")
        val sdk = Vehicle(context)
        val callbacksOnMain = AtomicBoolean(true)
        val callbacksDrained = CountDownLatch(2)
        val main = Handler(Looper.getMainLooper())
        var requests = 0
        suspend fun readOdometer(): Pair<Double, Long> {
            val observation = CompletableDeferred<Pair<Double, Long>>()
            val firstCallback = AtomicBoolean(true)
            fun record(value: Double?, error: Exception?) {
                if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false)
                else if (firstCallback.compareAndSet(true, false)) main.post { callbacksDrained.countDown() }
                if (error != null || value == null) observation.completeExceptionally(IllegalStateException("Odometer unavailable"))
                else observation.complete(value to System.currentTimeMillis())
            }
            instrumentation.runOnMainSync {
                requests++
                sdk.getOdometer().getCurrentDistanceDriven({ record(it?.toDouble(), null) }, { record(null, it) })
            }
            return withTimeout(15_000) { observation.await() }
        }
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CarlogDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = System.currentTimeMillis()
            override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
        }
        try {
            instrumentation.runOnMainSync { sdk.initialize() }
            val (value, receivedAt) = readOdometer()
            assertTrue(value.isFinite() && value >= 0)
            val car = VehicleEntity(name = "SDK 시험 차량")
            db.dao().insertVehicle(car)
            val capture = EventCapture(scope, RoomEventCaptureStore(db.dao()), clock, { fail(it) })
            capture.accept(Signal(receivedAt, "odometer", value, "km", "RECEIVED"))
            val id = capture.start(car.id, "일반 이벤트", "SDK 에뮬레이터 시험", "VEHICLE")
            delay(5)
            val (afterValue, afterReceivedAt) = readOdometer()
            assertTrue(afterValue.isFinite() && afterValue >= 0)
            capture.accept(Signal(afterReceivedAt, "odometer", afterValue, "km", "RECEIVED"))
            capture.interruptAll()
            val event = requireNotNull(db.dao().eventById(id))
            val samples = db.dao().samplesOnce(id)
            assertEquals("interrupted", event.status)
            assertEquals(2, samples.size)
            assertTrue(receivedAt <= event.timestamp && afterReceivedAt > event.timestamp)
            assertEquals(listOf(receivedAt, afterReceivedAt), samples.map { it.timestamp })
            assertTrue(samples.all { it.name == "odometer" && it.unit == "km" && it.timestampKind == "RECEIVED" })
            val exported = parseEventJson(exportEventJson(event, samples))
            assertEquals("VEHICLE", exported.event.source)
            assertTrue(exported.samples.all { it.timestampKind == "RECEIVED" })
            assertEquals(2, exported.samples.size)
        } finally {
            try {
                // For a non-main callback the SDK exposes no proven drain signal.
                // Do not guess: the isolated instrumentation process owns cleanup.
                if (requests == 0 || (requests == 2 && callbacksOnMain.get() && callbacksDrained.await(2, TimeUnit.SECONDS))) {
                    instrumentation.runOnMainSync { sdk.release() }
                }
            } finally { scope.cancel(); db.close() }
        }
    }
}
