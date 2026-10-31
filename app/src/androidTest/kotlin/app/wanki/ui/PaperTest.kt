package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ui.common.PageGeometry
import app.wanki.ui.common.drawPage
import app.wanki.ui.notebooks.PageEditorState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class PaperTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()
    private val paper = Color(0xFFFFFEFA)

    /** A page drawn 1:1 into a bitmap with the same [drawPage] the editor, thumbnails and the PDF export use. */
    private fun render(template: PageTemplate, pageSize: PageSize = PageSize.A4, contentBottom: Float = 0f): PixelMap {
        val size = PageGeometry.size(false, pageSize, contentBottom)
        val image = ImageBitmap(size.width.toInt(), size.height.toInt())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(image), Size(size.width, size.height)) {
            drawPage(template, false, pageSize, contentBottom)
        }
        return image.toPixelMap()
    }

    private fun PixelMap.inked(x: Int, y: Int): Boolean {
        val c = this[x, y]
        return abs(c.red - paper.red) + abs(c.green - paper.green) + abs(c.blue - paper.blue) > 0.12f
    }

    @Test
    fun mizigeHasDiagonals() {
        val mizige = render(PageTemplate.MIZIGE)
        val tianzige = render(PageTemplate.TIANZIGE)
        // the first cell is 115 units from (40, 40); walk its main diagonal away from the dashed centre lines
        fun onDiagonal(p: PixelMap) = (8..48).count { t -> p.inked(40 + t, 40 + t) || p.inked(40 + t, 41 + t) } +
            (8..48).count { t -> p.inked(155 - t, 40 + t) || p.inked(155 - t, 41 + t) }
        assertTrue("diagonals drawn", onDiagonal(mizige) >= 20)
        assertEquals(0, onDiagonal(tianzige))
    }

    @Test
    fun verticalGenkouColumnsFromRight() {
        val p = render(PageTemplate.GENKOU_VERTICAL)
        // inside the first cell's height: the rightmost rule pixel is the first column's right edge
        val y = 66
        val right = (999 downTo 0).first { p.inked(it, y) }
        assertTrue("right edge at $right", abs(right - 950) <= 2)
        // the next column is a 0.3-cell gap to the left: nothing between them
        val gapStart = (right - 4 downTo 0).first { !p.inked(it, y) }
        assertTrue(gapStart > right - 8)
        // 20 columns: the last left edge is near the left margin of 50
        val left = (0..999).first { p.inked(it, y) }
        assertTrue("left edge at $left", abs(left - 50) <= 2)
    }

    @Test
    fun arabicBaselineOnRightMargin() {
        val p = render(PageTemplate.ARABIC)
        assertTrue("margin on the right", p.inked(910, 600))
        assertFalse("nothing on the left", p.inked(90, 600))
        assertTrue("baseline", p.inked(500, 120) || p.inked(500, 121) || p.inked(500, 119))
        assertTrue("next baseline 84 lower", p.inked(500, 204) || p.inked(500, 205) || p.inked(500, 203))
        assertTrue("faint guide 28 above", p.inked(500, 176))
        assertFalse(p.inked(500, 150))
    }

    @Test
    fun cornellCueAndSummary() {
        val p = render(PageTemplate.CORNELL)
        assertTrue("cue rule", (279..281).any { p.inked(it, 600) })
        assertTrue("summary rule", (1153..1155).any { p.inked(600, it) })
        assertFalse("cue column is empty", p.inked(100, 600))
        // notes lines every 56 in the notes area
        assertTrue((119..121).any { p.inked(600, it) })
        assertTrue((175..177).any { p.inked(600, it) })
    }

    @Test
    fun templatesFillTheWholeGrownHeight() {
        for (t in listOf(PageTemplate.CORNELL, PageTemplate.ARABIC, PageTemplate.MIZIGE, PageTemplate.GENKOU_VERTICAL, PageTemplate.LINED)) {
            val p = render(t, PageSize.ENDLESS, 2000f)
            assertEquals(2828, p.height)
            // some rule pixel in the last quarter of the grown sheet
            val any = (2121 until 2800).any { y -> (0 until 1000 step 3).any { x -> p.inked(x, y) } }
            assertTrue("$t reaches the bottom", any)
        }
    }

    private fun open(title: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task10")
        sh("screencap -p /sdcard/shots-task10/$name.png")
    }

    private fun state(pageId: String): PageEditorState {
        rule.waitUntil(10_000) { rule.runOnUiThread { PageEditorState.editorOf(pageId) != null } }
        return rule.runOnUiThread { PageEditorState.editorOf(pageId)!! }
    }

    @Test
    fun endlessPageGrowsWhileWriting() {
        val title = "Endless $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto", PageSize.ENDLESS) }
        val page = runBlocking { repo.pages(nb.id).first() }
        open(title)
        val s = state(page.id)
        assertEquals(1414f, rule.runOnUiThread { s.viewport.bounds!!.bottom })
        // bring world y 1300 onto the screen, then write there
        val y = rule.runOnUiThread {
            val screenY = 1300f * s.viewport.scale + s.viewport.offsetY
            if (screenY > 600f) s.viewport.transform(0f, 600f - screenY, 1f, 0f, 0f)
            1300f * s.viewport.scale + s.viewport.offsetY
        }
        rule.onNodeWithTag("page-canvas").performTouchInput { swipe(Offset(width * 0.2f, y), Offset(width * 0.7f, y), 300) }
        rule.waitUntil(10_000) { rule.runOnUiThread { s.viewport.bounds!!.bottom > 1414f } }
        val bottom = rule.runOnUiThread { s.viewport.bounds!!.bottom }
        assertEquals(2121f, bottom)
        rule.onNode(hasText("стр. 1 из 1")).assertExists()
        // the sheet is a single page of the notebook, and no re-fit moved the view sideways
        assertEquals(1, runBlocking { repo.pages(nb.id) }.size)
        // scroll down to the new paper
        rule.runOnUiThread { s.viewport.transform(0f, -2000f, 1f, 0f, 0f) }
        shot("endless-scrolled")
        assertEquals(2121f, rule.runOnUiThread { s.viewport.bounds!!.bottom })
    }

    @Test
    fun settingsOfferEndless() {
        val title = "Offer $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        open(title)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-notebook-settings").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settings-save"), 5_000)
        rule.onNodeWithTag("settings-landscape").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("settings-size-ENDLESS").performScrollTo().assertExists()
        rule.onNode(hasText("Бесконечная")).assertExists()
        assertTrue(rule.onAllNodesWithTextCount("ENDLESS") == 0)
        rule.onNodeWithTag("settings-size-ENDLESS").performClick()
        rule.onNodeWithTag("settings-landscape").performScrollTo().assertIsNotEnabled()
        shot("settings-endless")
        rule.onNodeWithTag("settings-save").performClick()
        rule.waitUntil(5_000) { runBlocking { repo.notebook(nb.id)!!.pageSize == PageSize.ENDLESS.name } }
        rule.waitForIdle()
        // the paper dialog of an endless page cannot turn it
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-template").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("paper-landscape"), 5_000)
        rule.onNodeWithTag("paper-landscape").assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(t: String) =
        onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().size

    @Test
    fun newPapersOnScreen() {
        for (t in listOf(PageTemplate.MIZIGE, PageTemplate.GENKOU_VERTICAL, PageTemplate.ARABIC, PageTemplate.CORNELL)) {
            val title = "Paper ${t.name} $stamp"
            runBlocking { repo.createNotebook(title, t, "auto") }
            open(title)
            shot("paper-${t.name}")
            rule.onNodeWithTag("back").performClick()
        }
    }
}
