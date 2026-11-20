package app.wanki.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.AnswerMode
import app.wanki.data.PageEntity
import app.wanki.data.PageTemplate
import app.wanki.data.SourceKind
import app.wanki.data.SourceRefEntity
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.LocalUndo
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SourceLinksTest {
    @get:Rule
    val rule = createComposeRule()

    private val repo = InstrumentationRegistry.getInstrumentation().targetContext.graph.repository
    private val stamp = System.nanoTime().toString()

    private fun page(): PageEntity = runBlocking {
        val nb = repo.createNotebook("Источник $stamp", PageTemplate.LINED, "auto")
        repo.addPage(nb)
        repo.pages(nb.id)[1]
    }

    private fun card(page: PageEntity, deckId: String, front: String) = runBlocking {
        val ref = SourceRefEntity(noteId = "", kind = SourceKind.PAGE.name, sourceId = page.id, x = 100f, y = 200f, w = 300f, h = 100f, linked = false, createdAt = 1)
        repo.addCardFromSource(deckId, front, "a", AnswerMode.CLASSIC, ref)
    }

    private fun show(vararg routes: Route): Navigator {
        val nav = Navigator(routes.toList())
        rule.setContent {
            val scope = rememberUiScope()
            val undo = remember { Undo(repo, SnackbarHostState(), scope) }
            CompositionLocalProvider(LocalUndo provides undo) { Content(nav) }
        }
        return nav
    }

    private fun openSourceFromReview(page: PageEntity) {
        val deck = runBlocking { repo.createDeck("Источник $stamp", "ru", AnswerMode.CLASSIC) }
        card(page, deck.id, "q$stamp")
        show(Route.Decks, Route.Review(deck.id))
        rule.waitUntilAtLeastOneExists(hasText("q$stamp"), 10_000)
        rule.onNodeWithText("⋮").performClick()
        rule.onNodeWithText("Открыть источник").performClick()
    }

    @Test
    fun reviewOpensSourceWithArea() {
        openSourceFromReview(page())
        rule.waitUntilAtLeastOneExists(hasTestTag("source-area"), 10_000)
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
    }

    @Test
    fun trashedSourceIsRestoredAndOpened() {
        val p = page()
        runBlocking { repo.delete(TrashKind.PAGE, p.id) }
        openSourceFromReview(p)
        rule.waitUntilAtLeastOneExists(hasText("Страница в корзине"), 10_000)
        rule.onNodeWithTag("source-restore").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("source-area"), 10_000)
        assertFalse(runBlocking { repo.page(p.id) }!!.deleted)
    }

    @Test
    fun cardsFromHereListsPageCards() {
        val p = page()
        val deck = runBlocking { repo.createDeck("Отсюда $stamp", "ru", AnswerMode.CLASSIC) }
        card(p, deck.id, "first$stamp")
        card(p, deck.id, "second$stamp")
        val nav = show(Route.Notebooks, Route.Notebook(p.notebookId, p.id))
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.onNodeWithTag("menu").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Карточки отсюда (2)"), 10_000)
        rule.onNodeWithText("Карточки отсюда (2)").performClick()
        rule.waitUntilAtLeastOneExists(hasText("second$stamp"), 10_000)
        rule.onNodeWithText("first$stamp").performClick()
        rule.waitUntil(10_000) { nav.current is Route.CardEditor }
        assertEquals(Route.CardEditor::class, nav.current::class)
    }
}
