package kr.carlog.pleos.vehicle

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PleosVehicleReaderTest {
    private class FakePort : VehiclePort {
        var released = 0
        var readCount = 0
        var capabilityName = ""
        var stateName = ""
        lateinit var capabilityDone: (Boolean) -> Unit
        lateinit var capabilityFailed: (Exception) -> Unit
        lateinit var readDone: (Double?) -> Unit
        lateinit var readFailed: (Exception) -> Unit
        lateinit var stateDone: (String?) -> Unit
        override fun initialize() = Unit
        override fun capability(name: String, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) {
            capabilityName = name
            capabilityDone = complete; capabilityFailed = failure
        }
        override fun read(name: String, complete: (Double?) -> Unit, failure: (Exception) -> Unit) {
            readCount++; readDone = complete; readFailed = failure
        }
        override fun readState(name: String, complete: (String?) -> Unit, failure: (Exception) -> Unit) {
            readCount++; stateName = name; stateDone = complete; readFailed = failure
        }
        override fun release() { released++ }
    }

    @Test fun tireAreaDiscoveryPreservesSdkValuesAndIgnoresDuplicateCallbacks() {
        val wheel = ai.pleos.playground.vehicle.api.model.area.WheelArea(
            ai.pleos.playground.vehicle.constant.area.WheelValue.UNKNOWN,
            ai.pleos.playground.vehicle.constant.area.WheelValue.LEFT_FRONT)
        lateinit var callback: (Boolean, List<ai.pleos.playground.vehicle.api.model.area.WheelArea>) -> Unit
        val fake = FakePort()
        val port = object : VehiclePort by fake {
            override fun tireThresholdAreas(complete: (Boolean, List<ai.pleos.playground.vehicle.api.model.area.WheelArea>) -> Unit, failure: (Exception) -> Unit) { callback = complete }
        }
        val reader = PleosVehicleReader(port) { 42L }
        reader.initialize()
        val received = mutableListOf<List<ai.pleos.playground.vehicle.api.model.area.WheelArea>>()
        reader.listLowTirePressureThresholdAreas({ received.add(it) }, { throw AssertionError(it) })
        try { reader.release(); fail("pending discovery must prevent release") } catch (_: IllegalStateException) { }
        callback(true, listOf(wheel))
        callback(true, emptyList())
        assertEquals(1, received.size)
        assertEquals(listOf(wheel), received.single())
        assertEquals(listOf(ai.pleos.playground.vehicle.constant.area.WheelValue.UNKNOWN, ai.pleos.playground.vehicle.constant.area.WheelValue.LEFT_FRONT), received.single().single().values.toList())
        reader.release()
        assertEquals(1, fake.released)
    }

    @Test fun tireThresholdRequiresCapabilityAndPreservesAreaWithoutPretendingItIsPressure() {
        val original = ai.pleos.playground.vehicle.api.model.area.WheelArea(ai.pleos.playground.vehicle.constant.area.WheelValue.LEFT_FRONT)
        var requested: ai.pleos.playground.vehicle.api.model.area.WheelArea? = null
        var readable = false
        var raw: Double? = 137.0
        val port = object : VehiclePort by FakePort() {
            override fun tireThresholdCapability(area: ai.pleos.playground.vehicle.api.model.area.WheelArea, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) { requested = area; complete(readable) }
            override fun tireThreshold(area: ai.pleos.playground.vehicle.api.model.area.WheelArea, complete: (Double?) -> Unit, failure: (Exception) -> Unit) { assertSame(original, area); complete(raw) }
        }
        val reader = PleosVehicleReader(port) { 42L }
        reader.initialize()
        var error: Exception? = null
        var value: ReceivedReading? = null
        reader.readLowTirePressureThreshold(original, { value = it }, { error = it })
        assertTrue(error is UnsupportedOperationException)
        assertNull(value)
        assertSame(original, requested)
        readable = true; error = null
        reader.readLowTirePressureThreshold(original, { value = it }, { error = it })
        assertNull(error)
        assertEquals(ReceivedReading("low_tire_pressure_threshold", 137.0, "kPa", 42L), value)
        raw = -1.0; value = null
        reader.readLowTirePressureThreshold(original, { value = it }, { error = it })
        assertTrue(error is InvalidVehicleReadingException)
        assertNull(value)
        reader.release()
    }

    @Test fun releaseWaitsForSdkMethodReturnAfterSynchronousCallback() {
        val callbackDelivered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val released = AtomicInteger()
        val port = object : VehiclePort {
            override fun initialize() = Unit
            override fun capability(name: String, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) {
                complete(true)
                callbackDelivered.countDown()
                check(allowReturn.await(5, TimeUnit.SECONDS))
            }
            override fun read(name: String, complete: (Double?) -> Unit, failure: (Exception) -> Unit) = complete(123.0)
            override fun readState(name: String, complete: (String?) -> Unit, failure: (Exception) -> Unit) = error("unused")
            override fun release() { released.incrementAndGet() }
        }
        val reader = PleosVehicleReader(port) { 42L }
        reader.initialize()
        val thread = Thread {
            try { reader.readOdometer({ assertEquals(123.0, it!!.value, 0.0) }, { throw AssertionError(it) }) }
            catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        try {
            assertTrue(callbackDelivered.await(5, TimeUnit.SECONDS))
            try { reader.release(); fail("SDK capability method has not returned") } catch (_: IllegalStateException) { }
            assertEquals(0, released.get())
        } finally { allowReturn.countDown(); thread.join(5_000) }
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        reader.release()
        assertEquals(1, released.get())
    }

    @Test fun releaseWaitsForGetterReturnAfterSynchronousResult() {
        lateinit var capability: (Boolean) -> Unit
        val callbackDelivered = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val port = object : VehiclePort {
            override fun initialize() = Unit
            override fun capability(name: String, complete: (Boolean) -> Unit, failure: (Exception) -> Unit) { capability = complete }
            override fun read(name: String, complete: (Double?) -> Unit, failure: (Exception) -> Unit) {
                complete(123.0)
                callbackDelivered.countDown()
                check(allowReturn.await(5, TimeUnit.SECONDS))
            }
            override fun readState(name: String, complete: (String?) -> Unit, failure: (Exception) -> Unit) = error("unused")
            override fun release() = Unit
        }
        val reader = PleosVehicleReader(port) { 42L }
        reader.initialize()
        reader.readOdometer({ assertEquals(123.0, it!!.value, 0.0) }, { throw AssertionError(it) })
        val thread = Thread { try { capability(true) } catch (error: Throwable) { failure.set(error) } }
        thread.start()
        try {
            assertTrue(callbackDelivered.await(5, TimeUnit.SECONDS))
            try { reader.release(); fail("SDK getter has not returned") } catch (_: IllegalStateException) { }
        } finally { allowReturn.countDown(); thread.join(5_000) }
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        reader.release()
    }

    @Test fun releaseWaitsForAllIndependentRequests() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 42L }; reader.initialize()
        val readings = mutableListOf<ReceivedReading?>()
        reader.readOdometer(readings::add) { fail(it.message) }
        val odometerCapability = fake.capabilityDone
        reader.readFuelPercent(readings::add) { fail(it.message) }
        val fuelCapability = fake.capabilityDone
        odometerCapability(true)
        val odometerResult = fake.readDone
        fuelCapability(true)
        val fuelResult = fake.readDone
        odometerResult(100.0)
        try { reader.release(); fail("fuel request is pending") } catch (_: IllegalStateException) { }
        fuelResult(50.0)
        assertEquals(listOf("odometer", "fuel"), readings.map { it?.name })
        reader.release()
        assertEquals(1, fake.released)
    }

    @Test fun releaseIsRejectedWhileConsumerCallbackRunsAndDuplicateIsIgnored() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 42L }; reader.initialize()
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val count = AtomicInteger(); val failure = AtomicReference<Throwable?>()
        reader.readOdometer({
            count.incrementAndGet(); entered.countDown()
            check(unblock.await(5, TimeUnit.SECONDS))
        }, { throw AssertionError(it) })
        fake.capabilityDone(true)
        val callback = fake.readDone
        val thread = Thread {
            try { callback(100.0) } catch (e: Throwable) { failure.set(e) }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { reader.release(); fail("callback still running") } catch (_: IllegalStateException) { }
            callback(101.0)
            assertEquals(1, count.get())
        } finally { unblock.countDown(); thread.join(5_000) }
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        reader.release()
        assertEquals(1, fake.released)
    }

    @Test fun invalidNumericReadingsAreReportedWithoutClampingOrSubstitution() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 123L }; reader.initialize()
        var successes = 0; var failures = 0
        fun invalid(start: ((ReceivedReading?) -> Unit, (Exception) -> Unit) -> Unit, value: Double) {
            start({ successes++ }, { assertTrue(it is InvalidVehicleReadingException); failures++ })
            fake.capabilityDone(true); fake.readDone(value)
        }
        invalid(reader::readOdometer, -1.0)
        invalid(reader::readOdometer, Double.NaN)
        invalid(reader::readFuelPercent, 100.1)
        invalid(reader::readFuelPercent, Double.POSITIVE_INFINITY)
        invalid(reader::readEvBatteryStateOfCharge, -1.0)
        invalid(reader::readEvBatteryStateOfCharge, 101.0)
        invalid(reader::readBrakePedal, 2.0)
        assertEquals(0, successes); assertEquals(7, failures)
        reader.release()
    }

    @Test fun evBatteryChargeIsDistinctFromFuelAndKeepsReceiptTime() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 123L }; reader.initialize()
        var value: ReceivedReading? = null
        reader.readEvBatteryStateOfCharge({ value = it }, { fail(it.message) })
        assertEquals("ev_battery_soc", fake.capabilityName)
        fake.capabilityDone(true); fake.readDone(75.0)
        assertEquals(ReceivedReading("ev_battery_soc", 75.0, "%", 123L), value)
        reader.release()
    }

    @Test fun capabilityAndGetterProduceReceiptTimestampAndPreventEarlyRelease() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 1234L }
        reader.initialize()
        var actual: ReceivedReading? = null
        reader.readOdometer({ actual = it }, { fail(it.message) })
        try { reader.release(); fail("pending capability") } catch (_: IllegalStateException) { }
        fake.capabilityDone(true)
        try { reader.release(); fail("pending getter") } catch (_: IllegalStateException) { }
        fake.readDone(123.5)
        assertEquals(ReceivedReading("odometer", 123.5, "km", 1234L), actual)
        reader.release(); reader.release()
        assertEquals(1, fake.released)
    }

    @Test fun unsupportedCapabilityDoesNotCallGetter() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 0L }; reader.initialize()
        var error: Exception? = null
        reader.readFuelPercent({ fail("unexpected reading") }, { error = it })
        fake.capabilityDone(false)
        assertTrue(error is UnsupportedOperationException)
        assertEquals(0, fake.readCount)
        reader.release()
    }

    @Test fun nullValueAndGetterFailureAreReportedOnce() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 55L }; reader.initialize()
        var callbacks = 0
        reader.readFuelPercent({ assertNull(it); callbacks++ }, { fail(it.message) })
        fake.capabilityDone(true); fake.capabilityDone(true); fake.readDone(null); fake.readFailed(IllegalStateException("late"))
        assertEquals(1, fake.readCount); assertEquals(1, callbacks)
        reader.release()
    }

    @Test fun sdkFailureReleasesPendingRequestWithoutSubstitutingData() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 55L }; reader.initialize()
        val failure = IllegalStateException("permission denied")
        var received: Exception? = null
        reader.readFuelPercent({ fail("unexpected reading") }, { received = it })
        fake.capabilityFailed(failure)
        assertSame(failure, received)
        assertEquals(0, fake.readCount)
        reader.release()
    }

    @Test fun lateCapabilityFailureCannotCancelAnOutstandingGetter() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 99L }; reader.initialize()
        var value: ReceivedReading? = null
        var failures = 0
        reader.readFuelPercent({ value = it }, { failures++ })
        fake.capabilityDone(true)
        fake.capabilityFailed(IllegalStateException("late capability error"))
        try { reader.release(); fail("getter is still pending") } catch (_: IllegalStateException) { }
        fake.readDone(45.0)
        assertEquals(ReceivedReading("fuel", 45.0, "%", 99L), value)
        assertEquals(0, failures)
        reader.release()
    }

    @Test fun failedGetterIgnoresLateSuccessAndReportsOnlyOneResult() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 99L }; reader.initialize()
        var values = 0; var failures = 0
        reader.readOdometer({ values++ }, { failures++ })
        fake.capabilityDone(true)
        fake.readFailed(IllegalStateException("getter failed")); fake.readDone(123.0)
        assertEquals(0, values); assertEquals(1, failures)
        reader.release()
    }

    @Test fun brakeReadingKeepsBooleanEncodingAndReceiptTime() {
        assertEquals(1.0, true.brakeValue()!!, 0.0)
        assertEquals(0.0, false.brakeValue()!!, 0.0)
        assertNull((null as Boolean?).brakeValue())
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 123L }; reader.initialize()
        var value: ReceivedReading? = null
        reader.readBrakePedal({ value = it }, { fail(it.message) })
        fake.capabilityDone(true); fake.readDone(1.0)
        assertEquals(ReceivedReading("brake_pedal_pressed", 1.0, "boolean", 123L), value)
        reader.release()
    }

    @Test fun enumStateIsPreservedAsVersionedSdkName() {
        val fake = FakePort(); val reader = PleosVehicleReader(fake) { 456L }; reader.initialize()
        var state: ReceivedState? = null
        reader.readDrivingMode({ state = it }, { fail(it.message) })
        fake.capabilityDone(true); fake.stateDone("SPORT")
        assertEquals(ReceivedState("driving_mode", "SPORT", "2.3.2", 456L), state)
        reader.readTurnSignal({ state = it }, { fail(it.message) })
        fake.capabilityDone(true); fake.stateDone("LEFT")
        assertEquals(ReceivedState("turn_signal", "LEFT", "2.3.2", 456L), state)
        reader.readEngineOilLevelState({ state = it }, { fail(it.message) })
        fake.capabilityDone(true); fake.stateDone("LOW")
        assertEquals(ReceivedState("engine_oil_level", "LOW", "2.3.2", 456L), state)
        reader.readBrakeFluidLevelState({ state = it }, { fail(it.message) })
        assertEquals("brake_fluid_level", fake.capabilityName)
        fake.capabilityDone(true)
        assertEquals("brake_fluid_level", fake.stateName)
        fake.stateDone("LOW")
        assertEquals(ReceivedState("brake_fluid_level", "LOW", "2.3.2", 456L), state)
        reader.release()
    }
}
