package kr.carlog.pleos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Persists each event and its samples independently of the UI. */
interface EventCaptureStore {
    suspend fun start(event: EventEntity, samples: List<SampleEntity>)
    suspend fun append(samples: List<SampleEntity>)
    /** Finish an existing collecting event, or confirm the requested terminal status. */
    suspend fun finish(id: String, status: String)
    /** If an initial write committed, interrupt it; absence is safe. */
    suspend fun recoverFailedStart(id: String) = finish(id, "interrupted")
    /** Preserve completion if it committed before cancellation; otherwise interrupt. */
    suspend fun recoverCancelledCompletion(id: String)
}

class RoomEventCaptureStore(private val dao: CarlogDao) : EventCaptureStore {
    override suspend fun start(event: EventEntity, samples: List<SampleEntity>) = dao.startEvent(event, samples)
    override suspend fun append(samples: List<SampleEntity>) = dao.appendCollectingSamples(samples)
    override suspend fun finish(id: String, status: String) = dao.finishCollectingEvent(id, status)
    override suspend fun recoverFailedStart(id: String) = dao.recoverFailedEventStart(id)
    override suspend fun recoverCancelledCompletion(id: String) = dao.recoverCancelledEventCompletion(id)
}

interface CaptureClock {
    fun wallTimeMillis(): Long
    fun elapsedRealtimeMillis(): Long
}
data class CaptureMoment(val wallTimeMillis: Long, val elapsedRealtimeMillis: Long)
class FutureSignalTimestampException : IllegalArgumentException("Signal timestamp is too far in the future")
class InvalidSignalException : IllegalArgumentException("Invalid signal name, unit, or value")
class CaptureStoppedException(cause: Throwable? = null) : IllegalStateException("Vehicle data collection has stopped", cause)

