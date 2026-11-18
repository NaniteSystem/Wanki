package app.wanki.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.WankiTheme
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ink.InkTool
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.notebooks.NotebookScreen
import app.wanki.ui.notebooks.PageEditorState
import app.wanki.ui.notebooks.moveSelectionToPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * «Отменить» of a move to another page, tapped while the source page is open again but still loading its (big) ink:
 * the strokes must come back through that editor, not into the stored page under it, so the next stroke drawn there
 * does not save them away (final review I-1).
 */
class MoveUndoWhileLoadingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val graph get() = instrumentation.targetContext.graph
    private val repo get() = graph.repository

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Waits with the Compose test clock running (recompositions happen only while the rule waits). */
    private fun waitFor(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { check() }
        } catch (e: Throwable) {
            throw AssertionError("$what not within $timeoutMs ms", e)
        }
    }

    @Test
    fun undoWhileSourceLoadsKeepsStrokesOnce() {
        val nb = runBlocking { repo.createNotebook("Move undo ${System.nanoTime()}", PageTemplate.BLANK, "auto") }
        runBlocking { repo.addPage(nb) }
        val pages: List<PageEntity> = runBlocking { repo.pages(nb.id) }
        val source = pages[0]
        val target = pages[1]
        runBlocking { repo.saveInk(source, PerfInk.page(STROKES)) }
        lateinit var host: SnackbarHostState
        lateinit var undo: Undo
        // the screen shows the page of this id: changing it turns the page
        val showing = androidx.compose.runtime.mutableStateOf(source.id)
        rule.setContent {
            WankiTheme {
                host = remember { SnackbarHostState() }
                val scope = rememberUiScope()
                undo = remember { Undo(repo, host, scope) }
                CompositionLocalProvider(LocalUndo provides undo) {
                    androidx.compose.runtime.key(showing.value) {
                        NotebookScreen(nb.id, showing.value, onBack = {}, onGrid = {})
                    }
                }
            }
        }
        waitFor("source page loaded") { onMain { PageEditorState.editorOf(source.id) != null } }

        // move the first ten strokes to the second page
        onMain {
            val state = PageEditorState.editorOf(source.id)!!
            state.layer.selection.addAll(state.layer.strokes.take(MOVED))
            moveSelectionToPage(graph, undo, state, nb, target)
        }
        waitFor("the move") { onMain { host.currentSnackbarData?.visuals?.message?.startsWith("Перенесено") == true } }
        assertEquals(STROKES - MOVED, runBlocking { repo.loadInk(source.id) }.size)
        assertEquals(MOVED, runBlocking { repo.loadInk(target.id) }.size)

        // away from the source page (its editor closes), then back: its editor loads the big page again, and «Отменить»
        // is tapped while it does
        onMain { showing.value = target.id }
        waitFor("target page loaded") { onMain { PageEditorState.editorOf(target.id) != null } }
        onMain { showing.value = source.id }
        var tappedWhileLoading = false
        waitFor("the source editor") {
            onMain {
                val e = PageEditorState.openEditorOf(source.id)
                if (e != null && !e.loaded) {
                    tappedWhileLoading = true
                    host.currentSnackbarData!!.performAction()
                }
                e != null
            }
        }
        assertTrue("the source page had loaded before «Отменить» (no race to test)", tappedWhileLoading)
        waitFor("source page loaded again") { onMain { PageEditorState.editorOf(source.id)?.layer?.strokes?.size == STROKES } }

        // a stroke drawn on the source page is saved with everything the page holds
        onMain {
            PageEditorState.editorOf(source.id)!!.layer.add(
                InkStroke((0..10).map { InkPoint(300f + it * 6f, 900f, it * 8L) }, InkTool.STYLUS, InkBrush(size = 4f)),
            )
        }
        waitFor("the new stroke saved") { runBlocking { repo.loadInk(source.id) }.size != STROKES - MOVED && runBlocking { repo.loadInk(source.id) }.size != STROKES }
        val later = SystemClock.uptimeMillis() + 1500
        waitFor("any later save") { SystemClock.uptimeMillis() > later }
        assertEquals("source page strokes", STROKES + 1, runBlocking { repo.loadInk(source.id) }.size)
        assertEquals("target page strokes", 0, runBlocking { repo.loadInk(target.id) }.size)
    }

    private companion object {
        const val STROKES = 3000
        const val MOVED = 10
    }
}
