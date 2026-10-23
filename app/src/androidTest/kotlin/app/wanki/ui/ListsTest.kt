package app.wanki.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.wanki.MainActivity
import app.wanki.data.ListSort
import app.wanki.data.NotebookKind
import app.wanki.data.PageTemplate
import app.wanki.data.TextNoteEntity
import app.wanki.data.TrashKind
import app.wanki.graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ListsTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = rule.activity.graph
    private val repo get() = graph.repository
    private val stamp = System.nanoTime().toString()

    @After
    fun resetSort() {
        graph.settings.setListSort("notes", ListSort.EDITED)
    }

    private fun note(title: String): TextNoteEntity {
        val n = TextNoteEntity(title = title, body = "текст $title")
        runBlocking { repo.saveNoteWithBlocks(n, emptyList()) }
        Thread.sleep(5)
        return n
    }

    private fun openTab(label: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-$label"), 15_000)
        rule.onNodeWithTag("tab-$label").performClick()
    }

    private fun longPress(tag: String) {
        rule.waitUntilAtLeastOneExists(hasTestTag(tag), 10_000)
        rule.onNodeWithTag(tag).performTouchInput { longClick() }
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun sortByTitlePersists() {
        val a = note("A-$stamp")
        val b = note("B-$stamp") // newer: «Изменено» puts B first
        openTab("Заметки")
        rule.waitUntilAtLeastOneExists(hasTestTag("note-${b.id}"), 10_000)
        assertTrue(top("note-${b.id}") < top("note-${a.id}"))
        rule.onNodeWithTag("menu").performClick()
        rule.onNodeWithTag("menu-sort-TITLE").performClick()
        rule.waitForIdle()
        assertTrue(top("note-${a.id}") < top("note-${b.id}"))
        rule.activityRule.scenario.recreate()
        openTab("Заметки")
        rule.waitUntilAtLeastOneExists(hasTestTag("note-${a.id}"), 10_000)
        assertTrue(top("note-${a.id}") < top("note-${b.id}"))
    }

    @Test
    fun pinMovesToTop() {
        val a = note("P1-$stamp")
        val b = note("P2-$stamp")
        openTab("Заметки")
        rule.waitUntilAtLeastOneExists(hasTestTag("note-${b.id}"), 10_000)
        assertTrue(top("note-${b.id}") < top("note-${a.id}"))
        longPress("note-${a.id}")
        rule.onNodeWithTag("item-pin").performClick()
        rule.waitUntil(10_000) { top("note-${a.id}") < top("note-${b.id}") }
    }

    @Test
    fun renameFromMenu() {
        val a = note("R-$stamp")
        openTab("Заметки")
        longPress("note-${a.id}")
        rule.onNodeWithTag("item-rename").performClick()
        rule.onNodeWithTag("rename-field").performTextReplacement("Новое-$stamp")
        rule.onNodeWithTag("rename-ok").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Новое-$stamp"), 10_000)
        assertEquals("Новое-$stamp", runBlocking { repo.textNote(a.id) }?.title)
    }

    @Test
    fun duplicateAddsCopy() {
        val a = note("D-$stamp")
        openTab("Заметки")
        longPress("note-${a.id}")
        rule.onNodeWithTag("item-duplicate").performClick()
        rule.waitUntilAtLeastOneExists(hasText("D-$stamp (копия)"), 10_000)
    }

    @Test
    fun deleteShowsUndoAndRestores() {
        val a = note("X-$stamp")
        openTab("Заметки")
        longPress("note-${a.id}")
        rule.onNodeWithTag("item-delete").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Отменить"), 10_000)
        rule.onNodeWithTag("note-${a.id}").assertDoesNotExist()
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("note-${a.id}"), 10_000)
        rule.onNodeWithTag("note-${a.id}").assertIsDisplayed()
    }

    @Test
    fun emptyListHasOneNewButton() {
        val all = runBlocking { repo.observeNotebooks(NotebookKind.NOTEBOOK).first() }.map { it.notebook.id }
        runBlocking { all.forEach { repo.delete(TrashKind.NOTEBOOK, it) } }
        try {
            openTab("Тетради")
            rule.waitUntilAtLeastOneExists(hasTestTag("notebooks-empty-new"), 10_000)
            rule.onNodeWithText("+ Тетрадь").assertDoesNotExist()
        } finally {
            runBlocking { all.forEach { repo.restore(TrashKind.NOTEBOOK, it) } }
        }
    }

    @Test
    fun notebookCardHasThumbnailAndMenuHasSettings() {
        val nb = runBlocking { repo.createNotebook("N-$stamp", PageTemplate.LINED, "auto") }
        openTab("Тетради")
        longPress("notebook-${nb.id}")
        rule.onNodeWithTag("item-settings").assertIsDisplayed()
    }
}