/** All buffer and persistence operations are serialized, including completion. */
class EventCapture(
    private val scope: CoroutineScope,
    private val store: EventCaptureStore,
    private val clock: CaptureClock,
    private val onFailure: (String) -> Unit,
    private val windowMs: Long = 60_000,
    private val maxClockDriftMs: Long = 2_000,
    private val maxFutureSkewMs: Long = 2_000,
    private val maxBufferedSamples: Int = DEFAULT_SIGNAL_BUFFER_CAPACITY,
    private val onClockReset: () -> Unit = {},
    private val onTerminalFailure: (String) -> Unit = onFailure,
) {
    init {
        require(windowMs in 1..Long.MAX_VALUE / 2 && maxFutureSkewMs >= 0)
        require(maxFutureSkewMs <= Long.MAX_VALUE - windowMs * 2)
    }
    private val mutex = Mutex()
    // A delayed start needs both windows, plus allowed future skew so that an
    // accepted leading timestamp cannot evict the oldest pre-mark sample.
    private val buffer = RingBufferManager(windowMs * 2 + maxFutureSkewMs, maxBufferedSamples)
    private val active = mutableMapOf<String, LongRange>()
    private var stopped = false
    private var wallBaseline = clock.wallTimeMillis()
    private var elapsedBaseline = clock.elapsedRealtimeMillis()

    /** Capture this synchronously when the user marks the event, before waiting for database work. */
    fun markMoment(): CaptureMoment = CaptureMoment(clock.wallTimeMillis(), clock.elapsedRealtimeMillis())

    /** Age of the oldest observed sample in the preceding window, not continuous coverage. */
    suspend fun oldestPreEventSampleAgeMillis(): Long? = mutex.withLock {
        val now = clock.wallTimeMillis()
        val earliest = if (now < Long.MIN_VALUE + windowMs) Long.MIN_VALUE else now - windowMs
        buffer.oldestTimestamp(earliest, now)?.let { now - it }
    }

    suspend fun start(vehicleId: String, category: String, memo: String, source: String, moment: CaptureMoment = markMoment()): String = mutex.withLock {
        if (stopped) throw CaptureStoppedException()
        checkClockOrStop()
        val elapsedSinceMark = clock.elapsedRealtimeMillis() - moment.elapsedRealtimeMillis
        val wallSinceMark = clock.wallTimeMillis() - moment.wallTimeMillis
        require(elapsedSinceMark >= 0 && abs(wallSinceMark - elapsedSinceMark) <= maxClockDriftMs) {
            "Device clock changed after event was marked"
        }
        require(elapsedSinceMark < windowMs) { "Event capture window has already elapsed" }
        val now = moment.wallTimeMillis
        val endElapsed = moment.elapsedRealtimeMillis + windowMs
        val event = EventEntity(vehicleId = vehicleId, timestamp = now, category = category, memo = memo, source = source, status = "collecting")
        // Accepted samples may lead the device clock by the allowed skew. Keep those
        // already buffered if they belong to the event's post-mark window.
        val buffered = buffer.snapshotRange(now - windowMs, now + windowMs).map { it.forEvent(event.id) }
        try {
            store.start(event, buffered)
        } catch (e: CancellationException) {
            // A store can commit before cancellation is delivered to its caller. Close
            // this event by ID even when we cannot tell whether the transaction committed.
            recoverCancelledStart(event.id, e)
            throw e
        } catch (e: Exception) {
            // A storage adapter may fail after committing; recovery handles both
            // an absent row and a committed collecting row.
            try {
                store.recoverFailedStart(event.id)
            } catch (cancel: CancellationException) {
                cancel.addSuppressed(e)
                recoverCancelledStart(event.id, cancel)
                throw cancel
            } catch (recovery: Exception) {
                e.addSuppressed(recovery)
                stopped = true
                try { interruptLocked() } catch (cancel: CancellationException) {
                    cancel.addSuppressed(e)
                    recoverCancelledStart(event.id, cancel)
                    throw cancel
                } catch (failure: Exception) { e.addSuppressed(failure) }
                onTerminalFailure("이벤트 저장 상태를 복구하지 못해 차량 신호 수집을 중단했습니다.")
                throw CaptureStoppedException(e)
            }
            throw e
        }
        // The clock can change while the initial transaction is suspended. Include
        // the newly persisted event in the same interruption as existing captures.
        active[event.id] = (now - windowMs)..(now + windowMs)
        try {
            checkClock()
            if (!active.containsKey(event.id)) return@withLock event.id
            if (clock.elapsedRealtimeMillis() >= endElapsed) {
                store.finish(event.id, "interrupted")
                active.remove(event.id)
                onFailure("저장 지연으로 이벤트 후반 기록을 완료하지 못했습니다.")
                return@withLock event.id
            }
        } catch (e: CancellationException) {
            recoverCancelledStart(event.id, e)
            throw e
        } catch (e: Exception) {
            // The event already exists, but no completion job has been scheduled yet.
            stopped = true
            try { interruptLocked() } catch (recovery: CancellationException) {
                recovery.addSuppressed(e)
                recoverCancelledStart(event.id, recovery)
                throw recovery
            }
            catch (recovery: Exception) { e.addSuppressed(recovery) }
            onTerminalFailure("이벤트 상태를 저장하지 못해 차량 신호 수집을 중단했습니다.")
            throw CaptureStoppedException(e)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var recovered = false
            suspend fun recoverCancellation(e: CancellationException) {
                if (!active.containsKey(event.id)) { recovered = true; return }
                stopped = true
                try { store.recoverCancelledCompletion(event.id); active.remove(event.id) }
                catch (failure: Exception) { e.addSuppressed(failure) }
                try { interruptLocked() } catch (failure: Exception) { e.addSuppressed(failure) }
                recovered = true
                onTerminalFailure("이벤트 완료 저장이 취소되어 차량 신호 수집을 중단했습니다.")
            }
            try {
                delay((endElapsed - clock.elapsedRealtimeMillis()).coerceAtLeast(0))
                mutex.withLock {
                    try {
                        if (!stopped) checkClock()
                        if (!stopped && active.containsKey(event.id)) {
                            store.finish(event.id, "complete")
                            active.remove(event.id)
                        }
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { recoverCancellation(e) }
                        throw e
                    } catch (e: Exception) {
                        stopped = true
                        try { interruptLocked() } catch (recovery: CancellationException) { throw recovery }
                        catch (recovery: Exception) { e.addSuppressed(recovery) }
                        onTerminalFailure("이벤트 완료 상태를 저장하지 못했습니다.")
                    }
                }
            } catch (e: CancellationException) {
                // Also handle cancellation during the post-event delay or while
                // waiting for the mutex, before a completion write starts.
                withContext(NonCancellable) {
                    if (!recovered) mutex.withLock { recoverCancellation(e) }
                }
                throw e
            }
        }
        event.id
    }

    suspend fun accept(signal: Signal) = mutex.withLock {
        if (stopped) throw CaptureStoppedException()
        checkClockOrStop()
        if (!validSignalText(signal.name, signal.unit) || !validTimestampKind(signal.timestampKind) || !signal.value.isFinite()) {
            stopWith(InvalidSignalException())
        }
        val now = clock.wallTimeMillis()
        val latestAllowed = if (now > Long.MAX_VALUE - maxFutureSkewMs) Long.MAX_VALUE else now + maxFutureSkewMs
        if (signal.timestamp > latestAllowed) {
            stopWith(FutureSignalTimestampException())
        }
        try {
            buffer.add(signal)
        } catch (e: ConflictingSignalException) {
            stopWith(e)
        } catch (e: SignalBufferCapacityException) {
            stopWith(e)
        }
        val samples = active.filterValues { signal.timestamp in it }.keys.map { signal.forEvent(it) }
        if (samples.isNotEmpty()) {
            try {
                store.append(samples)
            } catch (e: CancellationException) {
                // A cancelled append may have committed. Stop all active windows before
                // the caller can release the collector or start another capture.
                stopped = true
                withContext(NonCancellable) {
                    try { interruptLocked() } catch (failure: Exception) { e.addSuppressed(failure) }
                    onTerminalFailure("차량 신호 저장이 취소되어 이벤트 수집을 중단했습니다.")
                }
                throw e
            } catch (e: Exception) {
                // Stop while holding the mutex: a queued event must not start between
                // this failed write and the source collector's error handling.
                stopped = true
                try { interruptLocked() }
                catch (recovery: CancellationException) {
                    recovery.addSuppressed(e)
                    recoverCancelledInterruption(recovery)
                    throw recovery
                } catch (recovery: Exception) { e.addSuppressed(recovery) }
                throw e
            }
        }
    }

    private suspend fun recoverCancelledStart(eventId: String, cancellation: CancellationException) {
        stopped = true
        withContext(NonCancellable) {
            val tracked = active.containsKey(eventId)
            try { interruptLocked() } catch (failure: Exception) { cancellation.addSuppressed(failure) }
            // The transaction may have committed even though start() did not return.
            if (!tracked) try { store.recoverFailedStart(eventId) }
            catch (failure: Exception) { cancellation.addSuppressed(failure) }
            onTerminalFailure("이벤트 저장이 취소되어 차량 신호 수집을 중단했습니다.")
        }
    }

    private suspend fun stopWith(error: IllegalArgumentException): Nothing {
        stopped = true
        try {
            interruptLocked()
        } catch (e: CancellationException) {
            recoverCancelledInterruption(e)
            throw e
        } catch (recovery: Exception) {
            error.addSuppressed(recovery)
        }
        throw error
    }

    /** Terminal stop: no later queued start or sample may reopen capture. */
    suspend fun interruptAll() = mutex.withLock {
        stopped = true
        try { interruptLocked() }
        catch (e: CancellationException) { recoverCancelledInterruption(e); throw e }
    }

    /** A committed interruption can be retried idempotently before releasing the lock. */
    private suspend fun recoverCancelledInterruption(error: CancellationException) = withContext(NonCancellable) {
        try { interruptLocked() } catch (failure: Exception) { error.addSuppressed(failure) }
        onTerminalFailure("이벤트 중단 저장이 취소되어 차량 신호 수집을 중단했습니다.")
    }

    /** Keep queued starts and samples out when a clock-reset interruption fails. */
    private suspend fun checkClockOrStop() {
        try { checkClock() }
        catch (e: CancellationException) {
            // A clock-reset interruption may have committed only some events before
            // cancellation. Never let a later caller resume the same capture.
            stopped = true
            withContext(NonCancellable) {
                try { interruptLocked() } catch (failure: Exception) { e.addSuppressed(failure) }
                buffer.clear()
                onClockReset()
                onTerminalFailure("기기 시간 변경 중 이벤트 중단 저장이 취소되어 수집을 중단했습니다.")
            }
            throw e
        }
        catch (e: Exception) {
            stopped = true
            try { interruptLocked() } catch (recovery: CancellationException) {
                recovery.addSuppressed(e)
                recoverCancelledInterruption(recovery)
                buffer.clear()
                onClockReset()
                throw recovery
            }
            catch (recovery: Exception) { e.addSuppressed(recovery) }
            buffer.clear()
            onClockReset()
            onTerminalFailure("기기 시간 변경 후 이벤트 중단 상태를 저장하지 못해 수집을 중단했습니다.")
            throw CaptureStoppedException(e)
        }
    }

    private suspend fun checkClock() {
        val wall = clock.wallTimeMillis()
        val elapsed = clock.elapsedRealtimeMillis()
        if (abs((wall - wallBaseline) - (elapsed - elapsedBaseline)) > maxClockDriftMs) {
            val hadActiveEvents = active.isNotEmpty()
            interruptLocked()
            buffer.clear()
            onClockReset()
            if (hadActiveEvents) onFailure("기기 시간이 변경되어 진행 중인 이벤트 기록을 중단했습니다.")
        }
        wallBaseline = wall
        elapsedBaseline = elapsed
    }

    private suspend fun interruptLocked() {
        // Remove only after a successful update so startup recovery can handle a failed write.
        var failure: Exception? = null
        active.keys.toList().forEach { id ->
            try {
                store.finish(id, "interrupted")
                active.remove(id)
            } catch (e: CancellationException) {
                failure?.let { e.addSuppressed(it) }
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e else failure!!.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}

private fun Signal.forEvent(id: String) = SampleEntity(id, timestamp, name, value, unit, timestampKind)
