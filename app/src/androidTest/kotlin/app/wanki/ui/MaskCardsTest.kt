package app.wanki.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.AnswerMode
import app.wanki.data.PageEntity
import app.wanki.data.PageObjectEntity
import app.wanki.data.PageObjectKind
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.data.SourceKind
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.input.TAPE
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MaskCardsTest {
    @get:Rule
    val rule = createComposeRule()

    private val repo = InstrumentationRegistry.getInstrumentation().targetContext.graph.repository
    private val stamp = System.nanoTime().toString()

    private class Made(val notebookId: String, val page: PageEntity)

    /** A notebook page with thick ink across 0..[inkBottom] and masks at [ys] (800 × 300 each). */
    private fun page(vararg ys: Float, size: PageSize = PageSize.A4, inkBottom: Float = 1400f): Made = runBlocking {
        val nb = repo.createNotebook("Скотч $stamp", PageTemplate.BLANK, "ru", size)
        val page = repo.pages(nb.id).single()
        val brush = InkBrush(size = 40f)
        repo.saveInk(page, (0 until (inkBottom / 60f).toInt()).map { i ->
            val y = i * 60f
            InkStroke(listOf(InkPoint(100f, y, 0), InkPoint(500f, y, 40), InkPoint(900f, y, 80)), brush = brush)
        })
        repo.savePageObjects(page, ys.mapIndexed { i, y ->
            PageObjectEntity(id = "m$i-$stamp", pageId = page.id, kind = PageObjectKind.MASK.name, x = 100f, y = y, w = 800f, h = 300f, updatedAt = 1)
        })
        Made(nb.id, page)
    }

    private fun deck() = runBlocking { repo.createDeck("Скотч $stamp", "ru", AnswerMode.CLASSIC) }

    private fun cards(page: PageEntity) = runBlocking { repo.observeCardsFrom(SourceKind.PAGE, page.id).first() }

    private fun show(vararg routes: Route): Navigator {
        val nav = Navigator(routes.toList())
        rule.setContent {
            val scope = rememberUiScope()
            val undo = remember { Undo(repo, SnackbarHostState(), scope) }
            CompositionLocalProvider(LocalUndo provides undo) { Content(nav) }
        }
        return nav
    }

    private fun share(tag: String): Float {
        rule.waitUntilAtLeastOneExists(hasTestTag(tag), 10_000)
        return tapeShare(rule.onNodeWithTag(tag).captureToImage())
    }

    /** Share of the picture's centre third that is tape coloured. */
    private fun tapeShare(img: ImageBitmap): Float {
        val px = img.toPixelMap()
        var n = 0
        var all = 0
        for (y in px.height / 3 until px.height * 2 / 3) for (x in px.width / 3 until px.width * 2 / 3) {
            all++
            if (px[x, y].toArgb() == TAPE) n++
        }
        return n.toFloat() / all
    }

    private fun review(deckId: String) = show(Route.Decks, Route.Review(deckId))

    @Test
    fun cardsFromTapeOncePerMask() {
        val m = page(200f, 700f)
        val deck = deck()
        InstrumentationRegistry.getInstrumentation().targetContext.graph.settings.setLastDeckFor(m.notebookId, deck.id)
        show(Route.Notebook(m.notebookId, m.page.id))
        rule.waitUntilAtLeastOneExists(hasTestTag("menu"), 10_000)
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-mask-cards"), 10_000)
        rule.onNodeWithTag("menu-mask-cards").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Новых карточек: 2", substring = true), 10_000)
        rule.onNodeWithTag("mask-cards-add").performClick()
        rule.waitUntil(5_000) { cards(m.page).size == 2 }
        assertTrue(cards(m.page).all { it.card.deckId == deck.id && it.card.answerMode == AnswerMode.CLASSIC.name })
        // again: nothing new
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu-mask-cards"), 10_000)
        rule.onNodeWithTag("menu-mask-cards").performClick()
        rule.waitUntilAtLeastOneExists(hasText("уже есть карточки", substring = true), 10_000)
        rule.onNodeWithTag("mask-cards-add").assertIsNotEnabled()
        assertEquals(0, runBlocking { repo.addMaskCards(deck.id, m.page.id) })
        assertEquals(2, cards(m.page).size)
    }

    @Test
    fun reviewShowsClosedThenOpenAndFollowsEdits() {
        val m = page(500f)
        val deck = deck()
        assertEquals(1, runBlocking { repo.addMaskCards(deck.id, m.page.id) })
        val nav = review(deck.id)
        assertTrue("question: tape closed", share("mask-closed") > 0.9f)
        rule.onNodeWithText("Показать ответ").performClick()
        val before = share("mask-open")
        assertTrue("answer: tape open", before < 0.1f)
        val inked = rule.onNodeWithTag("mask-open").captureToImage().toPixelMap()
        // the page under the mask is wiped: the card picture follows
        runBlocking { repo.saveInk(repo.page(m.page.id)!!, emptyList()) }
        rule.runOnUiThread { nav.back() }
        rule.waitForIdle()
        rule.runOnUiThread { nav.open(Route.Review(deck.id)) }
        rule.onNodeWithText("Показать ответ").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("mask-open"), 10_000)
        val wiped = rule.onNodeWithTag("mask-open").captureToImage().toPixelMap()
        assertNotEquals(inked[inked.width / 2, inked.height / 2], wiped[wiped.width / 2, wiped.height / 2])
    }

    @Test
    fun maskCardWithPurgedPageShowsPlaceholder() {
        val m = page(500f)
        val deck = deck()
        runBlocking {
            repo.addMaskCards(deck.id, m.page.id)
            repo.delete(TrashKind.NOTEBOOK, m.notebookId)
            repo.purge(TrashKind.NOTEBOOK, m.notebookId)
        }
        // the detached card is suspended (refs keep the page id); bring it back to see what review shows
        val ids = cards(m.page).map { it.card.id }
        assertEquals(1, ids.size)
        assertTrue(cards(m.page).single().card.suspended)
        runBlocking { repo.setSuspended(ids, false) }
        review(deck.id)
        rule.waitUntilAtLeastOneExists(hasTestTag("mask-gone"), 10_000)
        assertFalse(rule.onAllNodes(hasTestTag("mask-closed")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun maskBelowThumbnailCutIsCropped() {
        val m = page(3000f, size = PageSize.ENDLESS, inkBottom = 3600f)
        val deck = deck()
        runBlocking { repo.addMaskCards(deck.id, m.page.id) }
        review(deck.id)
        assertTrue("mask at y = 3000 is in the picture", share("mask-closed") > 0.9f)
    }
}
