package app.wanki.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.wanki.data.TextNoteEntity
import app.wanki.data.TrashKind
import app.wanki.graph
import app.wanki.ui.common.Undo
import app.wanki.ui.common.rememberUiScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalTestApi::class)
class UndoSnackbarTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun deleteShowsSnackbarAndUndoRestores() {
        val repo = InstrumentationRegistry.getInstrumentation().targetContext.graph.repository
        val note = TextNoteEntity(title = "Undo ${System.nanoTime()}", body = "")
        runBlocking { repo.saveNoteWithBlocks(note, emptyList()) }
        lateinit var undo: Undo
        rule.setContent {
            val host = remember { SnackbarHostState() }
            val scope = rememberUiScope()
            undo = remember { Undo(repo, host, scope) }
            SnackbarHost(host)
        }
        rule.runOnUiThread { undo.deleted(TrashKind.NOTE, note.id) }
        rule.waitUntilAtLeastOneExists(hasText("Удалено"), 10_000)
        assertTrue(runBlocking { repo.textNote(note.id) }?.deleted == true)
        rule.onNodeWithText("Отменить").performClick()
        rule.waitUntil(10_000) { runBlocking { repo.textNote(note.id) }?.deleted == false }
        assertEquals(false, runBlocking { repo.textNote(note.id) }?.deleted)
    }
}
