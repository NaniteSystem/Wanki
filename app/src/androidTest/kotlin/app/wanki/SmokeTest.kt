package app.wanki

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    @Test
    fun walkThroughAllSections() {
        // Install a deck from the catalog: on a fresh install from the empty deck list, otherwise
        // (data left by an earlier run) from the menu; the deck may already be there
        rule.waitUntilAtLeastOneExists(hasTestTag("decks-menu"), 15_000)
        rule.waitForIdle()
        shot("00-start")
        if (rule.onAllNodes(hasTestTag("open-catalog")).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hasTestTag("open-catalog")).performClick()
        } else {
            rule.onNode(hasText("⋮")).performClick()
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
        rule.onNode(hasText("Статистика")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Прогноз"), 10_000)
        shot("05b-stats")
        back()

        // Card browser: search, open a card in the editor
        rule.onNode(hasText("Поиск")).performClick()
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
        rule.onNode(hasText("⋮")).performClick()
        rule.onNode(hasText("Тренировка почерка")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Тренировка почерка"), 10_000)
        rule.waitForIdle()
        shot("05g-practice")
        back()

        // Notebook: create, write on the page
        tab("Тетради")
        rule.onNode(hasText("+ Тетрадь")).performClick()
        rule.onNode(hasText("Создать")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        write("page-canvas", 4)
        shot("06-notebook-page")
        // lasso around the writing → text actions
        rule.onNode(hasText("Лассо")).performClick()
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
        rule.onAllNodes(hasText("Текст")).onFirst().performClick()
        shot("07c-notebook-text")
        back()

        // Boards: create one, draw on it
        tab("Доски")
        rule.onNode(hasText("+ Доска")).performClick()
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
        rule.onNode(hasText("Сегодня")).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("page-canvas"), 10_000)
        write("page-canvas", 2)
        shot("10-diary-day")
        back()

        // Notes
        tab("Заметки")
        rule.onNode(hasText("+ Заметка")).performClick()
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
