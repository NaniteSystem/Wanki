package app.wanki.texts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordHistoryTest {
    private fun run(start: WordStat, vararg o: WordOutcome) = o.fold(start) { s, x -> WordHistory.after(s, x) }

    @Test fun errorRaisesCueAndResetsStreak() {
        val one = WordHistory.after(WordStat(cleanStreak = 2), WordOutcome.WRONG)
        assertEquals(1, one.cue)
        assertEquals(0, one.cleanStreak)
        assertEquals(1, one.recent)
        val two = WordHistory.after(one, WordOutcome.WRONG)
        assertEquals(2, two.cue)
        assertEquals(2, WordHistory.after(two, WordOutcome.WRONG).cue)
    }

    @Test fun threeCleanLowerCueOneStep() {
        val c = WordOutcome.CORRECT
        val a = run(WordStat(cue = 2), c, c, c)
        assertEquals(1, a.cue)
        assertEquals(0, run(a, c, c, c).cue)
    }

    @Test fun recentKeepsThree() {
        val s = run(WordStat(), WordOutcome.WRONG, WordOutcome.CORRECT, WordOutcome.CORRECT, WordOutcome.CORRECT)
        assertEquals(0, s.recent)
        val t = run(WordStat(), WordOutcome.WRONG, WordOutcome.CORRECT, WordOutcome.WRONG)
        assertEquals(0b101, t.recent)
    }

    @Test fun sampleHidesAfterThreeClean() {
        assertEquals(WordLook.HIDDEN, WordHistory.look(SampleLevel.SAMPLE, WordStat(cleanStreak = 3), false, false))
    }

    @Test fun sampleHighlightsTwoOfThreeErrors() {
        assertEquals(WordLook.HIGHLIGHTED, WordHistory.look(SampleLevel.SAMPLE, WordStat(recent = 0b101), false, false))
        assertEquals(WordLook.SHOWN, WordHistory.look(SampleLevel.SAMPLE, WordStat(recent = 0b001), false, false))
        assertEquals(WordLook.SHOWN, WordHistory.look(SampleLevel.SAMPLE, null, false, false))
    }

    @Test fun memoryCueOnlyAtCaret() {
        assertEquals(WordLook.NONE, WordHistory.look(SampleLevel.MEMORY, WordStat(cue = 1), false, false))
        assertEquals(WordLook.CUE_FIRST, WordHistory.look(SampleLevel.MEMORY, WordStat(cue = 1), true, false))
        assertEquals(WordLook.CUE_WORD, WordHistory.look(SampleLevel.MEMORY, WordStat(cue = 2), true, false))
        assertEquals(WordLook.NONE, WordHistory.look(SampleLevel.MEMORY, null, true, false))
    }

    @Test fun strictMarksAfterPassing() {
        assertEquals(WordLook.MARKED, WordHistory.look(SampleLevel.STRICT, WordStat(recent = 1), false, true))
        assertEquals(WordLook.NONE, WordHistory.look(SampleLevel.STRICT, WordStat(recent = 1), false, false))
    }

    @Test fun hintLooks() {
        assertTrue(WordHistory.countsAsHint(WordLook.CUE_FIRST))
        assertTrue(WordHistory.countsAsHint(WordLook.CUE_WORD))
        assertFalse(WordHistory.countsAsHint(WordLook.HIGHLIGHTED))
    }
}
