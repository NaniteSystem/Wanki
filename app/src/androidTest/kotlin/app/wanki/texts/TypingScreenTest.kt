package app.wanki.texts

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.TextEntity
import app.wanki.data.TextRepository
import app.wanki.graph
import app.wanki.srs.Rating
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The «Печать» study mode in the real app: typing, colours, drafts, ratings and the phone layout. */
@OptIn(ExperimentalTestApi::class)
class TypingScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = rule.activity.graph
    private val created = ArrayList<String>()

    @After
    fun cleanUp() = runBlocking { created.forEach { graph.texts.deleteText(it) } }

    private fun newText(content: String): TextEntity = runBlocking {
        graph.texts.createText("Печать ${System.nanoTime()}", "ru", TextKind.POEM, TextSchedule.FSRS, 5, content)!!
            .also { created += it.id }
    }

    private fun openTexts() {
        rule.waitUntilAtLeastOneExists(hasTestTag("open-texts"), 15_000)
        rule.onNode(hasTestTag("open-texts")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("new-text"), 10_000)
    }

    private fun openPractice(title: String) {
        openTexts()
        scrollToRow(hasText(title))
        rule.onNode(hasText(title)).performClick()
        practiceFromDetail()
    }

    /** The list may be split into two columns on a tablet: scroll whichever one holds the row. */
    private fun scrollToRow(row: SemanticsMatcher) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        val lists = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        for (i in 0 until lists) {
            if (runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(row) }.isSuccess) return
        }
        throw AssertionError("no list holds the row: $row")
    }

    private fun practiceFromDetail() {
        rule.waitUntilAtLeastOneExists(hasText("Рассказать весь текст"), 10_000)
        rule.onNode(hasText("Рассказать весь текст")).performScrollTo().performClick()
        chooseTyping()
    }

    private fun chooseTyping() {
        rule.waitUntilAtLeastOneExists(hasTestTag("mode-TYPE"), 10_000)
        rule.onNode(hasTestTag("mode-TYPE")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-stats"), 10_000)
    }

    private fun type(s: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-input"), 10_000)
        rule.onNode(hasTestTag("typing-input")).performTextInput(s)
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun typedText(): String =
        rule.onNode(hasTestTag("typing-input")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun recitations(textId: String) = runBlocking { graph.db.texts().recitations(textId) }

    @Test
    fun typePieceCleanly() {
        val t = newText("Мороз и солнце, день чудесный!\nЕщё ты дремлешь, друг прелестный")
        openPractice(t.title)
        shot("typing-memory-start")
        type("Мороз и солнце день чудесный Ещё ты дремлешь друг прелестный")
        rule.waitUntilAtLeastOneExists(hasText("Ошибок: 0"), 10_000)
        rule.onNode(hasTestTag("typing-done")).assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { recitations(t.id).size == 2 }
        val rows = recitations(t.id)
        assertTrue(rows.all { it.mode == TextRepository.MODE_TYPED && it.part == "PRACTICE" && !it.error })
    }

    @Test
    fun typoShowsWrongWord() {
        val t = newText("Мороз и солнце, день чудесный!")
        openPractice(t.title)
        type("Мороз и сонце день")
        val word = rule.onNode(hasTestTag("typing-word-2"))
        word.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "неверно"))
        rule.onNode(hasTestTag("typing-word-0")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "верно"))
        // a long press shows the correct spelling
        word.performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-correct"), 5_000)
        rule.onNode(hasTestTag("typing-correct")).assert(hasText("солнце"))
        val theme = graph.settings.theme
        try {
            rule.runOnUiThread { graph.settings.theme = "dark" }
            shot("typing-dark")
        } finally {
            rule.runOnUiThread { graph.settings.theme = theme }
        }
    }

    @Test
    fun draftSurvivesRecreate() {
        val t = newText("Мороз и солнце, день чудесный!\nЕщё ты дремлешь, друг прелестный")
        openPractice(t.title)
        type("Мороз и солнце")
        assertEquals("Мороз и солнце", typedText())

        rule.activityRule.scenario.recreate()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-input"), 15_000)
        assertEquals("Мороз и солнце", typedText())
        // (the keyboard may take the last word back into its composition, so only earlier words are checked)
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-word-0"), 10_000)
        rule.onNode(hasTestTag("typing-word-0")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "верно"))

        // leave and come back: the unfinished attempt is offered
        rule.waitUntil(10_000) { runBlocking { graph.texts.draft("PRACTICE:${graph.texts.pieces(t.id).joinToString(",") { it.id }}") } != null }
        back()
        practiceFromDetail()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-continue"), 10_000)
        rule.onNode(hasTestTag("typing-continue")).performClick()
        rule.waitForIdle()
        assertEquals("Мороз и солнце", typedText())
    }

    /** Crossing the 600 dp breakpoint (phone rotation) swaps the shell layout: the screen's state must survive. */
    @Test
    fun rotationKeepsTypingMode() {
        val t = newText("Мороз и солнце, день чудесный!\nЕщё ты дремлешь, друг прелестный")
        openPractice(t.title)
        type("Мороз и солнце ")
        assertEquals("Мороз и солнце ", typedText())
        fun orient(o: Int) {
            rule.activityRule.scenario.onActivity { it.requestedOrientation = o }
            rule.waitForIdle()
        }
        try {
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            rule.waitUntilAtLeastOneExists(hasTestTag("typing-input"), 10_000)
            assertEquals("Мороз и солнце ", typedText())
            rule.onNode(hasTestTag("typing-word-0")).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "верно"))
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            rule.waitUntilAtLeastOneExists(hasTestTag("typing-input"), 10_000)
            assertEquals("Мороз и солнце ", typedText())
        } finally {
            orient(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }

    @Test
    fun reviewTypedRatingApplied() {
        val t = newText("Ветер по морю гуляет и кораблик подгоняет, он бежит себе")
        runBlocking {
            val p = graph.texts.pieces(t.id).single()
            // learned a month ago: due for review now
            graph.texts.completePortion(t.id, listOf(p.id), 1_000, now = System.currentTimeMillis() - 30L * 86_400_000)
        }
        openTexts()
        val row = hasText("Повторить", substring = true) and hasText(t.title, substring = true)
        scrollToRow(row)
        rule.onNode(row).performClick()
        chooseTyping()
        type("Ветер по морю гуляет и кораблик подгоняет он бижит себе")
        rule.waitUntilAtLeastOneExists(hasText("Оценка: трудно"), 10_000)
        shot("typing-review-result")
        rule.onNode(hasTestTag("typing-next")).performClick()
        rule.waitUntil(10_000) { recitations(t.id).any { it.part == "REVIEW" } }
        val review = recitations(t.id).filter { it.part == "REVIEW" }.maxBy { it.at }
        assertEquals(Rating.HARD.value, review.rating)
        assertEquals(TextRepository.MODE_TYPED, review.mode)
    }

    @Test
    fun fitsPhone() {
        val t = newText("Мороз и солнце, день чудесный!\nЕщё ты дремлешь, друг прелестный")
        openPractice(t.title)
        val width = rule.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInRoot.width
        fun SemanticsNodeInteraction.fits() = assertIsDisplayed().also {
            val b = fetchSemanticsNode().boundsInRoot
            assertTrue("clipped: $b in $width", b.left >= 0f && b.right <= width + 0.5f)
        }
        rule.onNode(hasTestTag("typing-stats")).fits()
        rule.onNode(hasTestTag("mode-TYPE")).fits()
        rule.onNode(hasTestTag("mode-RECITE")).fits()
        rule.onNode(hasTestTag("typing-menu")).fits()
        type("Мороз и сол")
        shot("typing-memory-mid")
        rule.onNode(hasTestTag("typing-menu")).performClick()
        rule.onNode(hasTestTag("typing-level-SAMPLE")).performClick()
        rule.waitForIdle()
        shot("typing-sample-mid")
        rule.onNode(hasTestTag("typing-menu")).performClick()
        rule.onNode(hasTestTag("typing-finish")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-done"), 10_000)
        rule.onNode(hasTestTag("typing-done")).fits()
        rule.onNode(hasTestTag("typing-result")).fits()
        shot("typing-result")
    }

    /** Evidence for the layout review, pulled from the app's files dir; never an assertion. */
    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        runCatching { rule.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap() }
            .onFailure { Log.w("WankiTyping", "screenshot $name failed: ${it.message}") }
            .getOrNull()?.let { bmp -> File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
