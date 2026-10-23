package app.wanki.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.wanki.MainActivity
import app.wanki.data.PageTemplate
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
class TrashTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = rule.activity.graph
    private val repo get() = graph.repository
    private val stamp = System.nanoTime().toString()

    private fun stroke() = InkStroke((0..20).map { InkPoint(100f + it * 30f, 300f + it * 20f, it * 10L) }, brush = InkBrush(size = 8f, color = 0xFF000000.toInt()))

    private fun openTrash() {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Настройки"), 15_000)
        rule.onNodeWithTag("tab-Настройки").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("settings-trash"), 10_000)
        rule.onNodeWithTag("settings-trash").performScrollTo().performClick()
    }

    private fun inTrash(id: String) = runBlocking { repo.trash().any { it.id == id } }

    @Test
    fun deletedNotebookAppearsAndRestores() {
        val nb = runBlocking { repo.createNotebook("Корз $stamp", PageTemplate.LINED, "auto") }
        runBlocking { repo.delete(TrashKind.NOTEBOOK, nb.id) }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Корз $stamp"), 10_000)
        rule.onNodeWithTag("trash-restore-${nb.id}").performClick()
        rule.waitUntil(10_000) { !inTrash(nb.id) }
        assertFalse(runBlocking { repo.notebook(nb.id)!!.deleted })
    }

    @Test
    fun deletedPageShowsNotebookAndNumber() {
        val nb = runBlocking { repo.createNotebook("Стр $stamp", PageTemplate.LINED, "auto") }
        val page = runBlocking { repo.addPage(nb); repo.pages(nb.id)[1] }
        runBlocking { repo.delete(TrashKind.PAGE, page.id) }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Стр $stamp · стр. 2"), 10_000)
    }

    @Test
    fun purgeForeverAsksAndRemoves() {
        val nb = runBlocking { repo.createNotebook("Нав $stamp", PageTemplate.LINED, "auto") }
        runBlocking { repo.delete(TrashKind.NOTEBOOK, nb.id) }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Нав $stamp"), 10_000)
        rule.onNodeWithTag("trash-purge-${nb.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Удалить навсегда?"), 5_000)
        // cancelling keeps it
        rule.onNodeWithText("Отмена").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Удалить навсегда?").fetchSemanticsNodes().isEmpty() }
        assertTrue(inTrash(nb.id))
        rule.onNodeWithTag("trash-purge-${nb.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("confirm-ok"), 5_000)
        rule.onNodeWithTag("confirm-ok").performClick()
        rule.waitUntil(10_000) { !inTrash(nb.id) }
        assertNull(runBlocking { repo.notebook(nb.id) })
    }

    @Test
    fun emptyTrash() {
        val a = runBlocking { repo.createNotebook("Оч1 $stamp", PageTemplate.LINED, "auto") }
        val b = runBlocking { repo.createBoard("Оч2 $stamp") }
        runBlocking { repo.delete(TrashKind.NOTEBOOK, a.id); repo.delete(TrashKind.BOARD, b.id) }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Оч1 $stamp"), 10_000)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-empty-trash").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("confirm-ok"), 5_000)
        rule.onNodeWithTag("confirm-ok").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Корзина пуста"), 10_000)
        assertTrue(runBlocking { repo.trash() }.isEmpty())
        rule.onNodeWithText("Удалённое хранится 30 дней").assertExists()
    }

    @Test
    fun diaryDayDeleteAndRestore() {
        // a far-away past day so other tests' diary data does not interfere
        val day = LocalDate.of(2001, 3, 4)
        val diary = runBlocking { repo.diary() }
        val page = runBlocking { repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke())) } }
        val marked = { runBlocking { day.toEpochDay() in repo.observeDiaryDates(diary.id).first() } }
        assertTrue(marked())
        runBlocking { repo.delete(TrashKind.DIARY_DAY, page.id) }
        assertFalse(marked())
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Дневник · 4 мар."), 10_000)
        rule.onNodeWithTag("trash-restore-${page.id}").performClick()
        rule.waitUntil(10_000) { marked() }
        assertFalse(inTrash(page.id))
    }

    @Test
    fun restoreOfTakenDayShowsMessage() {
        val day = LocalDate.of(2001, 3, 5)
        val diary = runBlocking { repo.diary() }
        val old = runBlocking { repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke())) } }
        runBlocking { repo.delete(TrashKind.DIARY_DAY, old.id) }
        runBlocking { repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke(), stroke())) } }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasTestTag("trash-restore-${old.id}"), 10_000)
        rule.onNodeWithTag("trash-restore-${old.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Этот день уже записан"), 10_000)
        assertTrue(inTrash(old.id))
        assertNotNull(diary)
    }

    @Test
    fun daysLeftShown() {
        val nb = runBlocking { repo.createNotebook("Дни $stamp", PageTemplate.LINED, "auto") }
        runBlocking { graph.db.notebooks().trash(nb.id, System.currentTimeMillis() - 10 * 86_400_000L) }
        openTrash()
        rule.waitUntilAtLeastOneExists(hasText("Дни $stamp"), 10_000)
        rule.waitUntilAtLeastOneExists(hasText("осталось 20 дн.", substring = true), 5_000)
    }

    @Test
    fun diaryMenuDeletesDayWithUndo() {
        val day = LocalDate.now()
        val diary = runBlocking { repo.diary() }
        val page = runBlocking { repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke())) } }
        val marked = { runBlocking { day.toEpochDay() in repo.observeDiaryDates(diary.id).first() } }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Дневник"), 15_000)
        rule.onNodeWithTag("tab-Дневник").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("diary-day-${day.toEpochDay()}"), 10_000)
        rule.onNodeWithTag("diary-day-${day.toEpochDay()}").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu"), 10_000)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-delete-day").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Удалено"), 10_000)
        assertFalse(marked())
        assertTrue(inTrash(page.id))
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntil(10_000) { marked() }
    }

    private fun openDay(day: LocalDate) {
        rule.waitUntilAtLeastOneExists(hasTestTag("diary-day-${day.toEpochDay()}"), 10_000)
        rule.onNodeWithTag("diary-day-${day.toEpochDay()}").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("menu"), 10_000)
    }

    private fun deleteDayFromMenu(day: LocalDate) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Дневник"), 15_000)
        rule.onNodeWithTag("tab-Дневник").performClick()
        openDay(day)
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-delete-day").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Удалено"), 10_000)
    }

    /** Undo pressed while the same day is open again: the editor moves to the restored page, no second page for the date. */
    @Test
    fun diaryUndoOverReopenedDayShowsRestoredPage() {
        val day = LocalDate.now()
        val diary = runBlocking { repo.diary() }
        val page = runBlocking {
            repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke())); repo.setRecognizedText(it.id, "запись $stamp") }
        }
        deleteDayFromMenu(day)
        openDay(day)
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntil(10_000) { runBlocking { graph.db.notebooks().pageForDate(diary.id, day.toEpochDay())?.id == page.id } }
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("show-text").performClick()
        rule.waitUntilAtLeastOneExists(hasText("запись $stamp"), 10_000)
        assertFalse(inTrash(page.id))
    }

    /** Undo after the day was written again is refused with a message, the old entry stays in the trash. */
    @Test
    fun diaryUndoOnTakenDayShowsMessage() {
        val day = LocalDate.now()
        val page = runBlocking { repo.diaryPage(day).also { repo.saveInk(it, listOf(stroke())) } }
        deleteDayFromMenu(day)
        runBlocking { repo.diaryPage(day).also { assertTrue(it.id != page.id); repo.saveInk(it, listOf(stroke(), stroke())) } }
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Этот день уже записан"), 10_000)
        assertTrue(inTrash(page.id))
    }
}
