package app.wanki.ui.notebooks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PageAdderTest {
    @Test
    fun failedInsertIsKeptAsAnErrorAndNotThrown() = runTest(StandardTestDispatcher()) {
        val adder = PageAdder(this)
        adder.add(3) { throw IOException("disk full") }
        assertEquals(3, adder.pendingIndex)
        advanceUntilIdle()
        assertNull(adder.pendingIndex)
        assertNotNull(adder.error.value)
        // a later insert that works clears the error
        adder.add(3) { }
        advanceUntilIdle()
        assertNull(adder.error.value)
    }

    @Test
    fun newPageIsShownOnceItArrives() = runTest(StandardTestDispatcher()) {
        val adder = PageAdder(this)
        var inserts = 0
        adder.add(2) { inserts++ }
        adder.add(2) { inserts++ } // a second tap while the first page is on its way
        advanceUntilIdle()
        assertEquals(1, inserts)
        assertNull(adder.arrived(pageCount = 2), "the flow has not delivered the page yet")
        assertEquals(2, adder.arrived(pageCount = 3))
        assertNull(adder.pendingIndex)
    }
}
