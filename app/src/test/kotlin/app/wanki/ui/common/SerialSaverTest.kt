package app.wanki.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SerialSaverTest {
    @Test
    fun requestWhileRunningRunsOnceMore() = runTest(StandardTestDispatcher()) {
        var runs = 0
        val saver = SerialSaver(this) { runs++; delay(100) }
        saver.request()
        runCurrent()
        assertEquals(1, runs)
        repeat(3) { saver.request(); advanceTimeBy(10) }
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test
    fun noOverlap() = runTest(StandardTestDispatcher()) {
        var running = 0
        var maxRunning = 0
        var runs = 0
        val saver = SerialSaver(this) {
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(50)
            runs++
            running--
        }
        repeat(20) { saver.request(); advanceTimeBy(17) }
        advanceUntilIdle()
        assertEquals(1, maxRunning)
        assertEquals(0, running)
        assert(runs >= 2)
    }

    @Test
    fun failedSaveKeepsErrorAndRetries() = runTest(StandardTestDispatcher()) {
        var fail = true
        var runs = 0
        val saver = SerialSaver(this) {
            runs++
            if (fail) error("disk full")
        }
        saver.request()
        advanceUntilIdle()
        assertNotNull(saver.error.value)
        // a failure does not stop later saves
        fail = false
        saver.request()
        advanceUntilIdle()
        assertNull(saver.error.value)
        assertEquals(2, runs)
    }

    @Test
    fun flushWaitsForPending() = runTest(StandardTestDispatcher()) {
        var runs = 0
        val saver = SerialSaver(this) { delay(100); runs++ }
        saver.request()
        runCurrent()
        saver.request() // pending behind the running one
        saver.flush()
        assertEquals(2, runs)
        assertEquals(200, currentTime)
    }

    @Test
    fun flushWithNothingToDoReturnsAtOnce() = runTest(StandardTestDispatcher()) {
        val saver = SerialSaver(this) { delay(100) }
        saver.flush()
        assertEquals(0, currentTime)
    }

    /** Like Dispatchers.Main.immediate: a save that never suspends finishes inside request(). */
    @Test
    fun eagerDispatcherDoesNotLoseRequests() {
        var runs = 0
        // called from outside any coroutine, like a click handler on the main thread
        val saver = SerialSaver(CoroutineScope(Dispatchers.Unconfined)) { runs++ }
        saver.request()
        saver.request()
        saver.request()
        assertEquals(3, runs)
    }
}
