package app.wanki.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.AppGraph
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ui.common.PageThumbnail
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class PageThumbnailTest {
    @get:Rule
    val rule = createComposeRule()

    private val graph: AppGraph get() = InstrumentationRegistry.getInstrumentation().targetContext.graph

    private fun darkCount(tag: String): Int {
        rule.waitForIdle()
        val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        var n = 0
        for (y in 0 until px.height) for (x in 0 until px.width) if (px[x, y].luminance() < 0.3f) n++
        return n
    }

    private fun show(page: PageEntity, w: Int = 200, h: Int = 283) {
        rule.setContent { PageThumbnail(page, Modifier.size(w.dp, h.dp).testTag("thumb")) }
    }

    @Test
    fun strokeShowsAsDarkPixels() {
        val repo = graph.repository
        val page = runBlocking {
            val nb = repo.createNotebook("Thumb ${System.nanoTime()}", PageTemplate.BLANK, "ja")
            val p = repo.pages(nb.id).first()
            // a thick diagonal across the page
            val pts = (0..40).map { InkPoint(100f + it * 20f, 200f + it * 25f, it * 10L) }
            repo.saveInk(p, listOf(InkStroke(pts, brush = InkBrush(size = 30f, color = 0xFF000000.toInt()))))
            p
        }
        show(page)
        rule.waitUntil(10_000) { darkCount("thumb") > 50 }
    }

    @Test
    fun unknownTemplateKeepsPlainPaper() {
        val repo = graph.repository
        val page = runBlocking {
            val nb = repo.createNotebook("Bad ${System.nanoTime()}", PageTemplate.BLANK, "ja")
            repo.pages(nb.id).first()
        }.copy(template = "NO_SUCH_TEMPLATE")
        show(page)
        rule.waitForIdle()
        rule.waitUntil(5_000) { darkCount("thumb") == 0 } // still shown, no crash
    }

    @Test
    fun landscapePageKeepsItsAspect() {
        val repo = graph.repository
        val page = runBlocking {
            val nb = repo.createNotebook("Land ${System.nanoTime()}", PageTemplate.BLANK, "ja")
            repo.pages(nb.id).first()
        }.copy(landscape = true)
        rule.setContent { PageThumbnail(page, Modifier.size(280.dp).testTag("thumb")) }
        rule.waitForIdle()
        val n = rule.onNodeWithTag("thumb").fetchSemanticsNode().size
        assertEquals(1414f / 1000f, n.width.toFloat() / n.height, 0.02f)
    }

    @Test
    fun emptyPageHasOnlyPaperColours() {
        val repo = graph.repository
        val page = runBlocking {
            val nb = repo.createNotebook("Empty ${System.nanoTime()}", PageTemplate.BLANK, "ja")
            repo.pages(nb.id).first()
        }
        show(page)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1500)
        assertEquals(0, darkCount("thumb"))
    }
}
