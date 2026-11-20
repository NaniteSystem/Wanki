package app.wanki.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.wanki.MainActivity
import app.wanki.data.AnswerMode
import app.wanki.data.PageEntity
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import app.wanki.data.PageTemplate
import app.wanki.data.SourceKind
import app.wanki.data.SourceRefEntity
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.TAPE
import app.wanki.input.ToolSelection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MaskToolTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    @Before
    fun reset() = rule.activity.graph.settings.setTool(InkSection.NOTEBOOK, ToolSelection())

    @After
    fun restore() = reset()

    /** A notebook whose page has thick strokes across its middle (so the centre of the canvas is ink). */
    private fun notebook(title: String): PageEntity = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.BLANK, "auto")
        val page = repo.pages(nb.id).single()
        val brush = InkBrush(size = 40f)
        repo.saveInk(page, (0 until 50).map { i ->
            val y = i * 30f // whole page height: a landscape tablet shows only its top
            InkStroke(listOf(InkPoint(100f, y, 0), InkPoint(500f, y, 40), InkPoint(900f, y, 80)), brush = brush)
        })
        page
    }

    private fun open(title: String): InkLayer {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before && it.layer.strokes.isNotEmpty() } } }
        val layer = rule.runOnUiThread { InkTiles.live().first { it !in before }.layer }
        rule.waitForIdle()
        return layer
    }

    private fun canvas(): ImageBitmap {
        rule.waitForIdle()
        return rule.onNodeWithTag("page-canvas").captureToImage()
    }

    /** Whether the centre of the canvas is tape: its pixel is the tape colour. */
    private fun centreIsTape(): Boolean {
        val px = canvas().toPixelMap()
        return px[px.width / 2, px.height / 2].toArgb() == TAPE
    }

    /** Dark pixels around the centre (ink showing). */
    private fun centreInk(): Int {
        val px = canvas().toPixelMap()
        var n = 0
        for (y in px.height * 2 / 5 until px.height * 3 / 5) for (x in px.width * 2 / 5 until px.width * 3 / 5) if (px[x, y].luminance() < 0.3f) n++
        return n
    }

    /** Polls a canvas capture a few times a second (back-to-back PixelCopy requests time out). */
    private fun waitPx(check: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        // a slow device can time out one PixelCopy; that counts as "not yet", not a failure
        while (!(try { check() } catch (e: AssertionError) { if (e.message?.contains("PixelCopy") != true) throw e; false })) {
            assertTrue("canvas never matched", android.os.SystemClock.uptimeMillis() < end)
            android.os.SystemClock.sleep(250)
        }
    }

    private fun tapeOverCentre() {
        rule.onNodeWithTag("tool-MASK").performClick()
        rule.onNodeWithTag("page-canvas").performTouchInput {
            down(Offset(width * 0.3f, height * 0.35f))
            moveTo(Offset(width * 0.4f, height * 0.45f))
            moveTo(Offset(width * 0.6f, height * 0.55f))
            moveTo(Offset(width * 0.7f, height * 0.65f))
            up()
        }
        rule.waitForIdle()
    }

    private fun tapCentre() {
        rule.onNodeWithTag("page-canvas").performTouchInput { click(center) }
        rule.waitForIdle()
    }

    private fun stored(page: PageEntity) = runBlocking { repo.pageObjects(page.id).filter { it.kind == PageObjectKind.MASK.name } }

    @Test
    fun tapeHidesInkAndOpens() {
        val title = "Mask $stamp"
        val page = notebook(title)
        open(title)
        waitPx { centreInk() > 0 }
        tapeOverCentre()
        rule.waitUntil(5_000) { stored(page).size == 1 }
        assertEquals("", stored(page).single().text)
        assertTrue(centreIsTape())
        assertEquals(0, centreInk())
        // a tap opens it, another closes it
        tapCentre()
        waitPx { centreInk() > 0 }
        tapCentre()
        waitPx { centreIsTape() }
        // «Открыть всё» / «Закрыть всё»
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-masks-open"), 5_000)
        rule.onNodeWithTag("menu-masks-open").performClick()
        waitPx { centreInk() > 0 }
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-masks-close"), 5_000)
        rule.onNodeWithTag("menu-masks-close").performClick()
        waitPx { centreIsTape() }
    }

    @Test
    fun masksSurviveReopenClosed() {
        val title = "Mask reopen $stamp"
        val page = notebook(title)
        open(title)
        tapeOverCentre()
        rule.waitUntil(5_000) { stored(page).size == 1 }
        tapCentre() // opened: view state only
        waitPx { centreInk() > 0 }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        val layer = open(title)
        assertEquals(1, rule.runOnUiThread { layer.objects.count { it.kind == PageObjectKind.MASK } })
        waitPx { centreIsTape() }
    }

    @Test
    fun undoMaskDeleteRestoresCard() {
        val title = "Mask card $stamp"
        val page = notebook(title)
        val card = runBlocking {
            repo.savePageObjects(page, listOf(PageObjectEntity(id = "m-$stamp", pageId = page.id, kind = PageObjectKind.MASK.name, x = 100f, y = 300f, w = 800f, h = 600f, updatedAt = 1)))
            val deck = repo.createDeck("Mask deck $stamp", "ru", AnswerMode.CLASSIC)
            repo.addCardFromSource(
                deck.id, "q", "a", AnswerMode.CLASSIC,
                SourceRefEntity(noteId = "", kind = SourceKind.PAGE.name, sourceId = page.id, objectId = "m-$stamp", linked = true, createdAt = 1),
            )
        }
        fun suspended() = runBlocking { repo.card(card.id)!!.card.suspended }
        val layer = open(title)
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.objects.isNotEmpty() } }
        rule.runOnUiThread {
            layer.selectedObjects.add("m-$stamp")
            layer.deleteSelection()
        }
        rule.waitUntil(5_000) { suspended() }
        rule.runOnUiThread { layer.undo() }
        rule.waitUntil(5_000) { !suspended() }
    }
}
