package app.wanki

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.wanki.data.NoteBlockEntity
import app.wanki.data.NoteBlockType
import app.wanki.data.TextNoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The note editor saves what is on screen, including an emptied note, and a new note survives recreation. */
@OptIn(ExperimentalTestApi::class)
class NoteEditorTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val repo get() = rule.activity.graph.repository

    /** A stored note the user empties stays empty: the old text must not come back. */
    @Test
    fun emptiedStoredNoteIsSaved() {
        val title = "Emptied ${System.currentTimeMillis()}"
        val note = TextNoteEntity(title = title, body = "")
        runBlocking {
            repo.saveNoteWithBlocks(note, listOf(NoteBlockEntity(noteId = note.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "Удалить меня")))
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Заметки"), 15_000)
        rule.onNode(hasTestTag("tab-Заметки")).performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Удалить меня") and hasSetTextAction(), 10_000)
        rule.onNode(hasText(title) and hasSetTextAction()).performTextClearance()
        rule.onNode(hasText("Удалить меня") and hasSetTextAction()).performTextClearance()
        rule.onNode(hasTestTag("back")).performClick()

        rule.waitUntil(10_000) {
            runBlocking {
                val n = repo.textNote(note.id)
                n != null && n.title.isEmpty() && repo.noteBlocks(note.id).all { it.text.isEmpty() }
            }
        }
    }

    /** A new note keeps its id once saved, so a recreated (or restored) screen reopens it with its text. */
    @Test
    fun newNoteSurvivesRecreate() {
        val title = "New ${System.currentTimeMillis()}"
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Заметки"), 15_000)
        rule.onNode(hasTestTag("tab-Заметки")).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("+ Заметка")).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasTestTag("notes-empty-new")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodes(hasText("+ Заметка")).fetchSemanticsNodes().isNotEmpty()) rule.onNode(hasText("+ Заметка")).performClick()
        else rule.onNode(hasTestTag("notes-empty-new")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Заголовок"), 10_000)
        rule.onNode(hasText("Заголовок")).performTextInput(title)
        rule.onNode(hasText("Текст")).performTextInput("первая строка")
        rule.waitUntil(10_000) {
            runBlocking { repo.observeTextNotes().first().any { it.title == title && it.body == "первая строка" } }
        }
        rule.waitForIdle()

        rule.activityRule.scenario.recreate()

        rule.waitUntilAtLeastOneExists(hasText("первая строка") and hasSetTextAction(), 10_000)
        rule.onNode(hasText(title) and hasSetTextAction()).assertExists()
    }

    /** Deleting from the editor's menu offers «Отменить» on the notes list, and undo brings the note back. */
    @Test
    fun deleteFromEditorOffersUndo() {
        val title = "Del ${System.currentTimeMillis()}"
        val note = TextNoteEntity(title = title, body = "")
        runBlocking {
            repo.saveNoteWithBlocks(note, listOf(NoteBlockEntity(noteId = note.id, blockIndex = 0, type = NoteBlockType.TEXT.name, text = "текст")))
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("tab-Заметки"), 15_000)
        rule.onNode(hasTestTag("tab-Заметки")).performClick()
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
        rule.onNode(hasText(title)).performClick()
        rule.waitUntilAtLeastOneExists(hasText("текст") and hasSetTextAction(), 10_000)
        rule.onNode(hasTestTag("menu")).performClick()
        rule.onNode(hasTestTag("menu-delete")).performClick()
        rule.waitUntilAtLeastOneExists(hasText("Отменить"), 10_000)
        assertTrue(runBlocking { repo.textNote(note.id) }?.deleted == true)
        rule.onNode(hasText("Отменить")).performClick()
        rule.waitUntil(10_000) { runBlocking { repo.textNote(note.id) }?.deleted == false }
        rule.waitUntilAtLeastOneExists(hasText(title), 10_000)
    }
}
