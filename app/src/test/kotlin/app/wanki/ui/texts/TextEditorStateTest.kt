package app.wanki.ui.texts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.wanki.data.EditedPiece
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.texts.TextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextEditorStateTest {
    private fun loaded(): TextEditorState {
        val t = TextEntity(id = "t", title = "Стих", language = "ru")
        return TextEditorState().apply {
            load(t, listOf(
                TextPieceEntity(id = "p1", textId = "t", pieceIndex = 0, content = "Раз два", note = "один"),
                TextPieceEntity(id = "p2", textId = "t", pieceIndex = 1, content = "Три четыре", note = "два"),
            ))
        }
    }

    @Test
    fun splitAtTheCursorCopiesTheSchedule() {
        val s = loaded()
        s.editPiece(0, TextFieldValue("Раз два", TextRange(4))) // cursor before "два"
        assertTrue(s.canSplit(0))
        s.split(0)
        assertEquals(
            listOf(EditedPiece("p1", "Раз", "один"), EditedPiece(null, "два", "", scheduleFrom = "p1"), EditedPiece("p2", "Три четыре", "два")),
            s.pieces(),
        )
        assertEquals("Раз\nдва\nТри четыре", s.content)
    }

    @Test
    fun joinKeepsThePreviousPieceAndBothNotes() {
        val s = loaded()
        s.joinWithPrevious(1)
        assertEquals(listOf(EditedPiece("p1", "Раз два Три четыре", "один два")), s.pieces())
    }

    @Test
    fun typingInTheWholeTextKeepsPieceIds() {
        val s = loaded()
        s.editContent("Раз два\nТри четыре!")
        s.editContent("Раз два\nТри четыре!\nПять")
        assertEquals(
            listOf(EditedPiece("p1", "Раз два", "один"), EditedPiece("p2", "Три четыре!", "два"), EditedPiece(null, "Пять")),
            s.pieces(),
        )
    }

    @Test
    fun punctuationOnlyTextCannotBeSaved() {
        val s = TextEditorState()
        assertFalse(s.canSave)
        s.editContent("* * *\n—")
        assertFalse(s.canSave)
        s.editContent("* * *\n— Мороз")
        assertTrue(s.canSave)
    }

    @Test
    fun portionFollowsTheKindUntilSetAndLanguageFollowsDetectionUntilPicked() {
        val s = TextEditorState()
        s.changeKind(TextKind.PROSE)
        assertEquals(3, s.portion)
        s.changePortion(5)
        s.changeKind(TextKind.POEM)
        assertEquals(5, s.portion)

        s.detected("ar")
        assertEquals("ar", s.language)
        assertTrue(s.languageDetected)
        s.pickLanguage("ru")
        s.detected("ar")
        assertEquals("ru", s.language)
    }
}
