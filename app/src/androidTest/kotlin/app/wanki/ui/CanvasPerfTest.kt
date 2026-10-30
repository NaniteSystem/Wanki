package app.wanki.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
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
import app.wanki.input.InkTiles
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import app.wanki.ui.notebooks.NotebookScreen
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Speed of a page with 3000 strokes ([PerfInk.page]), opened in the real notebook screen.
 * Every number goes to logcat tag `WankiPerf`. Thresholds are the stage П3 targets for wanki-phone.
 */
class CanvasPerfTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = instrumentation.targetContext.graph.repository

    private lateinit var page: PageEntity

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    /** A notebook whose first page holds [PerfInk.page]; returns the notebook id. */
    private fun seed(name: String, strokes: Int = STROKES): String = runBlocking {
        val nb = repo.createNotebook("$name ${System.nanoTime()}", PageTemplate.LINED, "ja")
        page = repo.pages(nb.id).first()
        if (strokes > 0) repo.saveInk(page, PerfInk.page(strokes))
        page = repo.pages(nb.id).first()
        nb.id
    }

    private fun open(notebookId: String) {
        rule.setContent {
            WankiTheme {
                val host = remember { SnackbarHostState() }
                val scope = rememberUiScope()
                val undo = remember { Undo(repo, host, scope) }
                CompositionLocalProvider(LocalUndo provides undo) {
                    NotebookScreen(notebookId, null, onBack = {}, onGrid = {})
                }
            }
        }
    }

    /** The tiles of the page with the seeded ink, once it is loaded. */
    private fun loadedTiles(strokes: Int): InkTiles? = InkTiles.live().firstOrNull { it.layer.strokes.size == strokes && it.allVisibleReady }

    /** Waits (polling the main thread) until the loaded page shows every visible tile from its bitmap. */
    private fun awaitAllVisibleReady(timeoutMs: Long = 20_000, strokes: Int = STROKES): InkTiles {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            onMain { loadedTiles(strokes) }?.let { return it }
            SystemClock.sleep(2)
        }
        throw AssertionError("visible tiles not ready within $timeoutMs ms")
    }

    @Test
    fun openPageShowsInkQuickly() {
        val nb = seed("Perf open")
        val start = SystemClock.uptimeMillis()
        open(nb)
        awaitAllVisibleReady()
        val ms = SystemClock.uptimeMillis() - start
        log("open: all visible ink ready in $ms ms")
        assertTrue("page open took $ms ms", ms <= 1500)
    }

    @Test
    fun panFramesStayFast() {
        val (frames, p90, max) = pan(STROKES)
        assertTrue("pan p90 $p90 ms", p90 <= 20.0)
        assertTrue("pan max $max ms", max <= 100.0)
    }

    /**
     * Reference without a threshold: the same pan over an empty page, i.e. what a frame of this screen costs
     * without any ink (on wanki-phone mostly the software GPU). Compare with [panFramesStayFast].
     */
    @Test
    fun panEmptyPageReference() {
        pan(0)
    }

    /** Pans [FRAMES] frames over a page with [strokes] strokes; returns the sorted frame times (ms), p90 and max. */
    private fun pan(strokes: Int): Triple<List<Double>, Double, Double> {
        open(seed("Perf pan", strokes))
        val tiles = awaitAllVisibleReady(strokes = strokes)
        val vp = onMain { tiles.viewport!! }
        // zoomed a little so 120 × 12 px of panning stays on the page (level 0, like the fitted page)
        onMain { vp.restore(1.4f, vp.offsetX, 600f) }
        awaitAllVisibleReady(strokes = strokes)
        SystemClock.sleep(1500) // margin tiles
        val durations = ConcurrentLinkedQueue<Long>()
        val parts = ConcurrentLinkedQueue<LongArray>()
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            durations += m.getMetric(FrameMetrics.TOTAL_DURATION)
            parts += LongArray(BREAKDOWN.size) { m.getMetric(BREAKDOWN[it].second) }
        }
        val window = rule.activity.window
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        val done = CountDownLatch(1)
        onMain {
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                var n = 0
                override fun doFrame(frameTimeNanos: Long) {
                    vp.transform(0f, -12f, 1f, 0f, 0f)
                    if (++n < FRAMES) Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                }
            })
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        SystemClock.sleep(500) // the last frames' metrics
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        val frames = durations.toList().drop(5).map { it / 1_000_000.0 }.sorted()
        assertTrue("no frames measured", frames.isNotEmpty())
        val p90 = frames[(frames.size * 0.9).toInt().coerceAtMost(frames.lastIndex)]
        val max = frames.last()
        log("pan ($strokes strokes): ${frames.size} frames, median %.1f ms, p90 %.1f ms, max %.1f ms".format(frames[frames.size / 2], p90, max))
        log("pan frames: " + frames.joinToString { "%.1f".format(it) })
        // where a frame's time goes: median of each stage over the measured frames
        val stages = parts.toList().drop(5)
        log("pan frame breakdown (median ms): " + BREAKDOWN.indices.joinToString { i ->
            val v = stages.map { it[i] / 1_000_000.0 }.sorted()
            "${BREAKDOWN[i].first} %.1f".format(v[v.size / 2])
        })
        return Triple(frames, p90, max)
    }

    @Test
    fun newStrokeShowsWithinOneFrameBudget() {
        open(seed("Perf stroke"))
        val tiles = awaitAllVisibleReady()
        SystemClock.sleep(1000)
        val stroke = InkStroke((0..30).map { InkPoint(400f + it * 6f, 500f + it * 3f, it * 8L, 0.6f, 0.5f, 1.2f) }, InkTool.STYLUS, InkBrush(size = 4f))
        var addedAt = 0L
        var shownAt = 0L
        val done = CountDownLatch(1)
        // frame stages, to tell app work from the emulator's frame composition
        val frames = ConcurrentLinkedQueue<LongArray>()
        val thread = HandlerThread("frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            frames += LongArray(BREAKDOWN.size + 2) { i ->
                when (i) {
                    0 -> m.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                    1 -> m.getMetric(FrameMetrics.TOTAL_DURATION)
                    else -> m.getMetric(BREAKDOWN[i - 2].second)
                }
            }
        }
        rule.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        // added from a plain main-thread message, like a touch event
        Handler(instrumentation.targetContext.mainLooper).post {
            addedAt = System.nanoTime()
            tiles.layer.add(stroke)
            Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (stroke in tiles.pendingDirect || tiles.isReady(stroke)) {
                        shownAt = frameTimeNanos
                        done.countDown()
                    } else Choreographer.getInstance().postFrameCallback(this)
                }
            })
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(300)
        rule.activity.window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        val ms = (shownAt - addedAt) / 1_000_000.0
        log("new stroke: on screen after %.1f ms".format(ms))
        // the first frame after the add is the one that drew it
        frames.firstOrNull { it[0] > addedAt }?.let { f ->
            log("new stroke frame: starts %.1f ms after the add, total %.1f ms; ".format((f[0] - addedAt) / 1e6, f[1] / 1e6) +
                BREAKDOWN.indices.joinToString { "${BREAKDOWN[it].first} %.1f".format(f[it + 2] / 1e6) })
        }
        assertTrue("new stroke took $ms ms", ms <= 32.0)
    }

    @Test
    fun eraserStepIsCheap() {
        open(seed("Perf erase"))
        val tiles = awaitAllVisibleReady()
        val times = onMain {
            val layer = tiles.layer
            layer.beginErase()
            // along row 10 of the generated page
            val t = (0 until 100).map { i ->
                val start = SystemClock.elapsedRealtimeNanos()
                layer.eraseAt(20f + i * 9.6f, 30f + 10 * 34f + 17f, 6f)
                SystemClock.elapsedRealtimeNanos() - start
            }
            layer.endErase()
            t
        }.map { it / 1_000_000.0 }.sorted()
        val median = times[times.size / 2]
        log("eraser: median %.2f ms, max %.2f ms".format(median, times.last()))
        assertTrue("eraser step median $median ms", median <= 4.0)
    }

    @Test
    fun saveAfterOneStrokeIsCheap() {
        open(seed("Perf save"))
        val tiles = awaitAllVisibleReady()
        onMain { tiles.layer.add(InkStroke((0..30).map { InkPoint(300f + it * 6f, 900f + it * 2f, it * 8L, 0.6f, 0.5f, 1.2f) }, InkTool.STYLUS, InkBrush(size = 4f))) }
        // the editor saves the stroke itself at once: let that write finish so the two do not queue on the database
        SystemClock.sleep(1500)
        val encode = onMain { tiles.layer.snapshotEncoder() }
        // off the main thread, like the editor's saver
        val start = SystemClock.elapsedRealtimeNanos()
        val blob = encode()
        val encoded = SystemClock.elapsedRealtimeNanos()
        runBlocking { repo.saveInkBlob(page, blob) }
        val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        log("save after one stroke: %.1f ms (encode %.1f ms, %d bytes)".format(ms, (encoded - start) / 1_000_000.0, blob?.size ?: 0))
        assertTrue("save took $ms ms", ms <= 60.0)
    }

    private companion object {
        const val TAG = "WankiPerf"
        const val STROKES = 3000
        const val FRAMES = 120
        val BREAKDOWN = listOf(
            "unknownDelay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
            "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "animation" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
            "draw" to FrameMetrics.DRAW_DURATION,
            "sync" to FrameMetrics.SYNC_DURATION,
            "commandIssue" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swapBuffers" to FrameMetrics.SWAP_BUFFERS_DURATION,
            "gpu" to FrameMetrics.GPU_DURATION,
        )
    }
}
