package kr.carlog.pleos.vehicle

import android.util.Log
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated Pleos emulator probe; callback errors are observations, not simulated successful data. */
@RunWith(AndroidJUnit4::class)
class PleosServiceProbeTest {
    /** No asynchronous requests are in flight for either released instance. */
    @Test fun releaseWithoutRequestsAllowsFreshVehicleToRead() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = AtomicReference<String>()
        val callback = CountDownLatch(1)
        instrumentation.runOnMainSync {
            repeat(2) { iteration ->
                val vehicle = Vehicle(ApplicationProvider.getApplicationContext())
                vehicle.initialize()
                vehicle.release()
                Log.i("CarlogPleosProbe", "empty lifecycle iteration=$iteration release returned")
            }
            val reader = Vehicle(ApplicationProvider.getApplicationContext())
            reader.initialize()
            reader.getOdometer().getCurrentDistanceDriven(
                { value -> result.set("value=$value"); callback.countDown() },
                { error -> result.set("error=${error.javaClass.simpleName}: ${error.message}"); callback.countDown() },
            )
            // The test process owns the reader because its callback return is not observable here.
        }
        assertTrue("Fresh Vehicle did not receive an SDK callback", callback.await(15, TimeUnit.SECONDS))
        Log.i("CarlogPleosProbe", "fresh Vehicle after release ${result.get()}")
    }

    /** Diagnostic only: a sampled idle stack is not a general SDK lifecycle guarantee. */
    @Test fun observedCallbackReturnThenReleaseAllowsAnotherRead() {
        assumeTrue("Requires manualCallbackStackProbe=true", InstrumentationRegistry.getArguments().getString("manualCallbackStackProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(2) { iteration ->
            lateinit var vehicle: Vehicle
            val callback = CountDownLatch(1)
            val callbackThread = AtomicReference<Thread>()
            val outcome = AtomicReference<String>()
            fun record(value: String) {
                callbackThread.set(Thread.currentThread())
                outcome.set(value)
                callback.countDown()
            }
            instrumentation.runOnMainSync {
                vehicle = Vehicle(ApplicationProvider.getApplicationContext())
                vehicle.initialize()
                vehicle.getOdometer().getCurrentDistanceDriven(
                    { value -> record("value=$value") },
                    { error -> record("error=${error.javaClass.simpleName}: ${error.message}") },
                )
            }
            assertTrue("Odometer callback did not arrive", callback.await(15, TimeUnit.SECONDS))
            val thread = requireNotNull(callbackThread.get())
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var observedOutsideCallbacks = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val stack = thread.stackTrace
                if (!thread.isAlive || (stack.isNotEmpty() && stack.none {
                    it.className.startsWith("kr.carlog.pleos.vehicle.PleosServiceProbeTest") ||
                        it.className.startsWith("ai.pleos.playground.vehicle.")
                })) { observedOutsideCallbacks = true; break }
                Thread.sleep(10)
            }
            if (!observedOutsideCallbacks) Log.i("CarlogPleosProbe", "callback thread stack=${thread.stackTrace.map { it.className + "." + it.methodName }}")
            assumeTrue("Callback thread did not show an outside-callback stack", observedOutsideCallbacks)
            Log.i("CarlogPleosProbe", "observed callback return iteration=$iteration ${outcome.get()}")
            instrumentation.runOnMainSync { vehicle.release() }
            Log.i("CarlogPleosProbe", "observed callback release iteration=$iteration returned")
        }
    }

    /** Only release after the callback's main-looper dispatch has returned. */
    @Test fun odometerCallbackCanDrainBeforeReleaseAndReinitialize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = Handler(Looper.getMainLooper())
        repeat(2) { iteration ->
            lateinit var vehicle: Vehicle
            val callbackArrived = CountDownLatch(1)
            val callbackDispatchDrained = CountDownLatch(1)
            val callbackOnMain = AtomicBoolean(false)
            val outcome = AtomicReference<String>()
            fun record(result: String) {
                outcome.set(result)
                callbackOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                if (callbackOnMain.get()) main.post { callbackDispatchDrained.countDown() }
                callbackArrived.countDown()
            }
            instrumentation.runOnMainSync {
                vehicle = Vehicle(ApplicationProvider.getApplicationContext())
                vehicle.initialize()
                vehicle.getOdometer().getCurrentDistanceDriven(
                    { value -> record("value=$value") },
                    { error -> record("error=${error.javaClass.simpleName}: ${error.message}") },
                )
            }
            assertTrue("Odometer callback did not arrive", callbackArrived.await(15, TimeUnit.SECONDS))
            Log.i("CarlogPleosProbe", "release iteration=$iteration ${outcome.get()} callbackOnMain=${callbackOnMain.get()}")
            assumeTrue("Cannot prove callback dispatch completion on a non-main thread", callbackOnMain.get())
            assertTrue("Callback dispatch did not drain", callbackDispatchDrained.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vehicle.release() }
            Log.i("CarlogPleosProbe", "release iteration=$iteration returned")
        }
    }

    @Test fun tireThresholdCapabilityAndReadableAreasReturnCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vehicle: Vehicle
        val capabilityDone = CountDownLatch(1)
        val capability = AtomicReference<Pair<AccessType, List<WheelArea>>?>()
        instrumentation.runOnMainSync {
            vehicle = Vehicle(ApplicationProvider.getApplicationContext())
            vehicle.initialize()
            vehicle.getTire().checkLowTirePressureCapabilities({ config, areas ->
                capability.set(config.accessType to areas)
                Log.i("CarlogPleosProbe", "low tire threshold capability=${config.accessType} areas=${areas.map { it.values.toList() }}")
                capabilityDone.countDown()
            }, { e ->
                Log.i("CarlogPleosProbe", "low tire threshold capability error=${e.javaClass.simpleName}: ${e.message}")
                capabilityDone.countDown()
            })
        }
        assertTrue("Tire capability callback did not arrive", capabilityDone.await(15, TimeUnit.SECONDS))
        val supported = capability.get()
        if (supported != null && supported.first.readable()) {
            supported.second.forEach { area ->
                val readDone = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    vehicle.getTire().getLowTirePressure(area, { value ->
                        Log.i("CarlogPleosProbe", "low tire threshold area=${area.values.toList()} value=$value kPa")
                        readDone.countDown()
                    }, { e ->
                        Log.i("CarlogPleosProbe", "low tire threshold area=${area.values.toList()} error=${e.javaClass.simpleName}: ${e.message}")
                        readDone.countDown()
                    })
                }
                assertTrue("Tire threshold callback did not arrive", readDone.await(15, TimeUnit.SECONDS))
            }
        }
        // Callback outcomes are observations, and thresholds are not measured pressures.
        // Process termination owns cleanup; asynchronous SDK release remains unverified.
    }

    @Test fun experimentalReaderChecksSupportedAreaBeforeReadingTireThreshold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var reader: PleosVehicleReader
        val capabilityDone = CountDownLatch(1)
        val area = AtomicReference<WheelArea?>()
        val areaError = AtomicReference<Exception?>()
        instrumentation.runOnMainSync {
            reader = PleosVehicleReader(ApplicationProvider.getApplicationContext())
            reader.initialize()
            reader.listLowTirePressureThresholdAreas({ areas ->
                area.set(areas.firstOrNull())
                capabilityDone.countDown()
            }, { areaError.set(it); capabilityDone.countDown() })
        }
        assertTrue("Tire capability callback did not arrive", capabilityDone.await(15, TimeUnit.SECONDS))
        assertEquals("Area request failed: ${areaError.get()}", null, areaError.get())
        val supported = requireNotNull(area.get()) { "No readable tire threshold area" }
        val resultDone = CountDownLatch(1)
        val outcome = AtomicReference<ReceivedReading?>()
        val error = AtomicReference<Exception?>()
        instrumentation.runOnMainSync {
            reader.readLowTirePressureThreshold(supported, { outcome.set(it); resultDone.countDown() }, { error.set(it); resultDone.countDown() })
        }
        assertTrue("Tire threshold callback did not arrive", resultDone.await(15, TimeUnit.SECONDS))
        assertEquals("Threshold request failed: ${error.get()}", null, error.get())
        val value = requireNotNull(outcome.get()) { "SDK returned no threshold" }
        assertEquals("low_tire_pressure_threshold", value.name); assertEquals("kPa", value.unit); assertTrue(value.value >= 0.0)
        // SDK callback-return and release remain unverified.
    }

    /** Manual console probe, excluded from ordinary automated runs. */
    @Test fun manualOdometerChangeAndUnregisterProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires manualVehicleProbe=true and the dedicated emulator console",
            InstrumentationRegistry.getArguments().getString("manualVehicleProbe") == "true")
        val changed = CountDownLatch(1)
        val lateCallback = CountDownLatch(1)
        val afterUnregister = AtomicBoolean(false)
        val reading = AtomicReference<Float?>()
        lateinit var vehicle: Vehicle
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                Log.i("CarlogPleosProbe", "manual listener value=$value afterUnregister=${afterUnregister.get()}")
                if (afterUnregister.get()) lateCallback.countDown()
                if (value == 12_345f) changed.countDown()
            }
            override fun onFailed(e: Exception) {
                Log.i("CarlogPleosProbe", "manual listener error=${e.javaClass.simpleName}: ${e.message}")
                if (afterUnregister.get()) lateCallback.countDown()
            }
        }
        instrumentation.runOnMainSync {
            vehicle = Vehicle(ApplicationProvider.getApplicationContext())
            vehicle.initialize()
            vehicle.getOdometer().registerDistanceDriven(listener)
            Log.i("CarlogPleosProbe", "manual READY_FOR_12345")
        }
        assertTrue("No 12345 km notification", changed.await(60, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            vehicle.getOdometer().unregisterDistanceDriven(listener)
            afterUnregister.set(true)
            Log.i("CarlogPleosProbe", "manual UNREGISTERED_SET_54321")
        }
        val gotLateCallback = lateCallback.await(40, TimeUnit.SECONDS)
        val readDone = CountDownLatch(1)
        instrumentation.runOnMainSync {
            vehicle.getOdometer().getCurrentDistanceDriven({ value ->
                reading.set(value)
                Log.i("CarlogPleosProbe", "manual post-unregister getter=$value lateCallback=$gotLateCallback")
                readDone.countDown()
            }, { e ->
                Log.i("CarlogPleosProbe", "manual getter error=${e.message}")
                readDone.countDown()
            })
        }
        assertTrue("Post-unregister getter did not return", readDone.await(15, TimeUnit.SECONDS))
        assertEquals(54_321f, reading.get())
        assertTrue("A listener callback arrived after unregister returned", !gotLateCallback)
        // No release: callback return and service-side unregistration are not proven.
    }

    @Test fun odometerListenerCanBeRegisteredAndUnregistered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observed = CountDownLatch(1)
        lateinit var vehicle: Vehicle
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                Log.i("CarlogPleosProbe", "odometer listener value=$value")
                observed.countDown()
            }
            override fun onFailed(error: Exception) {
                Log.i("CarlogPleosProbe", "odometer listener error=${error.javaClass.simpleName}: ${error.message}")
                observed.countDown()
            }
        }
        instrumentation.runOnMainSync {
            vehicle = Vehicle(ApplicationProvider.getApplicationContext())
            vehicle.initialize()
            vehicle.getOdometer().registerDistanceDriven(listener)
            Log.i("CarlogPleosProbe", "odometer listener registration returned")
        }
        val notified = observed.await(10, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            vehicle.getOdometer().unregisterDistanceDriven(listener)
            Log.i("CarlogPleosProbe", "odometer listener unregistration returned; callback observed=$notified")
        }
        // Registration return is not a service acknowledgment. The short-lived test
        // process owns the instance; callback drain and release remain unverified.
    }

    @Test fun requestedVehicleDomainsReturnAnSdkCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var reader: PleosVehicleReader
        instrumentation.runOnMainSync {
            reader = PleosVehicleReader(ApplicationProvider.getApplicationContext())
            reader.initialize()
        }
        fun <T> probe(name: String, read: ((T?) -> Unit, (Exception) -> Unit) -> Unit) {
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync {
                read({ value ->
                    Log.i("CarlogPleosProbe", "$name result=$value")
                    done.countDown()
                }, { error ->
                    Log.i("CarlogPleosProbe", "$name error=${error.javaClass.simpleName}: ${error.message}")
                    done.countDown()
                })
            }
            assertTrue("$name callback did not arrive; see CarlogPleosProbe logs", done.await(15, TimeUnit.SECONDS))
        }
        probe("odometer", reader::readOdometer)
        probe("fuel", reader::readFuelPercent)
        probe("ev_battery_soc", reader::readEvBatteryStateOfCharge)
        probe("brake", reader::readBrakePedal)
        probe("driving_mode", reader::readDrivingMode)
        probe("turn_signal", reader::readTurnSignal)
        probe("engine_oil", reader::readEngineOilLevelState)
        probe("brake_fluid", reader::readBrakeFluidLevelState)
        // A result callback does not prove the SDK callback has returned. This short-lived
        // instrumentation process owns the instance; lifecycle/release remains unverified.
    }
}
