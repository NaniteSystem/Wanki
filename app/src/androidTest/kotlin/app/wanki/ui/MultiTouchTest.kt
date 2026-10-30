package app.wanki.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.layout.boundsInWindow
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.input.InkLayer
import app.wanki.input.InkTiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Multi-finger gestures on a notebook page with real touch events injected through UiAutomation:
 * a two-finger tap undoes, a three-finger tap redoes, a two-finger drag pans and draws nothing.
 */
@OptIn(ExperimentalTestApi::class)
class MultiTouchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun strokesNow(layer: InkLayer) = rule.runOnUiThread { layer.strokes.size }

    private fun open(title: String): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        return rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
    }

    /** Screen position of the canvas centre (the window may be offset by system bars). */
    private fun canvasCentre(): Offset {
        val inWindow = rule.onNodeWithTag("page-canvas").fetchSemanticsNode().boundsInWindow
        val loc = IntArray(2)
        rule.runOnUiThread { rule.activity.window.decorView.getLocationOnScreen(loc) }
        return Offset(inWindow.center.x + loc[0], inWindow.center.y + loc[1])
    }

    /** A gesture's clock: events are 20 ms apart whatever the injection latency, so a slow emulator cannot turn a tap into a long press. */
    private class Touch(val downTime: Long = SystemClock.uptimeMillis()) {
        var n = 0
        fun next() = downTime + 20L * n++
    }

    /** One event of a multi-finger gesture: the fingers' positions, in pointer order. */
    private fun inject(t: Touch, action: Int, fingers: List<Offset>) {
        val props = Array(fingers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(fingers.size) { MotionEvent.PointerCoords().apply { x = fingers[it].x; y = fingers[it].y; pressure = 1f; size = 0.1f } }
        val ev = MotionEvent.obtain(
            t.downTime, t.next(), action, fingers.size, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertNotNull(automation.injectInputEvent(ev, true))
        } finally {
            ev.recycle()
        }
    }

    private fun pointerDown(i: Int) = MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun pointerUp(i: Int) = MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** [n] fingers 80 px apart touch down together and lift together, without moving. */
    private fun tap(n: Int) {
        val c = canvasCentre()
        val f = List(n) { Offset(c.x + (it - (n - 1) / 2f) * 80f, c.y) }
        val t = Touch()
        inject(t, MotionEvent.ACTION_DOWN, f.take(1))
        for (i in 1 until n) inject(t, pointerDown(i), f.take(i + 1))
        for (i in n - 1 downTo 1) inject(t, pointerUp(i), f.take(i + 1))
        inject(t, MotionEvent.ACTION_UP, f.take(1))
        rule.waitForIdle()
    }

    private fun drawStroke(layer: InkLayer, before: Int) {
        rule.onNodeWithTag("page-canvas").performTouchInput { swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.8f, height * 0.5f), 400) }
        rule.waitUntil(5_000) { strokesNow(layer) == before + 1 }
    }

    @Test
    fun twoFingerTapUndoesAndThreeFingerTapRedoes() {
        val title = "Multitouch $stamp"
        runBlocking { repo.createNotebook(title, PageTemplate.BLANK, "auto") }
        val layer = open(title)
        drawStroke(layer, 0)
        rule.onNodeWithTag("undo").assertIsEnabled()

        tap(2)
        rule.waitUntil(5_000) { strokesNow(layer) == 0 }
        rule.onNodeWithTag("undo").assertIsNotEnabled()
        rule.onNodeWithTag("redo").assertIsEnabled()

        tap(3)
        rule.waitUntil(5_000) { strokesNow(layer) == 1 }
        rule.onNodeWithTag("undo").assertIsEnabled()
        rule.onNodeWithTag("redo").assertIsNotEnabled()
    }

    @Test
    fun twoFingerDragPansWithoutDrawing() {
        val title = "Multitouch pan $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.BLANK, "auto") }
        val layer = open(title)
        val page = runBlocking { repo.pages(nb.id).first() }
        val c = canvasCentre()
        val a0 = Offset(c.x - 60f, c.y + 200f)
        val b0 = Offset(c.x + 60f, c.y + 200f)
        val t = Touch()
        inject(t, MotionEvent.ACTION_DOWN, listOf(a0))
        inject(t, pointerDown(1), listOf(a0, b0))
        // both fingers go up the screen together, far beyond the tap slop
        for (step in 1..12) {
            val dy = -30f * step
            inject(t, MotionEvent.ACTION_MOVE, listOf(a0.copy(y = a0.y + dy), b0.copy(y = b0.y + dy)))
        }
        val end = listOf(a0.copy(y = a0.y - 360f), b0.copy(y = b0.y - 360f))
        inject(t, pointerUp(1), end)
        inject(t, MotionEvent.ACTION_UP, end.take(1))
        rule.waitForIdle()
        assertEquals("a pan draws nothing", 0, strokesNow(layer))
        rule.onNodeWithTag("undo").assertIsNotEnabled()
        // the view moved: the page remembers where it was left
        rule.waitUntil(10_000) { runBlocking { repo.pages(nb.id).first() }.viewY != null }
        val moved = runBlocking { repo.pages(nb.id).first() }
        assertNotEquals(page.viewY, moved.viewY)
    }

    private val stamp = System.nanoTime().toString()
}
