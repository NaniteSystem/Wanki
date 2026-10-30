package app.wanki.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.ToolSelection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * Real pen and finger events injected through UiAutomation: holding the pen at the end of a stroke
 * straightens it, and two-finger flings / the page number turn notebook pages.
 */
@OptIn(ExperimentalTestApi::class)
class StraightLineTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val stamp = System.nanoTime().toString()

    @Before
    fun resetTool() = rule.activity.graph.settings.setTool(InkSection.NOTEBOOK, ToolSelection())

    @After
    fun restoreTool() = resetTool()

    private fun open(title: String, pageText: String = "стр. 1 из 1"): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText(pageText), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        return rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
    }

    private fun canvasBox(): androidx.compose.ui.geometry.Rect {
        val r = rule.onNodeWithTag("page-canvas").fetchSemanticsNode().boundsInWindow
        val loc = IntArray(2)
        rule.runOnUiThread { rule.activity.window.decorView.getLocationOnScreen(loc) }
        return r.translate(loc[0].toFloat(), loc[1].toFloat())
    }

    private fun strokes(layer: InkLayer) = rule.runOnUiThread { layer.strokes.toList() }

    /** One event of a single-pointer gesture with [toolType], timed by the real clock (the hold is real time). */
    private fun send(downTime: Long, action: Int, p: Offset, toolType: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.5f; size = 0.05f })
        val source = if (toolType == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        try {
            assertNotNull(automation.injectInputEvent(ev, true))
        } finally {
            ev.recycle()
        }
    }

    /** A wavy pen stroke across the canvas that stops at its end; [holdMs] later the pen lifts. */
    private fun wavyStrokeThenLift(holdMs: Long) {
        val b = canvasBox()
        val t0 = SystemClock.uptimeMillis()
        val steps = 40
        var last = Offset.Zero
        for (i in 0..steps) {
            val x = b.left + b.width * (0.2f + 0.5f * i / steps)
            val y = b.top + b.height * 0.5f + 40f * sin(Math.PI.toFloat() * 4 * i / steps)
            last = Offset(x, y)
            send(t0, if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, last, MotionEvent.TOOL_TYPE_STYLUS)
            SystemClock.sleep(10)
        }
        SystemClock.sleep(holdMs)
        send(t0, MotionEvent.ACTION_UP, last, MotionEvent.TOOL_TYPE_STYLUS)
        rule.waitForIdle()
    }

    @Test
    fun holdingStraightens() {
        val title = "Straight hold $stamp"
        runBlocking { repo.createNotebook(title, PageTemplate.BLANK, "auto") }
        val layer = open(title)
        wavyStrokeThenLift(700)
        rule.waitUntil(5_000) { strokes(layer).size == 1 }
        val pts = strokes(layer).single().points
        val y0 = pts.first().y
        assertTrue("a straight line: ${pts.maxOf { abs(it.y - y0) }}", pts.all { abs(it.y - y0) < 0.5f })
        assertTrue("the line is longer than a few points", pts.size > 5)
        rule.onNodeWithTag("undo").performClick()
        rule.waitUntil(5_000) { strokes(layer).isEmpty() }
    }

    @Test
    fun liftingEarlyKeepsStroke() {
        val title = "Straight early $stamp"
        runBlocking { repo.createNotebook(title, PageTemplate.BLANK, "auto") }
        val layer = open(title)
        wavyStrokeThenLift(200)
        rule.waitUntil(5_000) { strokes(layer).size == 1 }
        val pts = strokes(layer).single().points
        val y0 = pts.first().y
        assertTrue("the wave is kept", pts.any { abs(it.y - y0) > 5f })
    }

    private fun notebookWithPages(title: String, n: Int): String = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.BLANK, "auto")
        repeat(n - 1) { repo.addPage(nb) }
        nb.id
    }

    /** Two fingers 80 px apart fling horizontally across [fraction] of the canvas width. */
    private fun twoFingerFling(left: Boolean, fraction: Float = 0.5f) {
        val b = canvasBox()
        val dx = (if (left) -1 else 1) * b.width * fraction
        val a0 = Offset(b.center.x - 40f + (if (left) b.width * fraction / 2 else -b.width * fraction / 2), b.center.y)
        val b0 = a0.copy(x = a0.x + 80f)
        var n = 0
        val t0 = SystemClock.uptimeMillis()
        fun inject(action: Int, f: List<Offset>) {
            val props = Array(f.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(f.size) { MotionEvent.PointerCoords().apply { x = f[it].x; y = f[it].y; pressure = 1f; size = 0.1f } }
            val ev = MotionEvent.obtain(t0, t0 + 20L * n++, action, f.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertNotNull(automation.injectInputEvent(ev, true)) } finally { ev.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, listOf(a0))
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(a0, b0))
        for (s in 1..10) inject(MotionEvent.ACTION_MOVE, listOf(a0.copy(x = a0.x + dx * s / 10), b0.copy(x = b0.x + dx * s / 10)))
        val end = listOf(a0.copy(x = a0.x + dx), b0.copy(x = b0.x + dx))
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
        inject(MotionEvent.ACTION_UP, end.take(1))
        rule.waitForIdle()
    }

    @Test
    fun swipeTurnsPage() {
        val title = "Swipe pages $stamp"
        val id = notebookWithPages(title, 3)
        open(title, "стр. 1 из 3")
        twoFingerFling(left = true)
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 3"), 5_000)
        twoFingerFling(left = false)
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 3"), 5_000)
        // before the first page nothing happens
        twoFingerFling(left = false)
        rule.waitForIdle()
        rule.onNodeWithText("стр. 1 из 3").assertExists()
        rule.onNodeWithTag("page-next").performClick()
        rule.onNodeWithTag("page-next").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 3 из 3"), 5_000)
        twoFingerFling(left = true)
        // no new page is made: still 3 of 3
        rule.onNodeWithText("стр. 3 из 3").assertExists()
        assertEquals(3, runBlocking { repo.pages(id).size })
    }

    @Test
    fun penSwipeAtEdgeDoesNotTurn() {
        val title = "Pen swipe $stamp"
        notebookWithPages(title, 3)
        open(title, "стр. 1 из 3")
        val b = canvasBox()
        val t0 = SystemClock.uptimeMillis()
        val y = b.center.y // starts 170 px from the screen edge: nearer, the system back gesture takes the touch
        for (i in 0..20) {
            val x = b.right - 170f - (b.width - 340f) * 0.6f * i / 20
            send(t0, if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, Offset(x, y + i), MotionEvent.TOOL_TYPE_STYLUS)
            SystemClock.sleep(8)
        }
        send(t0, MotionEvent.ACTION_UP, Offset(b.right - 170f - (b.width - 340f) * 0.6f, y + 20), MotionEvent.TOOL_TYPE_STYLUS)
        // the stroke was written on this page (undo is available) and the page did not turn
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("undo").assertIsEnabled() }.isSuccess }
        rule.onNodeWithText("стр. 1 из 3").assertExists()
    }

    @Test
    fun tapPageNumberJumps() {
        val title = "Jump pages $stamp"
        notebookWithPages(title, 3)
        open(title, "стр. 1 из 3")
        rule.onNodeWithTag("page-number").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Перейти на страницу"), 5_000)
        rule.onNodeWithTag("page-jump-field").performTextInput("3")
        rule.onNodeWithText("Перейти").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 3 из 3"), 5_000)
    }
}
