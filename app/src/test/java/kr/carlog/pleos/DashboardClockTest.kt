package kr.carlog.pleos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardClockTest {
    @Test fun ticksImmediatelyAndRereadsWallClockIncludingBackwardChanges() = runTest {
        var wallTime = 1_000L
        val ticks = mutableListOf<Long>()
        val job = launch { dashboardClock({ wallTime }, 60_000).collect { ticks += it } }
        runCurrent()
        assertEquals(listOf(1_000L), ticks)
        wallTime = 500L
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(1_000L, 500L), ticks)
        job.cancelAndJoin()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, ticks.size)
    }
}
