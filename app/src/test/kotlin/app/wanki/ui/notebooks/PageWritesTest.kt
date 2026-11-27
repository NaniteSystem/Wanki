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

    /**
     * «Отменить» of a move right after the source page's editor closed: the editor's last save (its copy without the
     * moved strokes) is still to run. The strokes written back into the stored page must come after it, else that
     * save drops them, and the undo then takes them off the target too: lost.
     */
    @Test
    fun anUndoWithoutAnEditorWritesAfterTheLastSaveOfOneThatClosed() = runTest(StandardTestDispatcher()) {
        val id = pageId()
        var stored = listOf("a", "b") // the source page after the move
        val editorCopy = stored
        // the editor closes: its final save is launched, it has not started yet
        PageWrites.launchWrite(this, id) {
            delay(40) // encoding a big page
            stored = editorCopy
        }
        val undo = async {
            val editor = PageWrites.editorOrIdle(id, loading = { false }, editor = { null as String? })
            if (editor == null) PageWrites.around(id) { stored = stored + "moved" }
            editor
        }
        advanceUntilIdle()
        assertEquals(null, undo.await())
        assertEquals(listOf("a", "b", "moved"), stored)
    }

    /**
     * The page is opened again while the undo waits for such a save: the new editor may read the page any moment, so
     * the strokes go back through it once it has loaded, not into the stored page under it.
     */
    @Test
    fun anEditorOpeningWhileTheUndoWaitsGetsTheChange() = runTest(StandardTestDispatcher()) {
        val id = pageId()
        var open = false
        var loaded = false
        PageWrites.launchWrite(this, id) { delay(40) } // the last save of the editor that closed
        launch {
            delay(20)
            open = true // the page shown again, its editor reading it
            delay(60)
            loaded = true
        }
        val undo = async {
            PageWrites.editorOrIdle(
                id,
                loading = { open && !loaded },
                // like PageEditorState.awaitEditor: waits while the open editor loads, null when none is open
                editor = {
                    while (open && !loaded) delay(16)
                    if (open) "new editor" else null
                },
            )
        }
        advanceUntilIdle()
        assertEquals("new editor", undo.await())
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
