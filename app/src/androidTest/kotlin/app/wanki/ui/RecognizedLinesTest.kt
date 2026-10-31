package app.wanki.ui

import android.os.SystemClock
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.MainActivity
import app.wanki.data.PageEntity
import app.wanki.data.PageLineEntity
import app.wanki.data.PageTemplate
import app.wanki.graph
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.input.InkTiles
import app.wanki.recognition.LineRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Answers «строка N» for the line at y ≈ N × 200 and fails for the one at [failAt]. */
private class FakeLines : LineRecognizer {
    val calls = CopyOnWriteArrayList<Int>()
    @Volatile var failAt: Int? = null
    @Volatile var failAll = false
    @Volatile var texts: Map<Int, String> = emptyMap()
    override fun candidateLanguages(language: String) = listOf("ru")
    override suspend fun recognizeLine(strokes: List<InkStroke>, w: Float, h: Float, pre: String, langs: List<String>, wait: Boolean): Pair<String, String?>? {
        val n = Math.round(strokes.first().bounds()!!.centerY / 200f)
        calls += n
        if (failAll || n == failAt) return null
        return (texts[n] ?: "строка $n") to "ru"
    }
}

@OptIn(ExperimentalTestApi::class)
class RecognizedLinesTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = rule.activity.graph
    private val repo get() = graph.repository
    private val stamp = System.nanoTime().toString()
    private val fake = FakeLines()
    private var original: LineRecognizer? = null

    @Before
    fun fakeOn() {
        original = graph.lineRecognizer
        graph.lineRecognizer = fake
    }

    @After
    fun restore() { original?.let { graph.lineRecognizer = it } }

    private fun stroke(y: Float, x: Float = 100f) = InkStroke(listOf(InkPoint(x, y, 0), InkPoint(x + 60f, y + 30f, 40), InkPoint(x + 120f, y, 80)))

    private fun threeLines(title: String): PageEntity = runBlocking {
        val nb = repo.createNotebook(title, PageTemplate.BLANK, "ru")
        val page = repo.pages(nb.id).first()
        repo.saveInk(page, listOf(stroke(185f), stroke(385f), stroke(585f)))
        page
    }

    private fun open(title: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        // «Текст» is in the overflow menu on a phone, a header button on a wide screen
        if (rule.onAllNodes(hasTestTag("show-text")).fetchSemanticsNodes().isEmpty()) rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("show-text").performClick()
    }

    private fun waitCalls(n: Int) {
        // waitUntil keeps the compose clock running (the pause before recognition is a delay on it)
        try { rule.waitUntil(30_000) { fake.calls.size >= n } } catch (_: Throwable) {}
        assertEquals(n, fake.calls.size)
    }

    private fun addStroke(y: Float, strokes: Int) {
        val layer = rule.runOnUiThread { InkTiles.live().first { it.layer.strokes.size == strokes }.layer }
        rule.runOnUiThread { layer.add(stroke(y, x = 300f)) }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task9")
        sh("screencap -p /sdcard/shots-task9/$name.png")
    }

    /** Opening does not recognise: a far-away fourth line is drawn to start the first recognition (4 lines). */
    private fun trigger() {
        addStroke(800f, 3)
        waitCalls(4)
    }

    private fun lines(page: PageEntity) = runBlocking { repo.pageLines(page.id) }

    @Test
    fun onlyChangedLineIsSentAgain() {
        val title = "Lines changed $stamp"
        val page = threeLines(title)
        open(title)
        trigger()
        rule.waitUntilAtLeastOneExists(hasTestTag("line-3"), 10_000)
        shot("lines-normal")
        fake.calls.clear()
        addStroke(400f, 4)
        waitCalls(1)
        assertEquals(listOf(2), fake.calls.toList())
        try { rule.waitUntil(4_000) { fake.calls.size > 1 } } catch (_: Throwable) {}
        assertEquals(1, fake.calls.size)
        assertEquals(4, lines(page).size)
    }

    @Test
    fun oneFailedLineKeepsOthers() {
        val title = "Lines failed $stamp"
        val page = threeLines(title)
        fake.failAt = 2
        open(title)
        trigger()
        rule.waitUntilAtLeastOneExists(hasText("Не удалось распознать строк: 1 — повторю позже"), 10_000)
        rule.onNodeWithTag("line-0").assertExists()
        rule.onNodeWithTag("line-2").assertExists()
        shot("lines-failed")
        assertEquals(listOf("строка 1", "строка 3", "строка 4"), lines(page).map { it.recognized })
    }

    @Test
    fun correctionSurvivesRecognition() {
        val title = "Lines corrected $stamp"
        val page = threeLines(title)
        open(title)
        trigger()
        rule.waitUntilAtLeastOneExists(hasTestTag("line-0"), 20_000)
        rule.onNodeWithTag("line-0").performClick()
        rule.onNodeWithTag("line-edit-0").performTextReplacement("исправлено")
        shot("lines-editing")
        rule.onNodeWithTag("line-done-0").performClick()
        try { rule.waitUntilAtLeastOneExists(hasTestTag("line-reset-0"), 10_000) } catch (e: Throwable) { shot("debug-corrected"); throw e }
        shot("lines-corrected")
        fake.calls.clear()
        addStroke(600f, 4)
        waitCalls(1)
        rule.waitUntil(10_000) { lines(page).any { it.lineIndex == 2 && it.recognized == "строка 3" && it.updatedAt > 0 } }
        assertEquals("исправлено", lines(page).first { it.lineIndex == 0 }.shown)
        rule.onNodeWithTag("line-0").assert(hasText("исправлено"))
        assertTrue(runBlocking { repo.searchPages("исправлено") }.any { it.pageId == page.id })
    }

    @Test
    fun resetCorrection() {
        val title = "Lines reset $stamp"
        val page = threeLines(title)
        open(title)
        trigger()
        rule.waitUntilAtLeastOneExists(hasTestTag("line-0"), 20_000)
        rule.onNodeWithTag("line-0").performClick()
        rule.onNodeWithTag("line-edit-0").performTextReplacement("исправлено")
        rule.onNodeWithTag("line-done-0").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("line-reset-0"), 10_000)
        rule.onNodeWithTag("line-reset-0").performClick()
        rule.waitUntil(10_000) { lines(page).first { it.lineIndex == 0 }.corrected == null }
        rule.onNodeWithTag("line-0").assert(hasText("строка 1"))
        rule.onNodeWithTag("line-reset-0").assertDoesNotExist()
        assertTrue(runBlocking { repo.searchPages("исправлено") }.none { it.pageId == page.id })
    }

    @Test
    fun legacyPageHitGetsHighlight() {
        // a page from before lines existed: only the joined text, no page_lines rows
        val word = "староеслово"
        val title = "Lines legacy $stamp"
        fake.texts = mapOf(2 to "вторая $word")
        runBlocking {
            val nb = repo.createNotebook(title, PageTemplate.BLANK, "ru")
            val page = repo.pages(nb.id).first()
            repo.saveInk(page, listOf(stroke(185f), stroke(385f), stroke(585f)))
            repo.setRecognizedText(page.id, "первая\nвторая $word\nтретья")
            assertTrue(repo.pageLines(page.id).isEmpty())
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasSetTextAction(), 10_000)
        rule.onNode(hasSetTextAction()).performTextInput(word)
        rule.waitUntilAtLeastOneExists(hasText("$title · стр. 1"), 10_000)
        rule.onNode(hasText("$title · стр. 1")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-highlight"), 20_000)
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun failedFirstRecognitionKeepsLegacyText() {
        val word = "староеслово"
        val title = "Lines legacy fail $stamp"
        fake.failAll = true
        val page = runBlocking {
            val nb = repo.createNotebook(title, PageTemplate.BLANK, "ru")
            val p = repo.pages(nb.id).first()
            repo.saveInk(p, listOf(stroke(185f), stroke(385f), stroke(585f)))
            repo.setRecognizedText(p.id, "первая $word")
            p
        }
        open(title)
        addStroke(800f, 3)
        waitCalls(4)
        rule.waitUntilAtLeastOneExists(hasText("Не удалось распознать строк: 4 — повторю позже"), 10_000)
        assertTrue(runBlocking { repo.searchPages(word) }.any { it.pageId == page.id })
        // one line is read now, the rest still fail: the old text stays for what failed
        fake.calls.clear()
        fake.failAll = false
        fake.failAt = 2
        addStroke(600f, 4)
        waitCalls(4)
        rule.waitUntilAtLeastOneExists(hasText("Не удалось распознать строк: 1 — повторю позже"), 10_000)
        assertTrue(runBlocking { repo.searchPages(word) }.any { it.pageId == page.id })
    }

    @Test
    fun searchHitHighlightsLine() {
        val word = "найденноеслово"
        val title = "Lines search $stamp"
        // the stored rows below have made-up keys, so the page counts as stale and is read again by the fake
        fake.texts = mapOf(2 to "вторая $word")
        runBlocking {
            val nb = repo.createNotebook(title, PageTemplate.BLANK, "ru")
            val page = repo.pages(nb.id).first()
            repo.saveInk(page, listOf(stroke(185f), stroke(385f), stroke(585f)))
            fun l(i: Int, key: Long, t: String) = PageLineEntity(page.id, key, i, 100f, i * 200f + 185f, 220f, i * 200f + 215f, t, null, 1)
            repo.saveLines(page.id, listOf(l(0, 11, "первая"), l(1, 12, "вторая $word"), l(2, 13, "третья")))
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Тетради"), 15_000)
        rule.onNodeWithTag("tab-Тетради").performClick()
        rule.waitUntilAtLeastOneExists(hasSetTextAction(), 10_000)
        rule.onNode(hasSetTextAction()).performTextInput(word)
        rule.waitUntilAtLeastOneExists(hasText("$title · стр. 1"), 10_000)
        rule.onNode(hasText("$title · стр. 1")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-highlight"), 10_000)
        shot("lines-highlight")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("page-highlight")).fetchSemanticsNodes().isEmpty() }
    }
}
