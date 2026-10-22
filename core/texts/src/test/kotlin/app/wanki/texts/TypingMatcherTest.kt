package app.wanki.texts

import kotlin.system.measureNanoTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypingMatcherTest {
    private fun words(vararg w: String) = w.map { TypingUnit(it) }
    private val abc = words("abc", "def", "ghi")

    private fun TypingResult.states() = units.map { it.state }

    @Test
    fun exactTypingIsAllOk() {
        val r = TypingMatcher(abc).update("abc def ghi")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(0, r.mistakes)
        assertEquals(3, r.correct)
        assertTrue(r.finished)
    }

    @Test
    fun wrongCharMakesWordWrong() {
        val r = TypingMatcher(abc).update("abc dxf ghi")
        assertEquals(listOf(UnitState.OK, UnitState.WRONG, UnitState.OK), r.states())
        assertTrue(5 in r.units[1].typed!!)
        assertEquals(1, r.mistakes)
        assertEquals(2, r.correct)
    }

    @Test
    fun skippedWordIsSkipped() {
        val r = TypingMatcher(abc).update("abc ghi")
        assertEquals(listOf(UnitState.OK, UnitState.SKIPPED, UnitState.OK), r.states())
        assertNull(r.units[1].typed)
        assertEquals(1, r.mistakes)
    }

    // The word after the skipped run must be long: substituting a short word (3/char) beats skipping (2/char).
    @Test
    fun runOfSkippedWordsCountsOnce() {
        val r = TypingMatcher(words("abc", "def", "ghi", "jkl", "mnopqrstuv")).update("abc mnopqrstuv")
        assertEquals(
            listOf(UnitState.OK, UnitState.SKIPPED, UnitState.SKIPPED, UnitState.SKIPPED, UnitState.OK),
            r.states(),
        )
        assertEquals(1, r.mistakes)
    }

    @Test
    fun extraWordIsExtra() {
        val r = TypingMatcher(abc).update("abc xyz def ghi")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(4..6, r.units[1].extraBefore)
        assertEquals(1, r.mistakes)
    }

    @Test
    fun otherSpellingCounts() {
        val units = listOf(TypingUnit("すべて", listOf("全て")), TypingUnit("の"))
        val r = TypingMatcher(units).update("全ての")
        assertEquals(listOf(UnitState.OK, UnitState.OK), r.states())
        assertEquals(0, r.mistakes)
        assertEquals(0..1, r.units[0].typed)
    }

    @Test
    fun otherSpellingNotRedWhileIncomplete() {
        val units = listOf(TypingUnit("すべて", listOf("全て")), TypingUnit("の"))
        val r = TypingMatcher(units).update("全")
        assertEquals(listOf(UnitState.PENDING, UnitState.UNTYPED), r.states())
        assertEquals(0, r.mistakes)
    }

    @Test
    fun wordUnderCaretIsPending() {
        val r = TypingMatcher(listOf(TypingUnit("すべて"), TypingUnit("の"))).update("すべ")
        assertEquals(listOf(UnitState.PENDING, UnitState.UNTYPED), r.states())
    }

    @Test
    fun punctuationOnlyUnitsAreSkippedSilently() {
        val units = listOf(TypingUnit("abc"), TypingUnit(""), TypingUnit("def"))
        val r = TypingMatcher(units).update("abcdef")
        assertEquals(List(3) { UnitState.OK }, r.states())
        assertEquals(0, r.mistakes)
        assertEquals(2, r.correct)
        val empty = TypingMatcher(emptyList()).update("")
        assertTrue(empty.finished)
        assertEquals(0, empty.total)
    }

    @Test
    fun backspaceRecomputes() {
        val m = TypingMatcher(listOf(TypingUnit("すべて")))
        assertNotNull(m.update("すべてx").trailingExtra)
        val r = m.update("すべて")
        assertNull(r.trailingExtra)
        assertEquals(UnitState.OK, r.units[0].state)
    }

    @Test
    fun pasteInTheMiddleRecomputes() {
        val m = TypingMatcher(abc)
        m.update("abc")
        assertEquals(TypingMatcher(abc).update("xbc"), m.update("xbc"))
    }

    @Test
    fun speed3000() {
        val rnd = Random(7)
        val kana = "あいうえおかきくけこさしすせそたちつてと"
        val units = List(600) { TypingUnit(String(CharArray(5) { kana[rnd.nextInt(kana.length)] })) }
        val text = units.joinToString("") { it.reading }
        val m = TypingMatcher(units)
        val times = LongArray(text.length)
        for (i in text.indices) {
            val typed = text.substring(0, i + 1)
            times[i] = measureNanoTime { m.update(typed) }
        }
        val meanMs = times.takeLast(1000).average() / 1e6
        assertTrue(meanMs < 5.0, "mean update $meanMs ms")
        val r = m.update(text)
        assertEquals(TypingMatcher(units).update(text), r)
        assertTrue(r.finished)
        assertEquals(600, r.correct)
    }

    @Test
    fun pasteLaterInTheTextReusesRows() {
        val u = words("abcdef", "ghijkl")
        val m = TypingMatcher(u)
        m.update("abcdefghi")
        assertEquals(TypingMatcher(u).update("abcxefghi"), m.update("abcxefghi"))
    }

    @Test
    fun extraCharInsideWordMakesItWrong() {
        val r = TypingMatcher(abc).update("abc dxef ghi")
        assertEquals(UnitState.WRONG, r.units[1].state)
        assertEquals(4..7, r.units[1].typed)
    }

    @Test
    fun rangesSkipIgnoredCharsAndSurrogates() {
        val r = TypingMatcher(listOf(TypingUnit("a\uD83D\uDE00b"), TypingUnit("c"))).update("a, b c")
        assertEquals(UnitState.OK, r.units[1].state)
        assertEquals(5..5, r.units[1].typed)
    }

    @Test
    fun hugeRunCompletes() {
        val rnd = Random(3)
        val kana = "あいうえおかきくけこさしすせそ"
        val units = List(1200) { TypingUnit(String(CharArray(5) { kana[rnd.nextInt(kana.length)] })) }
        val text = units.joinToString("") { it.reading }
        val r = TypingMatcher(units).update(text)
        assertTrue(r.finished)
        assertEquals(1200, r.correct)
        assertEquals(6000, r.total)
    }
}
