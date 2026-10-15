package app.wanki

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
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
            val bmp = rule.onAllNodes(isRoot())[i].captureToImage().asAndroidBitmap()
            val file = if (i == 0) "$name.png" else "$name-window$i.png"
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
        rule.waitUntilAtLeastOneExists(hasText("Кандзи · 1 класс (демо)"), 15_000)
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
        rule.onNode(hasText("Лаборатория письма (подробный разбор оценки)")).performClick()
        rule.waitForIdle()
        shot("13-lab")
    }
}
