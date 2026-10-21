package app.wanki

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.wanki.data.PageTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** The screen and the user's place in it survive an activity recreation (rotation, theme switch). */
@OptIn(ExperimentalTestApi::class)
class RecreateTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun notebookPageSurvivesRecreate() {
        val title = "Recreate ${System.currentTimeMillis()}"
        runBlocking {
            val repo = rule.activity.graph.repository
            val nb = repo.createNotebook(title, PageTemplate.LINED, "auto")
            repo.addPage(nb)
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNode(hasTestTag("tab-Тетради")).performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 2"), 10_000)
        rule.onNode(hasTestTag("page-next")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)

        rule.activityRule.scenario.recreate()

        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("2 / 2").assertExists()
    }

    /** The header stays usable at phone width: «Добавить страницу» is visible and the new page appears. */
    @Test
    fun addPageWorksAtPhoneWidth() {
        val title = "Add ${System.currentTimeMillis()}"
        runBlocking { rule.activity.graph.repository.createNotebook(title, PageTemplate.LINED, "auto") }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNode(hasTestTag("tab-Тетради")).performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 1"), 10_000)
        rule.onNode(hasTestTag("add-page")).assertIsDisplayed().performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        rule.onNode(hasTestTag("page-canvas")).assertIsDisplayed()
        rule.onNode(hasTestTag("page-prev")).assertIsDisplayed().assertIsEnabled()
    }

    /** The text panel (side panel on a tablet) stays open when the page is turned. */
    @Test
    fun textPanelStaysOpenWhenPageTurns() {
        val title = "Panel ${System.currentTimeMillis()}"
        runBlocking {
            val repo = rule.activity.graph.repository
            repo.addPage(repo.createNotebook(title, PageTemplate.LINED, "auto"))
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNode(hasTestTag("tab-Тетради")).performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 2"), 10_000)
        rule.onNode(hasTestTag("show-text")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Распознанный текст"), 10_000)
        rule.onNode(hasTestTag("page-next")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        rule.waitForIdle()
        rule.onNodeWithText("Распознанный текст").assertExists()
    }
}
