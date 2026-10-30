package app.wanki.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.WankiTheme
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The tile cache shows exactly the page's ink: after edits, undo, panning and zooming. */
class InkTilesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repo get() = instrumentation.targetContext.graph.repository

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Opens a notebook whose first page holds [strokes]; returns its tiles once every visible one is ready. */
    private fun open(strokes: List<InkStroke>): InkTiles {
        val nb = runBlocking {
            val nb = repo.createNotebook("Tiles ${System.nanoTime()}", PageTemplate.BLANK, "ja")
            repo.saveInk(repo.pages(nb.id).first(), strokes)
            nb
        }
        rule.setContent {
            WankiTheme {
                val host = remember { SnackbarHostState() }
                val scope = rememberUiScope()
                val undo = remember { Undo(repo, host, scope) }
                CompositionLocalProvider(LocalUndo provides undo) { NotebookScreen(nb.id, null, onBack = {}, onGrid = {}) }
            }
        }
        return awaitReady { InkTiles.live().firstOrNull { it.layer.strokes.size == strokes.size } }
    }

    private fun awaitReady(find: () -> InkTiles?): InkTiles {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            onMain { find()?.takeIf { it.allVisibleReady && it.pendingDirect.isEmpty() } }?.let { rule.waitForIdle(); return it }
            SystemClock.sleep(10)
        }
        throw AssertionError("tiles not ready")
    }

    private fun InkTiles.awaitReady() = awaitReady { this }

    private fun canvas(name: String): ImageBitmap {
        rule.waitForIdle()
        val image = rule.onNodeWithTag("page-canvas").captureToImage()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        runCatching { File(dir, "tiles-$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { Log.w("WankiTiles", "screenshot $name not saved: ${it.message}") }
        return image
    }

    private fun dark(image: ImageBitmap): Int {
        val px = image.toPixelMap()
        var n = 0
        for (y in 0 until px.height) for (x in 0 until px.width) if (px[x, y].luminance() < 0.3f) n++
        return n
    }

    /** Pixels that differ clearly between two captures of the same size. */
    private fun diff(a: ImageBitmap, b: ImageBitmap): Int {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var n = 0
        for (y in 0 until pa.height) for (x in 0 until pa.width) {
            if (kotlin.math.abs(pa[x, y].luminance() - pb[x, y].luminance()) > 0.2f) n++
        }
        return n
    }

    private fun line(y: Float, size: Float = 12f) =
        InkStroke((0..40).map { InkPoint(100f + it * 20f, y, it * 8L, 0.7f, 0.5f, 1.2f) }, InkTool.STYLUS, InkBrush(size = size))

    @Test
    fun undoAndEraseLeaveNoStaleInk() {
        val tiles = open(PerfInk.page(600))
        val before = canvas("before")
        // a thick line through the written rows: drawn on top of the tile at once, then baked into it
        val extra = line(300f)
        onMain { tiles.layer.add(extra) }
        tiles.awaitReady()
        val added = canvas("added")
        assertTrue("the new line shows", diff(before, added) > 500)
        onMain { tiles.layer.undo() }
        tiles.awaitReady()
        assertEquals("undo brings back the page as it was", 0, diff(before, canvas("undone")))
        onMain { tiles.layer.redo() }
        tiles.awaitReady()
        assertEquals(0, diff(added, canvas("redone")))
        // erase the line where it crosses into another tile: nothing of it may stay in either tile
        onMain {
            tiles.layer.beginErase()
            for (x in 100..900 step 10) tiles.layer.eraseAt(x.toFloat(), 300f, 4f)
            tiles.layer.endErase()
        }
        tiles.awaitReady()
        val erased = canvas("erased")
        assertTrue("erasing removes ink", dark(erased) < dark(added))
        onMain { tiles.layer.undo() }
        tiles.awaitReady()
        assertEquals(0, diff(added, canvas("erase-undone")))
    }

    @Test
    fun panAndZoomShowTheSameInk() {
        val tiles = open(PerfInk.page(600))
        val vp = onMain { tiles.viewport!! }
        val fitted = canvas("fitted")
        // a pinch: the old level's tiles are drawn scaled until it ends, then the new level renders
        onMain { vp.pinching = true; vp.transform(0f, 0f, 2.4f, 0f, 0f) }
        rule.waitForIdle()
        canvas("pinching")
        onMain { vp.pinching = false }
        tiles.awaitReady()
        val zoomed = canvas("zoomed")
        assertTrue("ink shows zoomed in", dark(zoomed) > 1000)
        // pan away and back, then zoom back out to the same view
        onMain { vp.transform(0f, -900f, 1f, 0f, 0f) }
        tiles.awaitReady()
        canvas("panned")
        onMain { vp.transform(0f, 900f, 1f, 0f, 0f); vp.transform(0f, 0f, 1 / 2.4f, 0f, 0f) }
        tiles.awaitReady()
        // the same view as at first: bitmaps of the first level again
        assertTrue("same picture after zooming back", diff(fitted, canvas("back")) < 200)
    }
}
