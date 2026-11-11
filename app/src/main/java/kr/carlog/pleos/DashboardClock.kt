package kr.carlog.pleos

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Re-read wall time on each tick so time and time-zone changes aren't extrapolated. */
fun dashboardClock(now: () -> Long = System::currentTimeMillis, intervalMillis: Long = 60_000): Flow<Long> = flow {
    require(intervalMillis > 0)
    while (true) {
        emit(now())
        delay(intervalMillis)
    }
}
