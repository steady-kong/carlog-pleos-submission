package kr.carlog.pleos

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventCaptureTest {
    @Test fun cancellationDuringErrorRecoveryRetriesInterruptionsAndPropagatesCancellation() = runTest {
        for (mode in listOf("append", "clock", "start")) {
            val events = mutableMapOf<String, EventEntity>()
            var wall = 100_000L + testScheduler.currentTime
            var interruptCalls = 0
            val errors = mutableListOf<String>()
            val store = object : EventCaptureStore {
                override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                    events[event.id] = event
                    if (mode == "start" && event.memo == "new") wall += 10_000
                }
                override suspend fun append(samples: List<SampleEntity>) { throw IllegalStateException("append failed") }
                override suspend fun finish(id: String, status: String) {
                    if (status == "interrupted") {
                        interruptCalls++
                        if (mode != "append" && interruptCalls == 1) throw IllegalStateException("first interruption failed")
                        if (interruptCalls == if (mode == "append") 1 else 2) throw kotlinx.coroutines.CancellationException("cancel recovery")
                    }
                    events[id]?.let { events[id] = it.copy(status = status) }
                }
                override suspend fun recoverCancelledCompletion(id: String) { if (events[id]?.status == "collecting") finish(id, "interrupted") }
            }
            val clock = object : CaptureClock {
                override fun wallTimeMillis() = wall
                override fun elapsedRealtimeMillis() = testScheduler.currentTime
            }
            var resets = 0
            val capture = EventCapture(this, store, clock, { fail(it) }, onClockReset = { resets++ }, onTerminalFailure = errors::add)
            capture.start("v", "일반 이벤트", "existing", "MOCK")
            if (mode == "clock") wall += 10_000
            val result = CompletableDeferred<kotlinx.coroutines.CancellationException>()
            val job = launch {
                try {
                    if (mode == "start") capture.start("v", "차량 이상", "new", "MOCK")
                    else capture.accept(Signal(wall, "fuel", 50.0, "%"))
                    fail("cancellation expected")
                } catch (e: kotlinx.coroutines.CancellationException) { result.complete(e) }
            }
            val cancellation = result.await()
            job.join()
            assertTrue(cancellation.suppressed.any { it is IllegalStateException })
            assertTrue(events.values.all { it.status == "interrupted" })
            assertEquals(1, errors.size)
            if (mode == "clock") assertEquals(1, resets)
            try { capture.start("v", "일반 이벤트", "late", "MOCK"); fail("capture must stay stopped") }
            catch (_: CaptureStoppedException) { }
        }
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun cancellationDuringInterruptionRetriesAllActiveEvents() = runTest {
        for (invalidSignal in listOf(false, true)) {
            val persisted = mutableMapOf<String, EventEntity>()
            val committed = CompletableDeferred<Unit>()
            var firstWrite = true
            val store = object : EventCaptureStore {
                override suspend fun start(event: EventEntity, samples: List<SampleEntity>) { persisted[event.id] = event }
                override suspend fun append(samples: List<SampleEntity>) = Unit
                override suspend fun finish(id: String, status: String) {
                    persisted[id]?.let { persisted[id] = it.copy(status = status) }
                    if (status == "interrupted" && firstWrite) {
                        firstWrite = false; committed.complete(Unit); awaitCancellation()
                    }
                }
                override suspend fun recoverCancelledCompletion(id: String) { if (persisted[id]?.status == "collecting") finish(id, "interrupted") }
            }
            val clock = object : CaptureClock {
                override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
                override fun elapsedRealtimeMillis() = testScheduler.currentTime
            }
            val terminalErrors = mutableListOf<String>()
            val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
            val first = capture.start("v", "일반 이벤트", "first", "MOCK")
            val second = capture.start("v", "차량 이상", "second", "MOCK")
            val pending = launch {
                if (invalidSignal) capture.accept(Signal(100_000L + testScheduler.currentTime, "", 1.0, ""))
                else capture.interruptAll()
            }
            committed.await()
            pending.cancelAndJoin()
            assertEquals("interrupted", persisted.getValue(first).status)
            assertEquals("interrupted", persisted.getValue(second).status)
            assertEquals(1, terminalErrors.size)
            try { capture.start("v", "일반 이벤트", "late", "MOCK"); fail("capture must stay stopped") }
            catch (_: CaptureStoppedException) { }
        }
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun cancellationDuringClockResetStopsCaptureAndInterruptsActiveEvent() = runTest {
        val persisted = mutableMapOf<String, EventEntity>()
        val entered = CompletableDeferred<Unit>()
        var firstInterruption = true
        var wall = 100_000L
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) { persisted[event.id] = event }
            override suspend fun append(samples: List<SampleEntity>) = Unit
            override suspend fun finish(id: String, status: String) {
                if (status == "interrupted" && firstInterruption) {
                    firstInterruption = false
                    entered.complete(Unit)
                    awaitCancellation()
                }
                persisted[id]?.let { persisted[id] = it.copy(status = status) }
            }
            override suspend fun recoverCancelledCompletion(id: String) { if (persisted[id]?.status == "collecting") finish(id, "interrupted") }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = wall
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        var resets = 0
        val capture = EventCapture(this, store, clock, { fail(it) }, onClockReset = { resets++ }, onTerminalFailure = errors::add)
        val id = capture.start("v", "일반 이벤트", "clock reset", "MOCK")
        wall += 10_000
        val pending = launch { capture.accept(Signal(wall, "speed", 1.0, "km/h")) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals("interrupted", persisted.getValue(id).status)
        assertEquals(1, resets)
        assertEquals(1, errors.size)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", persisted.getValue(id).status)
    }

    private class MemoryStore : EventCaptureStore {
        val events = mutableMapOf<String, EventEntity>()
        val samples = mutableListOf<SampleEntity>()
        var blockStart: CompletableDeferred<Unit>? = null
        var blockAppend: CompletableDeferred<Unit>? = null
        var blockComplete: CompletableDeferred<Unit>? = null
        var failStart = false
        var failAppend = false
        var failComplete = false
        var interruptedWriteFailures = 0
        override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
            blockStart?.await()
            if (failStart) throw IllegalStateException("storage failure")
            events[event.id] = event
            this.samples.addAll(samples)
        }
        override suspend fun append(samples: List<SampleEntity>) {
            blockAppend?.await()
            if (failAppend) throw IllegalStateException("storage failure")
            this.samples.addAll(samples)
        }
        override suspend fun finish(id: String, status: String) {
            if (status == "interrupted" && interruptedWriteFailures > 0) {
                interruptedWriteFailures--
                throw IllegalStateException("interruption failure")
            }
            if (status == "complete") {
                blockComplete?.await()
                if (failComplete) throw IllegalStateException("completion failure")
            }
            events[id]?.let { events[id] = it.copy(status = status) }
        }
        override suspend fun recoverCancelledCompletion(id: String) {
            if (events[id]?.status == "collecting") finish(id, "interrupted")
        }
    }

    @Test fun cancellingScopeDuringPostEventWindowInterruptsCaptures() = runTest {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        val first = capture.start("v", "일반 이벤트", "first", "MOCK")
        val second = capture.start("v", "차량 이상", "second", "MOCK")
        scope.cancel()
        runCurrent()
        assertEquals("interrupted", store.events.getValue(first).status)
        assertEquals("interrupted", store.events.getValue(second).status)
        assertEquals(1, errors.size)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
    }

    @Test fun cancellationAfterCommittedStartInterruptsThatEventAndExistingCaptures() = runTest {
        val persisted = mutableMapOf<String, EventEntity>()
        val committed = CompletableDeferred<Unit>()
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                persisted[event.id] = event
                if (event.memo == "cancelled") { committed.complete(Unit); awaitCancellation() }
            }
            override suspend fun append(samples: List<SampleEntity>) = Unit
            override suspend fun finish(id: String, status: String) {
                persisted[id]?.let { persisted[id] = it.copy(status = status) }
            }
            override suspend fun recoverCancelledCompletion(id: String) { if (persisted[id]?.status == "collecting") finish(id, "interrupted") }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        val existing = capture.start("v", "일반 이벤트", "existing", "MOCK")
        val pending = launch { capture.start("v", "일반 이벤트", "cancelled", "MOCK") }
        committed.await()
        pending.cancelAndJoin()
        assertEquals("interrupted", persisted.getValue(existing).status)
        assertEquals("interrupted", persisted.values.single { it.memo == "cancelled" }.status)
        assertEquals(1, terminalErrors.size)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertTrue(persisted.values.none { it.status == "complete" })
    }

    @Test fun cancellationAfterCommittedAppendInterruptsAllActiveEvents() = runTest {
        val persisted = mutableMapOf<String, EventEntity>()
        val storedSamples = mutableListOf<SampleEntity>()
        val committed = CompletableDeferred<Unit>()
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) { persisted[event.id] = event }
            override suspend fun append(samples: List<SampleEntity>) {
                storedSamples.addAll(samples)
                committed.complete(Unit)
                awaitCancellation()
            }
            override suspend fun finish(id: String, status: String) { persisted[id] = persisted.getValue(id).copy(status = status) }
            override suspend fun recoverCancelledCompletion(id: String) { if (persisted[id]?.status == "collecting") finish(id, "interrupted") }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        val first = capture.start("v", "일반 이벤트", "first", "MOCK")
        val second = capture.start("v", "차량 이상", "second", "MOCK")
        val pending = launch { capture.accept(Signal(100_000, "fuel", 50.0, "%")) }
        committed.await()
        pending.cancelAndJoin()
        assertEquals(setOf(first, second), storedSamples.map { it.eventId }.toSet())
        assertTrue(persisted.values.all { it.status == "interrupted" })
        assertEquals(1, errors.size)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertTrue(persisted.values.none { it.status == "complete" })
    }

    @Test fun cancelledCompletionKeepsCommittedEventAndInterruptsOtherCapture() = runTest {
        val persisted = mutableMapOf<String, EventEntity>()
        val committed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = object : EventCaptureStore {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) { persisted[event.id] = event }
            override suspend fun append(samples: List<SampleEntity>) = Unit
            override suspend fun finish(id: String, status: String) {
                persisted[id] = persisted.getValue(id).copy(status = status)
                if (status == "complete") { committed.complete(Unit); awaitCancellation() }
            }
            override suspend fun recoverCancelledCompletion(id: String) {
                if (persisted.getValue(id).status == "collecting") finish(id, "interrupted")
            }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(scope, store, clock, { fail(it) }, onTerminalFailure = errors::add)
        val first = capture.start("v", "일반 이벤트", "first", "MOCK")
        advanceTimeBy(1_000); runCurrent()
        val second = capture.start("v", "차량 이상", "second", "MOCK")
        advanceTimeBy(59_000); runCurrent()
        committed.await()
        scope.cancel(); runCurrent()
        assertEquals("complete", persisted.getValue(first).status)
        assertEquals("interrupted", persisted.getValue(second).status)
        assertEquals(1, errors.size)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
    }

    @Test fun capturePreservesTimestampProvenanceAndRejectsConflicts() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val signal = Signal(100_000, "fuel", 50.0, "%", "RECEIVED")
        capture.accept(signal)
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals("RECEIVED", store.samples.single().timestampKind)
        try { capture.accept(signal.copy(timestampKind = "MEASURED")); fail("different provenance is a conflict") }
        catch (_: ConflictingSignalException) { }
        assertEquals("interrupted", store.events.getValue(id).status)
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun liveCaptureAcceptsUnicodeSignalLengthsWithinExchangeLimits() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val signal = Signal(100_000, "🚗".repeat(256), 1.0, "🔋".repeat(64))
        capture.accept(signal)
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals(signal.name, store.samples.single().name)
        try { capture.accept(signal.copy(name = signal.name + "🚗")); fail("name too long") }
        catch (_: InvalidSignalException) { }
        assertEquals("interrupted", store.events.getValue(id).status)
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun bufferOverflowInterruptsEventsAndStopsFurtherCapture() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) }, maxBufferedSamples = 1)
        capture.accept(Signal(100_000, "fuel", 50.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        try { capture.accept(Signal(100_000, "odometer", 1_000.0, "km")); fail("capacity exceeded") }
        catch (_: SignalBufferCapacityException) { }
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(listOf("fuel"), store.samples.map { it.name })
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.getValue(id).status)
    }

    @Test fun failedInterruptionCannotLaterBecomeComplete() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        store.interruptedWriteFailures = 1
        try { capture.interruptAll(); fail("interruption should fail") }
        catch (_: IllegalStateException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("collecting", store.events.getValue(id).status)
        capture.interruptAll()
        assertEquals("interrupted", store.events.getValue(id).status)
    }

    @Test fun preEventAgeUsesOnlyObservedSamplesInsideTheLastMinute() = runTest {
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, MemoryStore(), clock, { fail(it) })
        assertNull(capture.oldestPreEventSampleAgeMillis())
        capture.accept(Signal(100_000, "fuel", 50.0, "%"))
        assertEquals(0L, capture.oldestPreEventSampleAgeMillis())
        advanceTimeBy(60_000)
        assertEquals(60_000L, capture.oldestPreEventSampleAgeMillis())
        advanceTimeBy(1)
        assertNull(capture.oldestPreEventSampleAgeMillis())
        capture.accept(Signal(161_001, "fuel", 49.0, "%"))
        assertNull(capture.oldestPreEventSampleAgeMillis())
        advanceTimeBy(1_000)
        assertEquals(0L, capture.oldestPreEventSampleAgeMillis())
    }

    @Test fun duplicateBufferedSignalsAreDeduplicatedBeforeEventStarts() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val signal = Signal(99_000, "fuel", 5.0, "%")
        capture.accept(signal); capture.accept(signal)
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals(listOf(5.0), store.samples.filter { it.eventId == id }.map { it.value })
        capture.interruptAll()
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun conflictingBufferedSignalStopsAndInterruptsActiveEvent() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(99_000, "fuel", 5.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        try { capture.accept(Signal(99_000, "fuel", 6.0, "%")); fail("conflict should stop capture") }
        catch (_: ConflictingSignalException) { }
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(listOf(5.0), store.samples.map { it.value })
        try { capture.start("v", "일반 이벤트", "", "MOCK"); fail("stopped capture must not restart") }
        catch (_: IllegalStateException) { }
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun overlappingEventsCaptureTheirOwnWindowsAndComplete() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(40_000, "fuel", 1.0, "%"))
        val first = capture.start("v", "일반 이벤트", "", "MOCK")
        advanceTimeBy(30_000); runCurrent()
        val second = capture.start("v", "차량 이상", "", "MOCK")
        capture.accept(Signal(130_000, "fuel", 2.0, "%"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("complete", store.events.getValue(first).status)
        assertEquals("collecting", store.events.getValue(second).status)
        assertEquals(listOf(40_000L, 130_000L), store.samples.filter { it.eventId == first }.map { it.timestamp })
        assertEquals(listOf(130_000L), store.samples.filter { it.eventId == second }.map { it.timestamp })
        advanceTimeBy(30_000); runCurrent()
        assertEquals("complete", store.events.getValue(second).status)
    }

    @Test fun signalArrivingDuringInitialDatabaseWriteIsNotLost() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred() }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val start = launch { capture.start("v", "일반 이벤트", "", "MOCK") }
        runCurrent()
        val signal = launch { capture.accept(Signal(100_001, "fuel", 5.0, "%")) }
        runCurrent()
        store.blockStart!!.complete(Unit)
        start.join(); signal.join()
        assertEquals(listOf(100_001L), store.samples.map { it.timestamp })
        capture.interruptAll()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.values.single().status)
    }

    @Test fun slowEventCreationDoesNotExtendPostEventCapture() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred() }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val start = launch { capture.start("v", "일반 이벤트", "", "MOCK") }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        store.blockStart!!.complete(Unit)
        start.join(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals("complete", store.events.values.single().status)
    }

    @Test fun permittedFutureSkewCannotEvictPreEventBoundaryDuringDelayedStart() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(40_000, "earliest", 1.0, "x"))
        val moment = capture.markMoment()
        advanceTimeBy(59_999)
        capture.accept(Signal(161_999, "future", 2.0, "x"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK", moment)
        assertEquals(listOf("earliest"), store.samples.filter { it.eventId == id }.map { it.name })
        assertEquals(40_000L, store.samples.single().timestamp)
        capture.interruptAll()
        advanceTimeBy(1); runCurrent()
        assertEquals("interrupted", store.events.getValue(id).status)
    }

    @Test fun delayedStartUsesTheMomentOfTheButtonPress() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(99_000, "fuel", 1.0, "%"))
        val moment = capture.markMoment()
        advanceTimeBy(30_000); runCurrent()
        val id = capture.start("v", "일반 이벤트", "", "MOCK", moment)
        assertEquals(100_000L, store.events.getValue(id).timestamp)
        assertEquals(listOf(99_000L), store.samples.filter { it.eventId == id }.map { it.timestamp })
        advanceTimeBy(30_000); runCurrent()
        assertEquals("complete", store.events.getValue(id).status)
    }

    @Test fun acceptedFutureSkewBeforeMarkIsPreservedInTheEvent() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(102_000, "fuel", 5.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals(listOf(102_000L), store.samples.filter { it.eventId == id }.map { it.timestamp })
        advanceTimeBy(60_000); runCurrent()
        assertEquals("complete", store.events.getValue(id).status)
    }

    @Test fun delayedStartKeepsSignalsFromBothSidesOfTheButtonPress() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(40_000, "fuel", 1.0, "%"))
        val moment = capture.markMoment()
        advanceTimeBy(30_000); runCurrent()
        capture.accept(Signal(130_000, "fuel", 2.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK", moment)
        assertEquals(listOf(40_000L, 130_000L), store.samples.filter { it.eventId == id }.map { it.timestamp })
        advanceTimeBy(30_000); runCurrent()
        assertEquals("complete", store.events.getValue(id).status)
    }

    @Test fun clockChangeBetweenButtonAndStartRejectsTheStaleMoment() = runTest {
        val store = MemoryStore()
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val moment = capture.markMoment()
        adjustment = 120_000
        try {
            capture.start("v", "일반 이벤트", "", "MOCK", moment)
            fail("a stale button time must not be recorded")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.events.isEmpty())
    }

    @Test fun startAfterTheEntireCaptureWindowDoesNotClaimCompletion() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val moment = capture.markMoment()
        advanceTimeBy(60_000); runCurrent()
        try {
            capture.start("v", "일반 이벤트", "", "MOCK", moment)
            fail("an expired capture must not be saved as complete")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.events.isEmpty())
    }

    @Test fun databaseStartTakingTheEntireWindowMarksEventInterrupted() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred() }
        val errors = mutableListOf<String>()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, errors::add)
        val start = launch { capture.start("v", "일반 이벤트", "", "MOCK") }
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        store.blockStart!!.complete(Unit)
        start.join()
        assertEquals("interrupted", store.events.values.single().status)
        assertEquals(1, errors.size)
    }

    @Test fun clockChangeDuringInitialWriteInterruptsTheNewEvent() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred() }
        val errors = mutableListOf<String>()
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, errors::add)
        val start = launch { capture.start("v", "일반 이벤트", "", "MOCK") }
        runCurrent()
        adjustment = 120_000
        store.blockStart!!.complete(Unit)
        start.join()
        assertEquals("interrupted", store.events.values.single().status)
        assertEquals(1, errors.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.values.single().status)
    }

    @Test fun failedInterruptionAfterInitialWriteStopsAndRecoversEvent() = runTest {
        val store = MemoryStore().apply {
            blockStart = CompletableDeferred()
            interruptedWriteFailures = 1
        }
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        var rejected = false
        val start = launch {
            try { capture.start("v", "일반 이벤트", "first", "MOCK") }
            catch (e: CaptureStoppedException) { rejected = e.cause is IllegalStateException }
        }
        runCurrent()
        adjustment = 120_000
        store.blockStart!!.complete(Unit)
        start.join()
        assertTrue(rejected)
        assertEquals("interrupted", store.events.values.single().status)
        assertEquals(1, terminalErrors.size)
        try { capture.start("v", "일반 이벤트", "second", "MOCK"); fail("capture stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.values.single().status)
    }

    @Test fun repeatedInterruptionWriteFailureLeavesEventForStartupRecovery() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred(); interruptedWriteFailures = 2 }
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        var error: CaptureStoppedException? = null
        val start = launch {
            try { capture.start("v", "일반 이벤트", "first", "MOCK") }
            catch (e: CaptureStoppedException) { error = e }
        }
        runCurrent()
        adjustment = 120_000
        store.blockStart!!.complete(Unit)
        start.join()
        assertEquals(1, error?.cause?.suppressed?.size)
        assertEquals("collecting", store.events.values.single().status)
        assertEquals(1, terminalErrors.size)
        try { capture.accept(Signal(clock.wallTimeMillis(), "fuel", 5.0, "%")); fail("capture stopped") }
        catch (_: CaptureStoppedException) { }
    }

    @Test fun failedClockResetInterruptionStopsCaptureBeforeAnotherEventStarts() = runTest {
        val store = MemoryStore()
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        val first = capture.start("v", "일반 이벤트", "first", "MOCK")
        store.interruptedWriteFailures = 1
        adjustment = 120_000
        try { capture.start("v", "일반 이벤트", "second", "MOCK"); fail("clock reset must stop capture") }
        catch (_: CaptureStoppedException) { }
        assertEquals("interrupted", store.events.getValue(first).status)
        assertEquals(1, store.events.size)
        assertEquals(1, terminalErrors.size)
        try { capture.start("v", "일반 이벤트", "third", "MOCK"); fail("stopped capture") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.getValue(first).status)
    }

    @Test fun failedClockResetDuringSignalAcceptanceStopsCapture() = runTest {
        val store = MemoryStore().apply { interruptedWriteFailures = 0 }
        var adjustment = 0L
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        store.interruptedWriteFailures = 2
        adjustment = 120_000
        try { capture.accept(Signal(220_000, "fuel", 50.0, "%")); fail("clock reset must stop capture") }
        catch (e: CaptureStoppedException) { assertEquals(1, e.cause?.suppressed?.size) }
        assertEquals(1, terminalErrors.size)
        assertEquals("collecting", store.events.getValue(id).status)
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("collecting", store.events.getValue(id).status)
        capture.interruptAll()
        assertEquals("interrupted", store.events.getValue(id).status)
    }

    @Test fun clockChangeInterruptsEventsAndDropsOldBuffer() = runTest {
        val store = MemoryStore()
        var adjustment = 0L
        val errors = mutableListOf<String>()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, errors::add)
        capture.accept(Signal(99_000, "fuel", 1.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        adjustment = 120_000
        capture.accept(Signal(220_000, "fuel", 2.0, "%"))
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(1, errors.size)
        val next = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals(listOf(220_000L), store.samples.filter { it.eventId == next }.map { it.timestamp })
        capture.interruptAll()
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun backwardClockChangeResetsDashboardReadingsBeforeAcceptingNewSignals() = runTest {
        val store = MemoryStore()
        var adjustment = 0L
        var latest = emptyMap<String, Signal>()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime + adjustment
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { }, onClockReset = { latest = emptyMap() })
        val old = Signal(100_000, "fuel", 50.0, "%")
        capture.accept(old); latest = updateLatestSignals(latest, old)
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        adjustment = -50_000
        val next = Signal(50_000, "fuel", 49.0, "%")
        capture.accept(next); latest = updateLatestSignals(latest, next)
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(next, latest["fuel"])
        val newEvent = capture.start("v", "일반 이벤트", "", "MOCK")
        assertEquals(listOf(50_000L), store.samples.filter { it.eventId == newEvent }.map { it.timestamp })
        capture.interruptAll(); advanceTimeBy(60_000); runCurrent()
    }

    @Test fun failedSampleWriteInterruptsAllActiveEvents() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.start("v", "일반 이벤트", "", "MOCK")
        capture.start("v", "차량 이상", "", "MOCK")
        store.failAppend = true
        try {
            capture.accept(Signal(100_000, "fuel", 5.0, "%"))
            fail("write should fail")
        } catch (_: IllegalStateException) { }
        assertTrue(store.events.values.all { it.status == "interrupted" })
        advanceTimeBy(60_000); runCurrent()
        assertTrue(store.events.values.all { it.status == "interrupted" })
    }

    @Test fun queuedEventCannotStartAfterSampleWriteFailure() = runTest {
        val store = MemoryStore().apply { blockAppend = CompletableDeferred(); failAppend = true }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val firstId = capture.start("v", "일반 이벤트", "first", "MOCK")
        var writeFailed = false
        val write = launch {
            try { capture.accept(Signal(100_000, "fuel", 5.0, "%")) }
            catch (_: IllegalStateException) { writeFailed = true }
        }
        runCurrent()
        var rejected = false
        val queued = launch {
            try { capture.start("v", "일반 이벤트", "second", "MOCK") }
            catch (_: IllegalStateException) { rejected = true }
        }
        runCurrent()
        store.blockAppend!!.complete(Unit)
        write.join(); queued.join()
        assertTrue(writeFailed)
        assertTrue(rejected)
        assertEquals(1, store.events.size)
        assertEquals("interrupted", store.events.getValue(firstId).status)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.getValue(firstId).status)
    }

    @Test fun completionFailureStopsQueuedEventAndNotifiesImmediately() = runTest {
        val store = MemoryStore().apply { blockComplete = CompletableDeferred(); failComplete = true }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val terminalErrors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, { fail(it) }, onTerminalFailure = terminalErrors::add)
        val id = capture.start("v", "일반 이벤트", "first", "MOCK")
        advanceTimeBy(60_000); runCurrent()
        var rejected = false
        val queued = launch {
            try { capture.start("v", "일반 이벤트", "second", "MOCK") }
            catch (_: CaptureStoppedException) { rejected = true }
        }
        runCurrent()
        store.blockComplete!!.complete(Unit)
        queued.join()
        assertTrue(rejected)
        assertEquals(1, store.events.size)
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(listOf("이벤트 완료 상태를 저장하지 못했습니다."), terminalErrors)
        try { capture.accept(Signal(clock.wallTimeMillis(), "fuel", 5.0, "%")); fail("capture stopped") }
        catch (_: CaptureStoppedException) { }
    }

    @Test fun invalidSignalsStopCaptureWithoutSavingThem() = runTest {
        val invalid = listOf(
            Signal(100_000, " ", 1.0, "%"),
            Signal(100_000, "x".repeat(257), 1.0, "%"),
            Signal(100_000, "fuel", 1.0, "x".repeat(65)),
            Signal(100_000, "fuel", Double.NaN, "%"),
            Signal(100_000, "fuel", Double.POSITIVE_INFINITY, "%"),
        )
        for (bad in invalid) {
            val store = MemoryStore()
            val clock = object : CaptureClock {
                override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
                override fun elapsedRealtimeMillis() = testScheduler.currentTime
            }
            val capture = EventCapture(this, store, clock, { fail(it) })
            capture.accept(Signal(clock.wallTimeMillis(), "fuel", 5.0, "%"))
            val id = capture.start("v", "일반 이벤트", "", "MOCK")
            try { capture.accept(bad); fail("invalid signal must be rejected") }
            catch (_: InvalidSignalException) { }
            assertEquals("interrupted", store.events.getValue(id).status)
            assertEquals(listOf(5.0), store.samples.map { it.value })
            try { capture.start("v", "일반 이벤트", "", "MOCK"); fail("stopped capture must not restart") }
            catch (_: IllegalStateException) { }
        }
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun failedInitialWriteCanBeRetriedWithTheSameMemo() = runTest {
        val store = MemoryStore().apply { failStart = true }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        try { capture.start("v", "차량 이상", "메모", "MOCK"); fail("write should fail") }
        catch (_: IllegalStateException) { }
        assertTrue(store.events.isEmpty())
        store.failStart = false
        val id = capture.start("v", "차량 이상", "메모", "MOCK")
        assertEquals("메모", store.events.getValue(id).memo)
        capture.interruptAll()
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun initialWriteThatCommitsThenFailsIsInterruptedBeforeRetry() = runTest {
        val persisted = MemoryStore()
        var failAfterCommit = true
        val store = object : EventCaptureStore by persisted {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                persisted.start(event, samples)
                if (failAfterCommit) throw IllegalStateException("failure after commit")
            }
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        try { capture.start("v", "일반 이벤트", "first", "MOCK"); fail("expected failure") }
        catch (_: IllegalStateException) { }
        assertEquals("interrupted", persisted.events.values.single().status)
        failAfterCommit = false
        val id = capture.start("v", "일반 이벤트", "retry", "MOCK")
        assertEquals("collecting", persisted.events.getValue(id).status)
        capture.interruptAll()
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun failedInitialWriteRecoveryStopsFurtherCapture() = runTest {
        val persisted = MemoryStore()
        val store = object : EventCaptureStore by persisted {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                persisted.start(event, samples)
                throw IllegalStateException("failure after commit")
            }
            override suspend fun finish(id: String, status: String) { throw IllegalStateException("recovery failed") }
            override suspend fun recoverFailedStart(id: String) = finish(id, "interrupted")
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, errors::add)
        try { capture.start("v", "일반 이벤트", "", "MOCK"); fail("expected stopped") }
        catch (_: CaptureStoppedException) { }
        assertTrue(errors.single().contains("복구하지 못해"))
        try { capture.start("v", "일반 이벤트", "", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
        assertEquals("collecting", persisted.events.values.single().status)
    }

    @Test fun cancelledRecoveryOfFailedStartInterruptsNewAndExistingEvents() = runTest {
        val persisted = MemoryStore()
        var failedId: String? = null
        var cancelRecovery = true
        val store = object : EventCaptureStore by persisted {
            override suspend fun start(event: EventEntity, samples: List<SampleEntity>) {
                persisted.start(event, samples)
                if (event.memo == "new") { failedId = event.id; throw IllegalStateException("failure after commit") }
            }
            override suspend fun finish(id: String, status: String) {
                if (id == failedId && cancelRecovery) {
                    cancelRecovery = false
                    throw kotlinx.coroutines.CancellationException("cancelled during recovery")
                }
                persisted.finish(id, status)
            }
            override suspend fun recoverFailedStart(id: String) = finish(id, "interrupted")
        }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val errors = mutableListOf<String>()
        val capture = EventCapture(this, store, clock, errors::add)
        val existing = capture.start("v", "일반 이벤트", "existing", "MOCK")
        try { capture.start("v", "일반 이벤트", "new", "MOCK"); fail("expected cancellation") }
        catch (e: kotlinx.coroutines.CancellationException) { assertEquals(1, e.suppressed.size) }
        assertEquals("interrupted", persisted.events.getValue(existing).status)
        assertEquals("interrupted", persisted.events.getValue(requireNotNull(failedId)).status)
        assertTrue(errors.single().contains("취소"))
        try { capture.start("v", "일반 이벤트", "later", "MOCK"); fail("capture must remain stopped") }
        catch (_: CaptureStoppedException) { }
        advanceTimeBy(60_000); runCurrent()
    }

    @Test fun queuedStartAfterSourceStopsCannotCreateACompletedEvent() = runTest {
        val store = MemoryStore().apply { blockStart = CompletableDeferred() }
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        val first = launch { capture.start("v", "일반 이벤트", "first", "MOCK") }
        runCurrent()
        val stop = launch { capture.interruptAll() }
        runCurrent()
        var rejected = false
        val second = launch {
            try { capture.start("v", "일반 이벤트", "second", "MOCK") }
            catch (_: IllegalStateException) { rejected = true }
        }
        runCurrent()
        store.blockStart!!.complete(Unit)
        first.join(); stop.join(); second.join()
        assertTrue(rejected)
        assertEquals("interrupted", store.events.values.single().status)
        try { capture.accept(Signal(100_001, "fuel", 1.0, "%")); fail("stopped capture must reject samples") }
        catch (_: IllegalStateException) { }
        advanceTimeBy(60_000); runCurrent()
        assertTrue(store.events.values.none { it.status == "complete" })
    }

    @Test fun farFutureSignalInterruptsCaptureBeforeItCanEvictOrAlterData() = runTest {
        val store = MemoryStore()
        val clock = object : CaptureClock {
            override fun wallTimeMillis() = 100_000L + testScheduler.currentTime
            override fun elapsedRealtimeMillis() = testScheduler.currentTime
        }
        val capture = EventCapture(this, store, clock, { fail(it) })
        capture.accept(Signal(40_000, "fuel", 1.0, "%"))
        val id = capture.start("v", "일반 이벤트", "", "MOCK")
        capture.accept(Signal(102_000, "fuel", 2.0, "%"))
        try { capture.accept(Signal(102_001, "fuel", 3.0, "%")); fail("future signal should fail") }
        catch (_: FutureSignalTimestampException) { }
        assertEquals("interrupted", store.events.getValue(id).status)
        assertEquals(listOf(40_000L, 102_000L), store.samples.filter { it.eventId == id }.map { it.timestamp })
        try { capture.start("v", "일반 이벤트", "", "MOCK"); fail("stopped source should not restart") }
        catch (_: IllegalStateException) { }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("interrupted", store.events.getValue(id).status)
    }
}
