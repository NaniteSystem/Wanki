package app.wanki.ui.texts

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.wanki.data.TextPieceEntity
import app.wanki.data.WordKey
import app.wanki.texts.PieceWord
import app.wanki.texts.SampleLevel
import app.wanki.texts.UnitState
import app.wanki.texts.WordLook
import app.wanki.texts.WordOutcome
import app.wanki.texts.WordStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypingSessionTest {
    private val p1 = TextPieceEntity(id = "p1", textId = "t", pieceIndex = 0, content = "Мороз и солнце;")
    private val p2 = TextPieceEntity(id = "p2", textId = "t", pieceIndex = 1, content = "день чудесный!")

    /** Words split on letters, each typed as written. */
    private fun words(p: TextPieceEntity): List<PieceWord> =
        Regex("\\p{L}+").findAll(p.content).map { PieceWord(it.range.first, it.range.last + 1, it.value, emptyList()) }.toList()

    private fun session(level: SampleLevel, stats: Map<WordKey, WordStat> = emptyMap(), pieces: List<TextPieceEntity> = listOf(p1, p2)) =
        TypingSession(pieces, pieces.associate { it.id to words(it) }, stats, level)

    private fun TypingSession.type(s: String) = onValue(TextFieldValue(s, TextRange(s.length)))

    @Test fun compositionIsNotJudged() {
        val p = TextPieceEntity(id = "j", textId = "t", pieceIndex = 0, content = "すべてない")
        val s = TypingSession(listOf(p), mapOf("j" to listOf(PieceWord(0, 3, "すべて", emptyList()), PieceWord(3, 5, "ない", emptyList()))), emptyMap(), SampleLevel.MEMORY)
        val out = s.onValue(TextFieldValue("すべてn", TextRange(4), composition = TextRange(3, 4)))
        assertEquals("すべて", s.committed)
        assertEquals("n", s.composing)
        assertEquals(TextRange(3, 4), out.composition)
        assertEquals(UnitState.OK, s.result.units[0].state)
        assertEquals(3, s.result.end)
        assertEquals(UnitState.UNTYPED, s.result.units[1].state)
    }

    @Test fun caretStaysAtEnd() {
        val s = session(SampleLevel.MEMORY)
        val out = s.onValue(TextFieldValue("Мор", TextRange(0)))
        assertEquals(TextRange(3), out.selection)
        assertEquals("Мор", out.text)
    }

    @Test fun sampleToggleCountsHint() {
        val s = session(SampleLevel.MEMORY)
        s.type("Мороз и солнце ")
        s.showSample()
        assertEquals(WordLook.SHOWN, s.look("p2", 1))
        s.hideSample()
        assertEquals(WordLook.NONE, s.look("p2", 1))
        val run = s.finish()
        assertEquals(setOf("p2"), run.hintedPieces)
        assertEquals(0, run.pieces.getValue("p1").hints)
        assertTrue(run.pieces.getValue("p2").hints > 0)
    }

    @Test fun memoryCueCountsHint() {
        val stats = mapOf(WordKey("p1", 1) to WordStat(cue = 1), WordKey("p2", 1) to WordStat(cue = 2))
        val s = session(SampleLevel.MEMORY, stats)
        s.type("Моро")
        assertEquals(emptySet(), s.finish().hintedPieces)
        s.type("Мороз ")
        assertEquals(WordLook.CUE_FIRST, s.look("p1", 1))
        assertEquals(WordLook.NONE, s.look("p2", 1)) // not at the caret yet
        val run = s.finish()
        assertEquals(setOf("p1"), run.hintedPieces)
        assertEquals(1, run.pieces.getValue("p1").hints)
        // a hint stays even after the caret moves on
        s.type("Мороз и солнце")
        assertEquals(setOf("p1"), s.finish().hintedPieces)
    }

    @Test fun finishMarksRestSkipped() {
        val s = session(SampleLevel.MEMORY)
        s.type("Мороз и солнце")
        assertFalse(s.result.finished)
        val run = s.finish()
        assertEquals(WordOutcome.CORRECT, run.words[WordKey("p1", 2)])
        assertEquals(WordOutcome.SKIPPED, run.words[WordKey("p2", 0)])
        assertEquals(WordOutcome.SKIPPED, run.words[WordKey("p2", 1)])
        assertEquals(5, run.words.size)
        assertEquals(3, run.pieces.getValue("p1").correct)
        assertEquals(0, run.pieces.getValue("p1").errors)
        assertEquals(0, run.pieces.getValue("p2").correct)
        assertEquals(2, run.pieces.getValue("p2").words)
        // consecutive skipped words are one error
        assertEquals(1, run.pieces.getValue("p2").errors)
        assertEquals(setOf("p2"), run.errorPieces)
    }

    @Test fun halfTypedWordIsWrongOnFinish() {
        val s = session(SampleLevel.MEMORY)
        s.type("Мороз и солн")
        assertEquals(UnitState.PENDING, s.result.units[2].state)
        assertEquals(WordOutcome.WRONG, s.finish().words[WordKey("p1", 2)])
    }

    @Test fun typoIsCountedPerWord() {
        val s = session(SampleLevel.MEMORY)
        s.type("Мороз и сонце день чудесный")
        assertTrue(s.result.finished)
        val run = s.finish()
        assertEquals(WordOutcome.WRONG, run.words[WordKey("p1", 2)])
        assertEquals(1, run.pieces.getValue("p1").errors)
        assertEquals(2, run.pieces.getValue("p1").correct)
        assertEquals(setOf("p1"), run.errorPieces)
    }

    @Test fun sampleLevelSetsWithSample() {
        val s = session(SampleLevel.SAMPLE)
        s.type("Мороз и солнце день чудесный")
        assertTrue(s.finish().pieces.values.all { it.withSample })

        val m = session(SampleLevel.MEMORY)
        m.type("Мороз и солнце")
        m.level = SampleLevel.SAMPLE
        val run = m.finish()
        assertFalse(run.pieces.getValue("p1").withSample) // finished before the switch
        assertTrue(run.pieces.getValue("p2").withSample)
    }

    @Test fun typingToTheEndEndsTheRun() {
        var now = 1_000L
        val s = TypingSession(listOf(p1), mapOf("p1" to words(p1)), emptyMap(), SampleLevel.MEMORY) { now }
        s.type("Мороз и")
        now += 5_000
        s.type("Мороз и солнце")
        assertTrue(s.ended)
        now += 60_000
        assertEquals(5_000, s.elapsedMs)
        // input after the end changes nothing
        s.type("Мороз и солнце xx")
        assertEquals("Мороз и солнце", s.committed)
    }

    @Test fun snapshotResumesTheSameRun() {
        val s = session(SampleLevel.MEMORY, mapOf(WordKey("p1", 1) to WordStat(cue = 2)))
        s.type("Мороз и солн")
        s.showSample()
        val snap = s.snapshot()
        val again = session(SampleLevel.SAMPLE, mapOf(WordKey("p1", 1) to WordStat(cue = 2)))
        again.resume(snap)
        assertEquals("Мороз и солн", again.committed)
        assertEquals(SampleLevel.MEMORY, again.level)
        assertTrue(again.sampleShown)
        assertEquals(s.finish(), again.finish())
    }

    @Test fun endJudgesTheComposition() {
        val s = session(SampleLevel.MEMORY)
        s.onValue(TextFieldValue("Мороз и солнце", TextRange(14), composition = TextRange(8, 14)))
        assertEquals("Мороз и солнце", s.snapshot().typed)
        s.end()
        assertEquals("Мороз и солнце", s.committed)
        assertEquals(WordOutcome.CORRECT, s.finish().words[WordKey("p1", 2)])
    }

    @Test fun sampleBeforeTypingDoesNotCount() {
        val s = session(SampleLevel.SAMPLE)
        s.level = SampleLevel.MEMORY
        s.type("Мороз и солнце день чудесный")
        val run = s.finish()
        assertTrue(run.pieces.values.none { it.withSample })
        assertTrue(run.pieces.values.all { app.wanki.texts.TypingScore.clean(it) })
    }

    @Test fun sampleWhileTypingCounts() {
        val s = session(SampleLevel.SAMPLE)
        s.type("Мор")
        s.level = SampleLevel.MEMORY
        s.type("Мороз и солнце день чудесный")
        val run = s.finish()
        assertTrue(run.pieces.getValue("p1").withSample)
        assertTrue(run.pieces.getValue("p2").withSample) // it was ahead while the sample was on
    }

    @Test fun sampleRunIsARecitalError() {
        val m = session(SampleLevel.MEMORY)
        m.type("Мороз и солнце")
        m.level = SampleLevel.SAMPLE
        m.type("Мороз и солнце день чудесный")
        assertEquals(setOf("p2"), m.finish().recitalErrors())
    }

    @Test fun draftLevelKeepsHints() {
        val stats = mapOf(WordKey("p1", 1) to WordStat(cue = 2))
        val s = session(SampleLevel.MEMORY, stats)
        s.type("Мороз и солнце")
        s.showSample()
        val code = encodeDraftLevel(s.snapshot())
        val back = decodeDraftLevel(code, typed = "Мороз и солнце", elapsedMs = 0)
        val again = session(SampleLevel.MEMORY)
        again.resume(back)
        assertEquals(s.finish(), again.finish())
        assertTrue(again.sampleShown)
        // an old draft holds only the level name
        assertEquals(SampleLevel.STRICT, decodeDraftLevel("STRICT", "x", 0).level)
        assertEquals(emptySet(), decodeDraftLevel("STRICT", "x", 0).hinted)
    }

    @Test fun compositionFinishesLastWord() {
        val s = session(SampleLevel.MEMORY, pieces = listOf(p1))
        s.onValue(TextFieldValue("Мороз и солнце", TextRange(14), composition = TextRange(8, 14)))
        assertTrue(s.ended)
        assertEquals(WordOutcome.CORRECT, s.finish().words[WordKey("p1", 2)])
    }

    @Test fun compositionWithWrongLastWordWaits() {
        val s = session(SampleLevel.MEMORY, pieces = listOf(p1))
        s.onValue(TextFieldValue("Мороз и солнцу", TextRange(14), composition = TextRange(8, 14)))
        assertFalse(s.ended)
    }

    /** While the keyboard composes a word, the matcher only runs when the composition could finish the text. */
    @Test fun compositionDoesNotRerunTheMatcher() {
        val s = session(SampleLevel.MEMORY)
        fun compose(committed: String, word: String) {
            val text = committed + word
            s.onValue(TextFieldValue(text, TextRange(text.length), composition = TextRange(committed.length, text.length)))
        }
        compose("", "")
        s.type("Мороз и ")
        val base = s.matcherUpdates
        for (i in 1.."солнце".length) compose("Мороз и ", "солнце".take(i))
        assertEquals(base, s.matcherUpdates, "no matcher run per composed key")
        s.type("Мороз и солнце день ")
        val committed = s.matcherUpdates
        for (i in 1 until "чудесный".length) compose("Мороз и солнце день ", "чудесный".take(i))
        assertEquals(committed, s.matcherUpdates, "too short to reach the end yet")
        assertFalse(s.ended)
        compose("Мороз и солнце день ", "чудесный")
        assertTrue(s.ended)
        assertEquals(WordOutcome.CORRECT, s.finish().words[WordKey("p2", 1)])
    }

    @Test fun romajiTailDoesNotFinish() {
        val p = TextPieceEntity(id = "j", textId = "t", pieceIndex = 0, content = "すべてない")
        fun ja() = TypingSession(listOf(p), mapOf("j" to listOf(PieceWord(0, 3, "すべて", emptyList()), PieceWord(3, 5, "ない", emptyList()))), emptyMap(), SampleLevel.MEMORY)
        val s = ja()
        s.onValue(TextFieldValue("すべてなn", TextRange(5), composition = TextRange(4, 5)))
        assertFalse(s.ended)
        assertEquals("すべてな", s.committed)
        val k = ja()
        k.onValue(TextFieldValue("すべてない", TextRange(5), composition = TextRange(4, 5)))
        assertTrue(k.ended)
        assertEquals(WordOutcome.CORRECT, k.finish().words[WordKey("j", 1)])
    }

    @Test fun defaultLevels() {
        assertEquals(SampleLevel.SAMPLE, defaultLevel(StudyKind.LEARN))
        StudyKind.entries.filter { it != StudyKind.LEARN }.forEach { assertEquals(SampleLevel.MEMORY, defaultLevel(it)) }
    }

    @Test fun draftKeys() {
        assertEquals("REVIEW:p2", draftKey(StudyRequest(StudyKind.REVIEW, "t", listOf("p1", "p2")), "p2"))
        assertEquals("PRACTICE:p1,p2", draftKey(StudyRequest(StudyKind.PRACTICE, "t", listOf("p1", "p2")), null))
    }
}
