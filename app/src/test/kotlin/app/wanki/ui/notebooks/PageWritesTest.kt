package app.wanki.ui.notebooks

import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageWritesTest {
    private fun pageId() = "page-" + UUID.randomUUID()

    /**
     * The move (`moveSelectionToPage`) adds the target page and encodes the source before it writes; an editor opening
     * the page in that gap must not read the page from before the write (its next save would double the moved strokes
     * on the source or drop them from the target).
     */
    @Test
    fun aReadStartedBeforeTheLaunchedWriteBeginsWaitsForIt() = runTest(StandardTestDispatcher()) {
        val id = pageId()
        var stored = "before"
        PageWrites.launchWrite(this, id) {
            delay(50) // addPage, encoding the rest on another dispatcher
            stored = "after"
        }
        // the page opened again right away: its editor reads before the move's coroutine has even started
        val read = async { PageWrites.read(id) { stored } }
        advanceUntilIdle()
        assertEquals("after", read.await())
        assertTrue(PageWrites.mark(id) >= 0, "the write is over")
    }

    @Test
    fun aWriteThatRunsDuringTheReadMakesItReadAgain() = runTest(StandardTestDispatcher()) {
        val id = pageId()
        var stored = "before"
        var reads = 0
        val read = async {
            PageWrites.read(id) {
                reads++
                val v = stored
                delay(30) // decoding a big page
                v
            }
        }
        launch {
            delay(10)
            PageWrites.around(id) { stored = "after" }
        }
        advanceUntilIdle()
        assertEquals("after", read.await())
        assertEquals(2, reads)
    }

    @Test
    fun theHoldEndsWhenTheWriteFailsOrNeverStarts() = runTest(StandardTestDispatcher()) {
        val failing = pageId()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        PageWrites.launchWrite(scope, failing) { error("disk full") }
        assertEquals(-1L, PageWrites.mark(failing), "held from the call on")
        advanceUntilIdle()
        assertTrue(PageWrites.mark(failing) >= 0)

        val never = pageId()
        PageWrites.launchWrite(CoroutineScope(coroutineContext + Job().apply { cancel() }), never) { error("must not run") }
        advanceUntilIdle()
        assertTrue(PageWrites.mark(never) >= 0)
    }

    @Test
    fun aPageHeldTwiceStaysHeldUntilBothEnd() {
        val id = pageId()
        val a = PageWrites.begin(id, id) // the same page twice counts once
        val b = PageWrites.begin(id)
        a.end()
        a.end() // once only
        assertEquals(-1L, PageWrites.mark(id))
        b.end()
        assertTrue(PageWrites.mark(id) >= 0)
    }
}
