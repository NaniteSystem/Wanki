package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextContains
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.AnswerMode
import app.wanki.data.CardWithNote
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.data.SourceKind
import app.wanki.graph
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.ToolSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CardFromSelectionTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    @Before
    fun reset() = rule.activity.graph.settings.setTool(InkSection.NOTEBOOK, ToolSelection())

    private fun ink(x: Float, y: Float) = InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 120f, y + 30f, 40), InkPoint(x + 200f, y, 80)))

    /** A notebook with [strokes] on its page and a deck remembered as its last one. */
    private fun setUp(title: String, strokes: List<InkStroke>): PageEntity = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.BLANK, "ru")
        val deck = repo.createDeck("Selection $stamp", "ru", AnswerMode.CLASSIC)
        rule.activity.graph.settings.setLastDeckFor(nb.id, deck.id)
        val page = repo.pages(nb.id)[0]
        repo.saveInk(page, strokes)
        page
    }

    private fun open(title: String): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        val layer = rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
        rule.waitUntil(10_000) { rule.runOnUiThread { layer.strokes.isNotEmpty() } }
        return layer
    }

    /** Lasso loop over the fraction [x0]..[x1] of the canvas width. */
    private fun lasso(layer: InkLayer, x0: Float, x1: Float) {
        rule.onNodeWithTag("tool-LASSO").performClick()
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * x0, height * 0.02f))
            moveTo(Offset(width * x1, height * 0.02f))
            moveTo(Offset(width * x1, height * 0.6f))
            moveTo(Offset(width * x0, height * 0.6f))
            moveTo(Offset(width * x0, height * 0.03f))
            up()
        }
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.hasSelection } }
    }

    private fun openCardDialog() {
        rule.onNodeWithTag("sel-more").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-card"), 5_000)
        rule.onNodeWithTag("sel-card").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("card-answer"), 10_000)
    }

    /** Fields typed by hand: recognition and translation models may be missing on the emulator. */
    private fun fill(answer: String) {
        rule.onNodeWithTag("card-answer").performTextReplacement(answer)
        rule.onNodeWithTag("card-question").performTextReplacement("q $answer")
    }

    private fun cards(page: PageEntity, n: Int): List<CardWithNote> {
        rule.waitUntil(10_000) { runBlocking { repo.observeCardsFrom(SourceKind.PAGE, page.id).first().size >= n } }
        return runBlocking { repo.observeCardsFrom(SourceKind.PAGE, page.id).first() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-p4a")
        sh("screencap -p /sdcard/shots-p4a/$name.png")
    }

    @Test
    fun cardKeepsPageAndBounds() {
        val title = "Card sel $stamp"
        val page = setUp(title, listOf(ink(300f, 200f)))
        val layer = open(title)
        lasso(layer, 0.02f, 0.98f)
        val bounds = rule.runOnUiThread { layer.selectionBounds()!! }
        openCardDialog()
        shot("card-dialog")
        fill("слово")
        rule.onNodeWithTag("card-add").performClick()
        val c = cards(page, 1).single()
        val ref = runBlocking { repo.sourceOf(c.note.id) }!!
        assertEquals(page.id, ref.sourceId)
        assertEquals(SourceKind.PAGE.name, ref.kind)
        assertFalse(ref.linked)
        assertEquals(bounds.left, ref.x!!, 0.5f)
        assertEquals(bounds.top, ref.y!!, 0.5f)
        assertEquals(bounds.right - bounds.left, ref.w!!, 0.5f)
        assertTrue(c.note.field(1).startsWith("слово<br><img src=\"card-"))
    }

    @Test
    fun addMoreUsesCurrentSelection() {
        val title = "Card more $stamp"
        val page = setUp(title, listOf(ink(100f, 200f), ink(900f, 200f)))
        val layer = open(title)
        rule.runOnUiThread { layer.selection.clear(); layer.selection.add(layer.strokes.minBy { it.points[0].x }) }
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
        openCardDialog()
        fill("один")
        rule.runOnUiThread { layer.selection.clear(); layer.selection.add(layer.strokes.maxBy { it.points[0].x }) }
        val second = rule.runOnUiThread { layer.selectionBounds()!! }
        rule.onNodeWithTag("card-add-more").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Карточка добавлена"), 10_000)
        rule.waitUntilAtLeastOneExists(hasTestTag("card-answer"), 10_000)
        fill("два")
        rule.onNodeWithTag("card-add").performClick()
        val all = cards(page, 2)
        val ref = runBlocking { all.map { repo.sourceOf(it.note.id)!! } }.single { it.x!! > 500f }
        assertEquals(second.left, ref.x!!, 0.5f)
    }

    @Test
    fun uncheckedPictureIsNotAttached() {
        val title = "Card nopic $stamp"
        val page = setUp(title, listOf(ink(300f, 200f)))
        val layer = open(title)
        lasso(layer, 0.02f, 0.98f)
        openCardDialog()
        fill("без")
        rule.onNodeWithTag("card-attach").performClick()
        rule.onNodeWithTag("card-add").performClick()
        assertEquals("без", cards(page, 1).single().note.field(1))
    }

    @Test
    fun textDialogSeedsEditedText() {
        val title = "Card seed $stamp"
        val page = setUp(title, listOf(ink(900f, 900f)))
        val block = PageObjectEntity(pageId = page.id, kind = PageObjectKind.TEXT.name, x = 100f, y = 100f, w = 300f, h = 80f, text = "исходный", updatedAt = System.currentTimeMillis())
        runBlocking { repo.savePageObjects(page, listOf(block)) }
        val layer = open(title)
        rule.waitUntil(10_000) { rule.runOnUiThread { layer.objects.isNotEmpty() } }
        rule.runOnUiThread { layer.selectedObjects.add(layer.objects.single().id) }
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-text"), 5_000)
        rule.onNodeWithTag("sel-text").performClick()
        rule.waitUntilAtLeastOneExists(hasSetTextAction(), 5_000)
        rule.onNode(hasSetTextAction()).performTextReplacement("исправленный")
        rule.onNode(hasText("В карточку")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("card-answer"), 10_000)
        rule.onNodeWithTag("card-answer").assertTextContains("исправленный")
        rule.onNodeWithTag("card-question").performTextReplacement("q")
        rule.onNodeWithTag("card-add").performClick()
        assertEquals("исправленный", cards(page, 1).single().note.field(1))
    }
}
