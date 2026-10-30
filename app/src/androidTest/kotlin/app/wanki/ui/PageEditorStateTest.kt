package app.wanki.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.AppGraph
import app.wanki.data.PageInkEntity
import app.wanki.data.PageTemplate
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ui.notebooks.PageEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageEditorStateTest {
    private val graph = AppGraph(InstrumentationRegistry.getInstrumentation().targetContext)
    private val stamp = System.nanoTime().toString()

    private fun stroke() = InkStroke(listOf(InkPoint(10f, 10f, 0), InkPoint(50f, 60f, 20)), brush = InkBrush(size = 8f, color = 0xFF000000.toInt()))

    private suspend fun newState(): PageEditorState {
        val nb = graph.repository.createNotebook("State $stamp", PageTemplate.LINED, "auto")
        val page = graph.repository.pages(nb.id).first()
        return PageEditorState(graph, nb, page, infinite = false)
    }

    @Test
    fun unreadableInkIsNeverOverwritten() = runBlocking(Dispatchers.Main.immediate) {
        val state = newState()
        val bad = byteArrayOf(1, 2, 3)
        graph.db.notebooks().upsertInk(PageInkEntity(pageId = state.page.id, strokes = bad))
        state.load()
        assertTrue(state.readOnly)
        state.layer.add(stroke())
        state.saver.request()
        state.saver.flush()
        assertArrayEquals(bad, graph.db.notebooks().ink(state.page.id)!!.strokes)
    }

    @Test
    fun readableInkIsSavedIncrementally() = runBlocking(Dispatchers.Main.immediate) {
        val state = newState()
        state.load()
        assertTrue(!state.readOnly)
        state.layer.add(stroke())
        state.saver.request()
        state.saver.flush()
        assertEquals(1, graph.repository.loadInk(state.page.id).size)
        state.layer.undo()
        state.saver.request()
        state.saver.flush()
        assertTrue(graph.db.notebooks().ink(state.page.id) == null)
    }

    @Test
    fun viewSavedAfterPan() = runBlocking(Dispatchers.Main.immediate) {
        val state = newState()
        state.load()
        state.viewport.onScreenSize(1000f, 2000f)
        state.viewport.transform(0f, 0f, 2f, 500f, 500f)
        val scale = state.viewport.scale
        state.onViewGestureEnd()
        withContext(Dispatchers.Default) { delay(1200) }
        assertEquals(scale, graph.db.notebooks().pages(state.page.notebookId).first().viewScale)
    }

    @Test
    fun saveDuringEraseDragThenCancelStoresRestoredStrokes() = runBlocking(Dispatchers.Main.immediate) {
        val state = newState()
        state.load()
        val far = InkStroke(listOf(InkPoint(500f, 500f, 0), InkPoint(540f, 560f, 20)), brush = InkBrush(size = 8f, color = 0xFF000000.toInt()))
        state.layer.add(stroke())
        state.layer.add(far)
        state.saver.request()
        state.saver.flush()
        assertEquals(2, graph.repository.loadInk(state.page.id).size)

        state.layer.beginErase()
        assertTrue(state.layer.eraseAt(10f, 10f, 6f))
        assertEquals(1, state.layer.strokes.size)
        state.saver.request() // a save mid-drag stores the half-erased state
        state.saver.flush()
        assertEquals(1, graph.repository.loadInk(state.page.id).size)

        state.layer.cancelErase()
        assertEquals(2, state.layer.strokes.size)
        state.saver.request()
        state.saver.flush()
        val stored = graph.repository.loadInk(state.page.id)
        assertEquals(2, stored.size)
        assertEquals(state.layer.strokes.map { it.points }, stored.map { it.points })
    }
}
