package app.wanki.ui.texts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.wanki.data.EditedPiece
import app.wanki.data.TextEntity
import app.wanki.data.TextPieceEntity
import app.wanki.texts.PieceWords
import app.wanki.texts.ReadingOverride
import app.wanki.texts.TextKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextEditorStateTest {
    private fun loaded(vararg pieces: Pair<String, String>, kind: TextKind = TextKind.POEM, language: String = "ru") =
        TextEditorState().apply {
            val t = TextEntity(id = "t", title = "Стих", language = language, kind = kind.name)
            load(t, pieces.mapIndexed { i, (content, note) ->
                TextPieceEntity(id = "p${i + 1}", textId = "t", pieceIndex = i, content = content, note = note)
            })
        }

    private fun loaded() = loaded("Раз два" to "один", "Три четыре" to "два")

    /** Feeds the whole-text field one state at a time, as typing does. */
    private fun TextEditorState.type(vararg states: String) = states.forEach(::editContent)

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

    @Test
    fun lineMergedIntoItsNeighbourAndSplitAgainKeepsItsId() {
        val s = loaded("Мороз" to "один", "Пора" to "два")
        s.type("МорозПора", "Мороз\nПора") // Backspace at the start of line 2, then Enter
        assertEquals(listOf(EditedPiece("p1", "Мороз", "один"), EditedPiece("p2", "Пора", "два")), s.pieces())
        // merged, split one letter off, then put right: the cut-off "зПора" is a split of p1 until it reads "Пора" again
        s.type("МорозПора", "Моро\nзПора", "Моро\nПора", "Мороз\nПора")
        assertEquals(listOf(EditedPiece("p1", "Мороз", "один"), EditedPiece("p2", "Пора", "два")), s.pieces())
    }

    @Test
    fun joinUndoneByEnterBringsThePieceBack() {
        val s = loaded("Мороз" to "один", "Пора" to "два")
        s.joinWithPrevious(1)
        s.type("Мороз\nПора")
        assertEquals(listOf(EditedPiece("p1", "Мороз", "один два"), EditedPiece("p2", "Пора", "два")), s.pieces())
    }

    @Test
    fun lineDeletedAndRetypedKeepsItsId() {
        val s = loaded("Мороз" to "", "Пора" to "два", "Встань" to "")
        s.type("Мороз\nПор\nВстань", "Мороз\nПо\nВстань", "Мороз\nП\nВстань", "Мороз\n\nВстань")
        assertEquals(listOf("p1", "p3"), s.pieces().map { it.id })
        s.type("Мороз\nП\nВстань", "Мороз\nПо\nВстань", "Мороз\nПор\nВстань", "Мороз\nПора\nВстань")
        assertEquals(
            listOf(EditedPiece("p1", "Мороз"), EditedPiece("p2", "Пора", "два"), EditedPiece("p3", "Встань")),
            s.pieces(),
        )
    }

    @Test
    fun proseTerminatorDeletedAndRetypedKeepsBothIds() {
        val s = loaded("Раз." to "один", "Два." to "два", kind = TextKind.PROSE)
        s.type("Раз. Два.", "Раз Два.", "Раз. Два.")
        assertEquals(listOf(EditedPiece("p1", "Раз.", "один"), EditedPiece("p2", "Два.", "два")), s.pieces())
    }

    @Test
    fun movedLineKeepsItsId() {
        val s = loaded("А" to "", "Б" to "", "В" to "")
        s.type("Б\nВ", "Б\nВ\nА")
        assertEquals(listOf("p2", "p3", "p1"), s.pieces().map { it.id })
    }

    @Test
    fun arabicWithMarksRoundTripsByteForByte() {
        val ayat = listOf("بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ۝١", "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ ۝٢")
        val s = loaded(ayat[0] to "", ayat[1] to "", language = "ar")
        fun check() {
            assertEquals(listOf("p1", "p2"), s.pieces().map { it.id })
            s.pieces().zip(ayat).forEach { (p, a) -> assertContentEquals(a.toByteArray(), p.content.toByteArray()) }
        }
        check()
        // re-aligned through the whole-text field: a blank line in between, then the text as it was
        s.type(ayat.joinToString("\n\n"), ayat.joinToString("\n"))
        check()
    }

    @Test
    fun pastedMarkupBecomesPlainTextWithReadings() {
        val s = loaded(language = "ja")
        s.editContent("{日本|にほん}の{春|はる}")
        assertEquals("日本の春", s.content)
        assertEquals(listOf("日本の春"), s.pieces().map { it.content })
        assertEquals(mapOf("日本" to ReadingOverride("にほん"), "春" to ReadingOverride("はる")), s.readings)
        assertTrue(s.changed)
        // a later paste merges with what is there
        s.editContent("日本の春{夏|なつ|か}")
        assertEquals("日本の春夏", s.content)
        assertEquals(setOf("日本", "春", "夏"), s.readings.keys)
        assertEquals(ReadingOverride("なつ", listOf("か")), s.readings["夏"])
    }

    @Test
    fun loadKeepsReadings() {
        val r = mapOf("春" to ReadingOverride("はる", listOf("ハル")))
        val s = TextEditorState()
        s.load(
            TextEntity(id = "t", title = "x", language = "ja", readings = PieceWords.encodeReadings(r)),
            listOf(TextPieceEntity(id = "p1", textId = "t", pieceIndex = 0, content = "春")),
        )
        assertEquals(r, s.readings)
    }
}
