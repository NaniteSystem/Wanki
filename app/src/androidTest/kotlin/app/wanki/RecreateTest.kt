package app.wanki

import androidx.compose.ui.test.ExperimentalTestApi
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
        rule.waitUntilAtLeastOneExists(hasText("1/2"), 10_000)
        rule.onNodeWithText("▶").performClick()
        rule.waitUntilAtLeastOneExists(hasText("2/2"), 10_000)

        rule.activityRule.scenario.recreate()

        rule.waitUntilAtLeastOneExists(hasText("2/2"), 10_000)
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("2/2").assertExists()
    }
}
