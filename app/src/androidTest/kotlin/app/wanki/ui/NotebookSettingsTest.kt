package app.wanki.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import app.wanki.MainActivity
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class NotebookSettingsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository
    private val stamp = System.nanoTime().toString()

    private fun openNotebook(title: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
    }

    private fun openSettings() {
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-notebook-settings").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settings-save"), 5_000)
    }

    private fun pick(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun changeLanguageAndPaper() {
        val title = "Set $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        openNotebook(title)
        openSettings()
        pick("settings-lang-ja")
        pick("settings-paper-GRID")
        pick("settings-size-A5")
        rule.onNodeWithTag("settings-save").performClick()
        rule.waitUntil(5_000) { runBlocking { repo.notebook(nb.id)!!.language == "ja" } }
        val saved = runBlocking { repo.notebook(nb.id)!! }
        assertEquals(PageTemplate.GRID.name, saved.template)
        assertEquals(PageSize.A5.name, saved.pageSize)
        rule.onNodeWithTag("add-page").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        val pages = runBlocking { repo.pages(nb.id) }
        assertEquals(PageTemplate.LINED.name, pages[0].template)
        assertEquals(PageTemplate.GRID.name, pages[1].template)
    }

    @Test
    fun landscapeNewPages() {
        val title = "Land $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        openNotebook(title)
        openSettings()
        pick("settings-landscape")
        rule.onNodeWithTag("settings-save").performClick()
        rule.waitUntil(5_000) { runBlocking { repo.notebook(nb.id)!!.landscape } }
        rule.onNodeWithTag("add-page").performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        val pages = runBlocking { repo.pages(nb.id) }
        assertFalse(pages[0].landscape)
        assertTrue(pages[1].landscape)
    }

    @Test
    fun toggleCurrentPageOrientation() {
        val title = "Turn $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.LINED, "auto") }
        val first = runBlocking { repo.pages(nb.id).first() }
        val stroke = InkStroke(
            (0..20).map { InkPoint(100f + it * 30f, 300f + it * 20f, it * 10L) },
            brush = InkBrush(size = 8f, color = 0xFF000000.toInt()),
        )
        runBlocking { repo.saveInk(first, listOf(stroke)) }
        openNotebook(title)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-template").performClick()
        rule.onNodeWithTag("paper-landscape").performClick()
        rule.waitUntil(5_000) { runBlocking { repo.pages(nb.id).first().landscape } }
        assertEquals(1, runBlocking { repo.loadInk(first.id) }.size)
    }

    @Test
    fun languageChangeKeepsFreshInk() {
        val title = "Ink $stamp"
        val nb = runBlocking { repo.createNotebook(title, PageTemplate.BLANK, "auto") }
        openNotebook(title)
        rule.onNodeWithTag("page-canvas").performTouchInput { swipe(Offset(width * 0.2f, height * 0.3f), Offset(width * 0.8f, height * 0.6f), 300) }
        openSettings()
        pick("settings-lang-ja")
        rule.onNodeWithTag("settings-save").performClick()
        rule.waitUntil(5_000) { runBlocking { repo.notebook(nb.id)!!.language == "ja" } }
        rule.waitForIdle()
        Thread.sleep(1500)
        // the same editor state stays (undo history survives): it is not reloaded from the database
        rule.onNodeWithTag("undo").assertIsEnabled()
        val page = runBlocking { repo.pages(nb.id).first() }
        assertEquals(1, runBlocking { repo.loadInk(page.id) }.size)
        // the editor still shows the stroke after the language change: leaving and reopening keeps it
        rule.onNodeWithTag("back").performClick()
        openNotebook(title)
        assertEquals(1, runBlocking { repo.loadInk(page.id) }.size)
    }
}
