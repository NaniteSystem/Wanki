package app.wanki

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.NotebookKind
import app.wanki.data.PageTemplate
import app.wanki.input.EraserSettings
import app.wanki.input.InkLayer
import app.wanki.input.InkSection
import app.wanki.input.InkTiles
import app.wanki.input.SelectionHandles
import app.wanki.data.TrashKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Walks through every section, writes with synthetic finger strokes and saves screenshots to
 * the app's external files dir (CI pulls them as an artifact).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val shotsDir: File by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        // a dialog is a second window (root): save the screen and the dialog separately
        val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes().size
        for (i in 0 until roots) {
            val file = if (i == 0) "$name.png" else "$name-window$i.png"
            // screenshots are evidence, not assertions: PixelCopy can time out on a busy emulator
            val bmp = (1..3).firstNotNullOfOrNull {
                runCatching { rule.onAllNodes(isRoot())[i].captureToImage().asAndroidBitmap() }
                    .onFailure { e -> Log.w("WankiSmoke", "screenshot $file failed: ${e.message}"); rule.waitForIdle() }
                    .getOrNull()
            } ?: continue
            File(shotsDir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The «new» FAB, or the empty-state button while the list is empty. */
    private fun clickNew(fab: String, emptyTag: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(fab)).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasTestTag(emptyTag)).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText(fab)).fetchSemanticsNodes().isNotEmpty()) rule.onNode(hasText(fab)).performClick()
        else rule.onNode(hasTestTag(emptyTag)).performClick()
    }

    private fun tab(label: String) = rule.onNode(hasTestTag("tab-$label")).performClick()

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** A few horizontal strokes inside the node, like writing 三. */
    private fun write(tag: String, strokes: Int = 3) {
        rule.onNode(hasTestTag(tag)).performTouchInput {
            for (i in 0 until strokes) {
                val y = height * (0.3f + 0.2f * i)
                swipe(Offset(width * 0.25f, y), Offset(width * 0.75f, y), 250)
            }
        }
        rule.waitForIdle()
    }

    /** The whole screen into /sdcard/shots-task12 (kept after the app is uninstalled), for the person reviewing the stage. */
    private fun review(name: String) {
        rule.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun sh(cmd: String) = ui.executeShellCommand(cmd).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
        sh("mkdir -p /sdcard/shots-task12")
        sh("screencap -p /sdcard/shots-task12/$name.png")
    }

    /** Tool popups are dropdown windows that test clicks pass by: real touches on the page header close them. */
    private fun closePopups() {
        fun open() = rule.onAllNodes(hasTestTag("tool-options")).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasTestTag("eraser-options")).fetchSemanticsNodes().isNotEmpty()
        val dm = rule.activity.resources.displayMetrics
        val top = rule.activity.window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0
        repeat(4) {
            if (!open()) return
            val t = SystemClock.uptimeMillis()
            for ((i, act) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                InstrumentationRegistry.getInstrumentation()
                    .sendPointerSync(MotionEvent.obtain(t, t + i * 50L, act, dm.widthPixels * 0.5f, top + 40 * dm.density, 0))
            }
            SystemClock.sleep(300)
            rule.waitForIdle()
        }
        assertTrue("tool popups closed", !open())
    }

    private fun strokes(layer: InkLayer) = rule.runOnUiThread { layer.strokes.toList() }

    /** The ⋮ menu of the page header, then [item]. */
    private fun menu(item: String) {
        rule.onNode(hasTestTag("menu")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(item), 5_000)
        rule.onNode(hasTestTag(item)).performClick()
    }

    /** One event of a pen gesture, injected like the hardware would (the hold is real time). */
    private fun pen(downTime: Long, action: Int, p: Offset) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.5f; size = 0.05f })
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try {
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(ev, true))
        } finally {
            ev.recycle()
        }
    }

    /**
     * Stage П3 on a new notebook page: partial eraser, lasso duplicate + rotate + undo, move to a new page and undo,
     * a text block, the Cornell paper, a pen held still straightens its stroke, PDF export to the share chooser.
     */
    private fun canvasTools() {
        val before = rule.runOnUiThread { InkTiles.live().toSet() }
        clickNew("+ Тетрадь", "notebooks-empty-new")
        rule.onNode(hasText("Создать")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        rule.waitUntil(10_000) { rule.runOnUiThread { InkTiles.live().any { it !in before } } }
        val tiles = rule.runOnUiThread { InkTiles.live().first { it !in before } }
        val layer = tiles.layer
        val repo = rule.activity.graph.repository
        val nb = runBlocking { repo.observeNotebooks(NotebookKind.NOTEBOOK).first().maxBy { it.notebook.createdAt }.notebook.id }

        // 1. two strokes (rows at 30 % and 50 % of the canvas)
        rule.onNode(hasTestTag("tool-LASSO")).performClick()
        rule.onNode(hasTestTag("tool-PEN")).performClick()
        closePopups()
        write("page-canvas", 2)
        rule.waitUntil(5_000) { strokes(layer).size == 2 }

        // 2. the eraser «Частично» cuts the upper stroke in two
        rule.onNode(hasTestTag("tool-ERASER")).performClick()
        rule.onNode(hasTestTag("tool-ERASER")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("eraser-options"), 5_000)
        rule.onNode(hasText("Частично")).performClick()
        rule.onNode(hasTestTag("eraser-size-S")).performClick()
        shot("26-eraser-partial"); review("26-eraser-partial")
        closePopups()
        rule.onNode(hasTestTag("page-canvas")).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.22f), Offset(width * 0.5f, height * 0.38f), 300)
        }
        rule.waitUntil(5_000) { strokes(layer).size == 3 }
        shot("27-erased"); review("27-erased")

        // 3. lasso the left piece → «Дублировать» → rotate the copy by its handle → one undo turns it back
        rule.onNode(hasTestTag("tool-LASSO")).performClick()
        rule.onNode(hasTestTag("page-canvas")).performTouchInput {
            down(Offset(width * 0.05f, height * 0.2f))
            moveTo(Offset(width * 0.5f, height * 0.2f))
            moveTo(Offset(width * 0.5f, height * 0.4f))
            moveTo(Offset(width * 0.05f, height * 0.4f))
            moveTo(Offset(width * 0.05f, height * 0.21f))
            up()
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
        assertEquals(1, rule.runOnUiThread { layer.selection.size })
        rule.onNode(hasTestTag("sel-more")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Дублировать"), 5_000)
        rule.onNode(hasText("Дублировать")).performClick()
        rule.waitUntil(5_000) { strokes(layer).size == 4 }
        val copied = strokes(layer).map { it.bounds() }
        val (from, to) = rule.runOnUiThread {
            val vp = tiles.viewport!!
            val touch = 48f * rule.activity.resources.displayMetrics.density
            val h = SelectionHandles.rotateHandle(layer.selectionBounds()!!, vp.scale, touch)
            val start = Offset(h.x * vp.scale + vp.offsetX, h.y * vp.scale + vp.offsetY)
            start to start + Offset(200f, 160f)
        }
        rule.onNode(hasTestTag("page-canvas")).performTouchInput {
            down(from)
            moveTo(from + (to - from) / 3f)
            moveTo(from + (to - from) * 2f / 3f)
            moveTo(to)
            up()
        }
        rule.waitUntil(5_000) { strokes(layer).map { it.bounds() } != copied }
        val turned = strokes(layer).last().bounds()!!
        assertTrue("the copy is turned", turned.height > copied.last()!!.height * 2)
        shot("28-duplicate-rotated"); review("28-duplicate-rotated")
        rule.onNode(hasTestTag("undo")).performClick()
        rule.waitUntil(5_000) { strokes(layer).map { it.bounds() } == copied }

        // 4. «На другую страницу…» → «Новая страница в конце», then «Отменить» brings the strokes back
        if (rule.onAllNodes(hasTestTag("sel-more")).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(hasTestTag("page-canvas")).performTouchInput {
                down(Offset(width * 0.05f, height * 0.2f))
                moveTo(Offset(width * 0.5f, height * 0.2f))
                moveTo(Offset(width * 0.5f, height * 0.4f))
                moveTo(Offset(width * 0.05f, height * 0.4f))
                moveTo(Offset(width * 0.05f, height * 0.21f))
                up()
            }
            rule.waitUntilAtLeastOneExists(hasTestTag("sel-more"), 5_000)
        }
        val moving = rule.runOnUiThread { layer.selection.size }
        rule.onNode(hasTestTag("sel-more")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("На другую страницу…"), 5_000)
        rule.onNode(hasText("На другую страницу…")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Новая страница в конце"), 5_000)
        rule.onNode(hasText("Новая страница в конце")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Перенесено на стр. 2"), 10_000)
        assertEquals(4 - moving, strokes(layer).size)
        shot("29-moved-to-page"); review("29-moved-to-page")
        rule.onNode(hasText("Отменить")).performClick()
        rule.waitUntil(5_000) { strokes(layer).size == 4 }
        rule.onNode(hasTestTag("tool-PEN")).performClick()

        // 5. a text block «Тест»
        menu("menu-insert-text")
        rule.waitUntilAtLeastOneExists(hasTestTag("text-block-field"), 5_000)
        rule.onNode(hasTestTag("text-block-field")).performTextInput("Тест")
        rule.onNode(hasTestTag("text-block-done")).performClick()
        // drawn on the canvas (no text node): the block is on the page and in the database
        rule.waitUntil(5_000) { rule.runOnUiThread { layer.objects.map { it.text } } == listOf("Тест") }
        rule.waitUntil(10_000) { runBlocking { repo.pageObjects(repo.pages(nb).first().id) }.map { it.text } == listOf("Тест") }
        shot("30-text-block"); review("30-text-block")

        // 6. the paper «Корнелл»
        menu("menu-template")
        rule.waitUntilAtLeastOneExists(hasText("Корнелл", substring = true), 5_000)
        rule.onNode(hasText("Корнелл", substring = true)).performScrollTo().performClick()
        rule.waitUntil(10_000) { runBlocking { repo.pages(nb).first().template } == PageTemplate.CORNELL.name }
        shot("31-cornell"); review("31-cornell")

        // 7. a wavy pen stroke held still at its end turns into a straight line; the new block is still selected:
        // switching tools drops the selection (a second tap on the current pen would open its popup instead)
        rule.onNode(hasTestTag("tool-LASSO")).performClick()
        rule.onNode(hasTestTag("tool-PEN")).performClick()
        rule.waitForIdle()
        closePopups()
        val r = rule.onNode(hasTestTag("page-canvas")).fetchSemanticsNode().boundsInWindow
        val loc = IntArray(2)
        rule.runOnUiThread { rule.activity.window.decorView.getLocationOnScreen(loc) }
        val box = r.translate(loc[0].toFloat(), loc[1].toFloat())
        val t0 = SystemClock.uptimeMillis()
        var last = Offset.Zero
        for (i in 0..40) {
            last = Offset(box.left + box.width * (0.2f + 0.5f * i / 40), box.top + box.height * 0.7f + 40f * sin(Math.PI.toFloat() * 4 * i / 40))
            pen(t0, if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, last)
            SystemClock.sleep(10)
        }
        SystemClock.sleep(700)
        pen(t0, MotionEvent.ACTION_UP, last)
        runCatching { rule.waitUntil(5_000) { strokes(layer).size == 5 } }
            .onFailure { review("32-straight-line-missing"); throw AssertionError("the pen stroke was not added: ${strokes(layer).size} strokes", it) }
        val line = strokes(layer).last().points
        assertTrue("a straight line", line.all { abs(it.y - line.first().y) < 0.5f })
        shot("32-straight-line"); review("32-straight-line")

        // 8. «Экспорт в PDF» → «Эта страница» → the system share chooser
        menu("menu-export-pdf")
        rule.waitUntilAtLeastOneExists(hasText("Эта страница"), 5_000)
        rule.onNode(hasText("Эта страница")).performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val app = rule.activity.packageName
        var chooser: String? = null
        val end = SystemClock.uptimeMillis() + 15_000
        while (chooser == null && SystemClock.uptimeMillis() < end) {
            chooser = automation.rootInActiveWindow?.packageName?.toString()?.takeIf { it != app }
            if (chooser == null) SystemClock.sleep(100)
        }
        assertTrue("share chooser shown: $chooser", chooser == "android" || chooser == "com.android.intentresolver")
        SystemClock.sleep(800)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/shots-task12/33a-share-chooser.png").close()
        SystemClock.sleep(1500)
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        rule.waitUntil(10_000) { automation.rootInActiveWindow?.packageName?.toString() == app }
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        shot("33-after-export"); review("33-after-export")
        // the export dialog may still be open over the page
        repeat(3) { if (rule.onAllNodes(hasTestTag("notebook-$nb")).fetchSemanticsNodes().isEmpty()) back() }
        rule.waitUntilAtLeastOneExists(hasTestTag("notebook-$nb"), 10_000)
        rule.activity.graph.settings.setEraser(InkSection.NOTEBOOK, EraserSettings())
    }

    @Test
    fun walkThroughAllSections() {
        // Install a deck from the catalog: on a fresh install from the empty deck list, otherwise
        // (data left by an earlier run) from the menu; the deck may already be there
        rule.waitUntilAtLeastOneExists(hasTestTag("menu"), 15_000)
        rule.waitForIdle()
        shot("00-start")
        if (rule.onAllNodes(hasTestTag("open-catalog")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hasTestTag("open-catalog")).performClick()
        } else {
            rule.onNode(hasTestTag("menu")).performClick()
            rule.onNode(hasText("Готовые колоды")).performClick()
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("catalog"), 10_000)
        shot("00b-catalog")
        if (rule.onAllNodes(hasTestTag("install-ja-kanji-1")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hasTestTag("install-ja-kanji-1")).performClick()
        }
        rule.waitUntilAtLeastOneExists(hasText("✓ Открыть"), 30_000)
        shot("00c-catalog-installed")
        back()
        rule.waitUntilAtLeastOneExists(hasText("1 класс"), 10_000)
        shot("01-decks")

        // Review: write into the cell; the check starts by itself after a pause
        rule.onAllNodes(hasText("Учить")).onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("writing-canvas"), 10_000)
        shot("02-review-question")
        write("writing-canvas", 3)
        shot("03-review-written")
        val auto = runCatching { rule.waitUntilAtLeastOneExists(hasText("Хорошо"), 12_000) }.isSuccess
        if (!auto) {
            // the automatic check did not fire: keep evidence, then press the button
            shot("04a-no-autocheck")
            rule.onAllNodes(hasText("Проверить")).onFirst().performClick()
            rule.waitUntilAtLeastOneExists(hasText("Хорошо"), 15_000)
        }
        shot("04-review-checked")
        rule.onAllNodes(hasText("Хорошо")).onFirst().performClick()
        rule.waitForIdle()
        shot("05-review-next")
        rule.onAllNodes(hasText("‹ Закрыть")).onFirst().performClick()
        rule.onNode(hasTestTag("menu")).performClick()
        rule.onNode(hasText("Статистика")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Прогноз"), 10_000)
        shot("05b-stats")
        back()

        // App-wide search: a card found by its question opens in the editor
        rule.onNode(hasTestTag("decks-search")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("global-search"), 10_000)
        rule.onNode(hasTestTag("global-search")).performTextInput("日")
        rule.waitUntilAtLeastOneExists(hasTestTag("search-group-CARDS"), 10_000)
        shot("05c0-global-search")
        rule.onAllNodes(hasText("день; солнце", substring = true)).onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasText("История ответов"), 10_000)
        back()
        back()

        // Card browser (from the menu): search, open a card in the editor
        rule.onNode(hasTestTag("menu")).performClick()
        rule.onNode(hasTestTag("decks-browser")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("card-search"), 10_000)
        rule.onNode(hasTestTag("card-search")).performTextInput("日")
        rule.waitUntilAtLeastOneExists(hasText("день; солнце", substring = true), 10_000)
        shot("05c-browser")
        rule.onAllNodes(hasText("день; солнце", substring = true)).onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasText("История ответов"), 10_000)
        shot("05d-card-editor")
        back()
        back()

        // A deck with its cards, then its settings
        rule.onNode(hasText("1 класс")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("deck-options"), 10_000)
        shot("05e-deck")
        rule.onNode(hasTestTag("deck-options")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Лимиты на день"), 10_000)
        shot("05f-deck-options")
        back()
        back()

        // Handwriting practice from the menu (the queue may be empty on a fresh install)
        rule.onNode(hasTestTag("menu")).performClick()
        rule.onNode(hasText("Тренировка почерка")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Тренировка почерка"), 10_000)
        rule.waitForIdle()
        shot("05g-practice")
        back()

        // Texts: the list from the decks screen, a new poem, its detail screen
        rule.onNode(hasTestTag("open-texts")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("new-text"), 10_000)
        shot("14-texts")
        rule.onNode(hasTestTag("new-text")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("text-content"), 10_000)
        rule.onNode(hasTestTag("text-content"))
            .performTextInput("Мороз и солнце; день чудесный!\nЕщё ты дремлешь, друг прелестный —\nПора, красавица, проснись")
        rule.waitForIdle()
        shot("15-text-editor")
        rule.onNode(hasTestTag("save-text")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("text-detail"), 10_000)
        rule.onAllNodes(hasText("Мороз", substring = true)).onFirst().assertIsDisplayed()
        shot("16-text-detail")

        // Study: learn the portion — read, vanishing cues, then a clean recital marks it learned
        rule.onNode(hasTestTag("learn-portion")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("mode-READ"), 10_000)
        shot("17-study-read")
        rule.onNode(hasTestTag("mode-CUES")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("cue-next"), 10_000)
        rule.onNode(hasTestTag("cue-next")).performClick()
        rule.onNode(hasTestTag("cue-next")).performClick()
        rule.waitForIdle()
        shot("18-study-cues")
        rule.onNode(hasTestTag("mode-RECITE")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("reveal"), 10_000)
        rule.onNode(hasTestTag("reveal")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("learned"), 10_000)
        rule.onNode(hasTestTag("learned")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("выучен", substring = true), 10_000)
        shot("19-text-learned")

        // Typing: the first line with one typo; the wrong word is marked, the result card appears
        rule.waitUntilAtLeastOneExists(hasText("Рассказать весь текст"), 10_000)
        rule.onNode(hasText("Рассказать весь текст")).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("mode-TYPE"), 10_000)
        rule.onNode(hasTestTag("mode-TYPE")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-input"), 10_000)
        rule.onNode(hasTestTag("typing-input")).performTextInput("Мороз и сонце день чудесный ")
        rule.waitForIdle()
        rule.onNode(hasTestTag("typing-word-2"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "неверно"))
        rule.onNode(hasTestTag("typing-word-0"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "верно"))
        shot("19b-typing")
        rule.onNode(hasTestTag("typing-menu")).performClick()
        rule.onNode(hasTestTag("typing-finish")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("typing-result"), 10_000)
        shot("19c-typing-result")
        back()
        rule.waitUntilAtLeastOneExists(hasText("Рассказать весь текст"), 10_000)
        back()
        rule.waitUntilAtLeastOneExists(hasText("Все тексты"), 10_000)
        shot("16b-texts-list")
        back()

        // Notebook: create, write on the page
        tab("Тетради")
        clickNew("+ Тетрадь", "notebooks-empty-new")
        rule.onNode(hasText("Создать")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        write("page-canvas", 4)
        shot("06-notebook-page")
        // lasso around the writing → text actions
        rule.onNode(hasTestTag("tool-LASSO")).performClick()
        rule.onNode(hasTestTag("page-canvas")).performTouchInput {
            down(Offset(width * 0.05f, height * 0.15f))
            moveTo(Offset(width * 0.95f, height * 0.15f))
            moveTo(Offset(width * 0.95f, height * 0.95f))
            moveTo(Offset(width * 0.05f, height * 0.95f))
            moveTo(Offset(width * 0.05f, height * 0.16f))
            up()
        }
        rule.waitUntilAtLeastOneExists(hasText("Текст и перевод"), 5_000)
        shot("07-notebook-lasso")
        rule.onNode(hasText("Текст и перевод")).performClick()
        rule.waitForIdle()
        shot("07b-text-dialog")
        rule.onNode(hasText("Закрыть")).performClick()
        // «Текст» lives in the notebook header's overflow menu
        rule.onNode(hasTestTag("menu")).performClick()
        rule.onNode(hasTestTag("show-text")).performClick()
        shot("07c-notebook-text")
        // a second page, back to the first, and the pen popup
        rule.onNode(hasTestTag("add-page")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 2 из 2"), 10_000)
        rule.onNode(hasTestTag("page-prev")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("стр. 1 из 2"), 10_000)
        shot("20-notebook-p1")
        rule.onNode(hasTestTag("tool-PEN")).performClick()
        rule.onNode(hasTestTag("tool-PEN")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("tool-options"), 5_000)
        shot("21-tool-popup")
        // a real touch outside the popup (the empty middle of the page header) closes it
        val inst = InstrumentationRegistry.getInstrumentation()
        val t = android.os.SystemClock.uptimeMillis()
        val dm = rule.activity.resources.displayMetrics
        val top = rule.activity.window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0
        val x = dm.widthPixels * 0.5f
        val y = top + 40 * dm.density
        for ((i, act) in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).withIndex()) {
            inst.sendPointerSync(android.view.MotionEvent.obtain(t, t + i * 50L, act, x, y, 0))
        }
        Thread.sleep(800); rule.waitForIdle(); shot("21b-popup-closed")

        // П2 order: page grid (move page 2 first, delete + undo), pin, delete + restore from the trash
        val repo = rule.activity.graph.repository
        val nb = runBlocking { repo.observeNotebooks(NotebookKind.NOTEBOOK).first().maxBy { it.notebook.createdAt }.notebook.id }
        val (p1, p2) = runBlocking { repo.pages(nb).map { it.id } }
        rule.onNode(hasTestTag("pages-grid")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-thumb-1"), 10_000)
        shot("23-page-grid")
        val first = rule.onNode(hasTestTag("page-thumb-0")).fetchSemanticsNode().boundsInRoot.center
        val second = rule.onNode(hasTestTag("page-thumb-1")).fetchSemanticsNode().boundsInRoot.center
        rule.onNode(hasTestTag("page-drag-1")).performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            repeat(12) { moveBy((first - second) / 12f) }
            moveBy(Offset(-2f, -2f))
            up()
        }
        rule.waitUntil(10_000) { runBlocking { repo.pages(nb).map { it.id } } == listOf(p2, p1) }
        shot("23b-page-moved")
        rule.onNode(hasTestTag("page-thumb-0")).performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("item-delete"), 5_000)
        rule.onNode(hasTestTag("item-delete")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Отменить"), 10_000)
        assertEquals(listOf(p1), runBlocking { repo.pages(nb).map { it.id } })
        shot("23c-page-deleted")
        rule.onNode(hasText("Отменить")).performClick()
        rule.waitUntil(10_000) { runBlocking { repo.pages(nb).map { it.id } } == listOf(p2, p1) }
        back()
        back()
        val card = hasTestTag("notebook-$nb")
        rule.waitUntilAtLeastOneExists(card, 10_000)
        rule.onNode(card).performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("item-pin"), 5_000)
        rule.onNode(hasTestTag("item-pin")).performClick()
        rule.waitUntil(10_000) { runBlocking { repo.notebook(nb)!!.pinned } }
        shot("24-notebook-pinned")
        rule.onNode(card).performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("item-delete"), 5_000)
        rule.onNode(hasTestTag("item-delete")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Отменить"), 10_000)
        assertTrue(runBlocking { repo.notebook(nb)!!.deleted })
        shot("24b-notebook-deleted")
        tab("Настройки")
        rule.onNode(hasTestTag("settings-trash")).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("trash-restore-$nb"), 10_000)
        shot("25-trash")
        rule.onNode(hasTestTag("trash-restore-$nb")).performClick()
        rule.waitUntil(10_000) { runBlocking { !repo.notebook(nb)!!.deleted } }
        back()
        // leave the list as it was: unpin
        runBlocking { repo.setPinned(TrashKind.NOTEBOOK, nb, false) }
        tab("Тетради")
        rule.waitUntilAtLeastOneExists(card, 10_000)
        shot("25b-notebook-restored")

        canvasTools()

        // Boards: create one, draw on it
        tab("Доски")
        clickNew("+ Доска", "boards-empty-new")
        rule.onNode(hasText("Создать")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        write("page-canvas", 2)
        shot("08-board")
        back()
        shot("08b-boards")

        // Diary
        tab("Дневник")
        rule.waitUntilAtLeastOneExists(hasText("Сегодня"), 10_000)
        shot("09-diary")
        // open yesterday and leave without writing: the day stays unmarked
        val yesterday = java.time.LocalDate.now().minusDays(1)
        if (yesterday.month == java.time.LocalDate.now().month) {
            val cell = hasTestTag("diary-day-${yesterday.toEpochDay()}")
            rule.onNode(cell).performClick()
            rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
            back()
            rule.waitUntilAtLeastOneExists(cell, 10_000)
            rule.onNode(cell).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            shot("22-diary")
        }
        rule.onNode(hasText("Сегодня")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        write("page-canvas", 2)
        shot("10-diary-day")
        back()

        // Notes
        tab("Заметки")
        clickNew("+ Заметка", "notes-empty-new")
        rule.onNode(hasText("Заголовок")).performTextInput("Проверка")
        rule.onNode(hasText("+ От руки")).performClick()
        rule.waitForIdle()
        shot("11-note")
        back()

        tab("Настройки")
        shot("12-settings")
        rule.onNode(hasText("Лаборатория письма (подробный разбор оценки)")).performScrollTo().performClick()
        rule.waitForIdle()
        shot("13-lab")
    }
}
