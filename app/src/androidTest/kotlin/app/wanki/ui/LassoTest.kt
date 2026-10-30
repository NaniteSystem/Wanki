package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.input.InkClipboard
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.SelectionHandles
import app.wanki.input.ToolKind
import app.wanki.input.ToolSelection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LassoTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val settings get() = rule.activity.graph.settings
    private val stamp = System.nanoTime().toString()

    @Before
    fun reset() {
        settings.setTool(InkSection.NOTEBOOK, ToolSelection())
        rule.runOnUiThread { InkClipboard.clip = null }
    }

    @After
    fun restore() = reset()

    /** A short pen stroke around (x, y) on the page (world units). */
    private fun ink(x: Float, y: Float) = InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 120f, y + 30f, 40), InkPoint(x + 200f, y, 80)))

    private fun notebook(title: String, pages: Int, landscape: Boolean = false, first: List<InkStroke> = listOf(ink(400f, 200f))): List<PageEntity> = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.BLANK, "auto", landscape = landscape)
        repeat(pages - 1) { repo.addPage(nb) }
        val all = repo.pages(nb.id)
        repo.saveInk(all[0], first)
        all
    }

    private fun tiles(before: Set<InkTiles>): InkTiles {
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        return rule.runOnUiThread { InkTiles.live().first { it !in before } }
    }

    private fun open(title: String, pageText: String): InkTiles {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText(pageText), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        val t = tiles(before)
        rule.waitUntil(10_000) { rule.runOnUiThread { t.layer.strokes.isNotEmpty() } }
        return t
    }

    private fun strokes(layer: InkLayer) = rule.runOnUiThread { layer.strokes.toList() }

    /** Lasso tool, then a loop around most of the canvas. */
    private fun lassoAll(layer: InkLayer) {
        rule.onNodeWithTag("tool-LASSO").performClick()
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * 0.05f, height * 0.05f))
            moveTo(Offset(width * 0.95f, height * 0.05f))
            moveTo(Offset(width * 0.95f, height * 0.95f))
            moveTo(Offset(width * 0.05f, height * 0.95f))
            moveTo(Offset(width * 0.05f, height * 0.06f))
            up()
        }
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.hasSelection } }
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
    }

    private fun more(item: String) {
        rule.onNodeWithTag("sel-more").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(item), 5_000)
        rule.onNodeWithTag(item).performClick()
        rule.waitForIdle()
    }

    /** The whole screen (menus and dialogs are other windows) into /sdcard/shots-task7, for the person reviewing the task. */
    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task7")
        sh("screencap -p /sdcard/shots-task7/$name.png")
    }

    @Test
    fun copyPasteOnAnotherPage() {
        val title = "Lasso copy $stamp"
        val pages = notebook(title, 2)
        val t1 = open(title, "стр. 1 из 2")
        lassoAll(t1.layer)
        shot("selection-bar-handles")
        rule.onNodeWithTag("sel-more").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-copy"), 5_000)
        shot("selection-menu")
        rule.onNodeWithTag("sel-copy").performClick()
        rule.waitForIdle()
        assertEquals(1, rule.runOnUiThread { InkClipboard.clip?.strokes?.size })
        // the original stays
        assertEquals(1, strokes(t1.layer).size)

        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.onNodeWithTag("page-next").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        val t2 = tiles(before)
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-paste"), 5_000)
        rule.onNodeWithTag("menu-paste").performClick()
        rule.waitUntil(5_000) { strokes(t2.layer).size == 1 }
        // the paste is selected, with the lasso ready to move it
        assertEquals(1, rule.runOnUiThread { t2.layer.selection.size })
        assertEquals(ToolKind.LASSO, settings.tool(InkSection.NOTEBOOK).kind)
        // and stored with the page
        rule.waitUntil(5_000) { runBlocking { repo.loadInk(pages[1].id).size == 1 } }
    }

    @Test
    fun moveToNewPageWithUndo() {
        val title = "Lasso move $stamp"
        val pages = notebook(title, 2)
        val t1 = open(title, "стр. 1 из 2")
        lassoAll(t1.layer)
        rule.onNodeWithTag("sel-more").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-move-page"), 5_000)
        rule.onNodeWithTag("sel-move-page").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("move-page-new"), 5_000)
        // the current page is not offered
        assertTrue(rule.onAllNodes(hasTestTag("move-page-0")).fetchSemanticsNodes().isEmpty())
        shot("move-dialog")
        rule.onNodeWithTag("move-page-new").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Перенесено на стр. 3"), 10_000)
        shot("move-snackbar")
        assertTrue(strokes(t1.layer).isEmpty())
        val third = runBlocking { repo.pages(pages[0].notebookId) }.single { it.pageIndex == 2 }
        assertEquals(1, runBlocking { repo.loadInk(third.id) }.size)
        rule.waitUntil(5_000) { runBlocking { repo.loadInk(pages[0].id).isEmpty() } }

        rule.onNode(hasText("Отменить")).performClick()
        rule.waitUntil(5_000) { strokes(t1.layer).size == 1 }
        rule.waitUntil(5_000) { runBlocking { repo.loadInk(third.id).isEmpty() && repo.loadInk(pages[0].id).size == 1 } }
    }

    @Test
    fun recolorFromMenu() {
        val title = "Lasso colour $stamp"
        notebook(title, 1)
        val t = open(title, "стр. 1 из 1")
        lassoAll(t.layer)
        more("sel-color")
        rule.waitUntilAtLeastOneExists(hasTestTag("color-3"), 5_000)
        shot("color-dialog")
        rule.onNodeWithTag("color-3").performClick()
        rule.waitUntil(5_000) { strokes(t.layer).single().brush.color == ToolSelection.PALETTE[3] }
        // one undo step
        rule.onNodeWithTag("undo").performClick()
        rule.waitUntil(5_000) { strokes(t.layer).single().brush.color == ToolSelection().color }
    }

    @Test
    fun scaleHandleDrag() {
        val title = "Lasso scale $stamp"
        notebook(title, 1)
        val t = open(title, "стр. 1 из 1")
        lassoAll(t.layer)
        val before = strokes(t.layer).single().bounds()!!
        val (from, to) = rule.runOnUiThread {
            val vp = t.viewport!!
            val touch = 48f * rule.activity.resources.displayMetrics.density
            val h = SelectionHandles.scaleHandle(t.layer.selectionBounds()!!, vp.scale, touch)
            val start = Offset(h.x * vp.scale + vp.offsetX, h.y * vp.scale + vp.offsetY)
            start to start + Offset(120f, 40f)
        }
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(from)
            moveTo(from + (to - from) / 2f)
            moveTo(to)
            up()
        }
        rule.waitUntil(5_000) { strokes(t.layer).single().bounds()!!.width > before.width * 1.1f }
        val after = strokes(t.layer).single()
        assertEquals((before.left + before.right) / 2, (after.bounds()!!.left + after.bounds()!!.right) / 2, 1f)
        assertTrue(after.brush.size > ToolSelection().size)
        shot("selection-scaled")
        rule.onNodeWithTag("undo").performClick()
        rule.waitUntil(5_000) { strokes(t.layer).single().bounds() == before }
    }

    @Test
    fun offEdgePrompt() {
        val title = "Lasso edge $stamp"
        notebook(title, 1, landscape = true, first = listOf(ink(300f, 300f), ink(1250f, 400f)))
        val t = open(title, "стр. 1 из 1")
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-template"), 5_000)
        rule.onNodeWithTag("menu-template").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("paper-landscape"), 5_000)
        rule.onNodeWithTag("paper-landscape").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("select-offpage"), 5_000)
        rule.onNode(hasText("Часть записей оказалась за краем страницы")).assertExists()
        shot("offpage-prompt")
        rule.onNodeWithTag("select-offpage").performClick()
        rule.waitUntil(5_000) { rule.runOnUiThread { t.layer.selection.size == 1 } }
        assertEquals(1250f, rule.runOnUiThread { t.layer.selection.single().points[0].x })
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
        shot("offpage-selected")
    }

    @Test
    fun customGrey() {
        val title = "Lasso grey $stamp"
        notebook(title, 1)
        open(title, "стр. 1 из 1")
        rule.onNodeWithTag("tool-PEN").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("tool-options"), 5_000)
        rule.onNodeWithTag("color-custom").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("custom-saturation"), 5_000)
        rule.onNodeWithTag("custom-value").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.onNodeWithTag("custom-saturation").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        shot("custom-colour-grey")
        rule.onNode(hasText("Готово")).performClick()
        rule.waitForIdle()
        val c = settings.tool(InkSection.NOTEBOOK).color
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        assertTrue("grey: $r $g $b", r == g && g == b && r in 100..156)
    }
}
