package app.wanki.scoring

import app.wanki.scoring.geom.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandwritingScorerTest {
    private val scorer = HandwritingScorer()
    private val ref = Fixtures.kanji("右")
    private val perfect = Fixtures.perfect(ref)

    @Test
    fun `perfect attempt scores ~100 regardless of canvas scale and offset`() {
        val r = scorer.score(ref, Simulate.transform(perfect, 3.7, 250.0, -40.0))
        assertTrue(r.score > 99, "score=${r.score}")
        assertTrue(r.passed)
        assertTrue(r.strokes.all { it.status == StrokeStatus.CORRECT })
        assertTrue(r.missingRefStrokes.isEmpty())
        assertTrue(r.beauty!!.score > 97, "beauty=${r.beauty}")
    }

    @Test
    fun `sloppy but correct writing passes`() {
        val sloppy = Simulate.misplace(Simulate.wobble(perfect, 4.0, 1), shift = 5.0, scaleJitter = 0.15, seed = 2)
        val r = scorer.score(ref, sloppy)
        assertTrue(r.passed, "score=${r.score} ${r.metrics}")
        assertTrue(r.score in 70.0..97.0, "score=${r.score}")
        assertTrue(r.beauty!!.score < 90, "beauty=${r.beauty}")
    }

    @Test
    fun `reversed stroke is yellow and costs direction`() {
        val r = scorer.score(ref, Simulate.reverse(perfect, 1))
        val s = r.strokes[1]
        assertEquals(1, s.refIndex)
        assertTrue(s.reversed)
        assertEquals(StrokeStatus.ORDER_OR_DIRECTION, s.status)
        assertEquals(0.8, r.metrics.direction, 1e-9)
        assertTrue(r.passed)
    }

    @Test
    fun `swapped strokes are matched to their reference and flagged`() {
        // 右: writing the horizontal (stroke 2) before the left-falling stroke (stroke 1) is the classic mistake
        val r = scorer.score(ref, Simulate.swap(perfect, 0, 1))
        assertEquals(listOf(1, 0, 2, 3, 4), r.strokes.map { it.refIndex })
        assertEquals(1, r.strokes.count { it.wrongOrder })
        assertEquals(0.8, r.metrics.order, 1e-9)
        assertTrue(r.passed)
    }

    @Test
    fun `order strictness 0 ignores order`() {
        val lax = HandwritingScorer(ScoringConfig(orderStrictness = 0.0))
        val r = lax.score(ref, Simulate.swap(perfect, 0, 1))
        assertTrue(r.score > 99, "score=${r.score}")
        assertTrue(r.strokes.none { it.wrongOrder })
    }

    @Test
    fun `missing and extra strokes are reported`() {
        val extra = listOf(Point(5.0, 100.0), Point(30.0, 104.0))
        val r = scorer.score(ref, Simulate.add(Simulate.drop(perfect, 4), extra))
        assertEquals(listOf(4), r.missingRefStrokes)
        assertEquals(StrokeStatus.EXTRA, r.strokes.last().status)
        assertNull(r.strokes.last().refIndex)
        assertEquals(setOf(FailReason.MISSING_STROKES, FailReason.EXTRA_STROKES), r.failReasons)
        assertEquals(59.0, r.score, 1e-9)
    }

    @Test
    fun `one missing stroke fails even when everything else is perfect`() {
        for (c in listOf("日", "本", "書", "語")) {
            val t = Fixtures.kanji(c)
            val r = scorer.score(t, Simulate.drop(Fixtures.perfect(t), t.strokes.lastIndex))
            assertFalse(r.passed, "$c: ${r.score}")
            assertTrue(FailReason.MISSING_STROKES in r.failReasons)
            assertTrue(r.rawScore > 74, "raw still reflects the rest: ${r.rawScore}")
        }
    }

    @Test
    fun `stroke-count look-alikes fail without any recognizer`() {
        // 木 for 本 (one missing), 玉 for 王 (one extra)
        val hon = scorer.score(Fixtures.kanji("本"), Fixtures.perfect(Fixtures.kanji("木")))
        assertEquals(setOf(FailReason.MISSING_STROKES), hon.failReasons)
        val ou = scorer.score(Fixtures.kanji("王"), Fixtures.perfect(Fixtures.kanji("玉")))
        assertEquals(setOf(FailReason.EXTRA_STROKES), ou.failReasons)
    }

    @Test
    fun `stroke count rules can be relaxed`() {
        val lax = HandwritingScorer(ScoringConfig(failOnMissingStroke = false, failOnExtraStroke = false))
        val r = lax.score(ref, Simulate.drop(perfect, 4))
        assertTrue(r.passed, "score=${r.score}")
    }

    @Test
    fun `half the strokes is a fail`() {
        val r = scorer.score(ref, Simulate.drop(perfect, 2, 3, 4))
        assertFalse(r.passed, "score=${r.score}")
        assertNull(r.beauty)
    }

    @Test
    fun `recognition gate caps the score below the threshold`() {
        val r = scorer.score(ref, perfect, recognitionCandidates = listOf("石", "古", "若", "右"))
        assertEquals(RecognitionOutcome.NOT_RECOGNIZED, r.recognition)
        assertEquals(59.0, r.score, 1e-9)
        assertTrue(r.rawScore > 99)
        assertEquals(setOf(FailReason.NOT_RECOGNIZED), r.failReasons)
        assertFalse(r.passed)

        val ok = scorer.score(ref, perfect, recognitionCandidates = listOf("石", "右"))
        assertEquals(RecognitionOutcome.IN_TOP_K, ok.recognition)
        assertTrue(ok.passed)
    }

    @Test
    fun `look-alike character is rejected by the competitor check`() {
        // same stroke count, so only geometry can tell them apart
        for ((target, written) in listOf("右" to "左", "土" to "士", "末" to "未")) {
            val t = Fixtures.kanji(target)
            val w = Fixtures.kanji(written)
            val attempt = Simulate.wobble(Fixtures.perfect(w), 3.0, 5)
            val alone = scorer.score(t, attempt)
            assertTrue(alone.passed, "geometry alone is expected to accept $written for $target (${alone.score})")
            val r = scorer.score(t, attempt, competitors = listOf(w))
            assertFalse(r.passed, "$written for $target: ${r.score}")
            assertEquals(written, r.confusedWith)
            assertEquals(setOf(FailReason.CONFUSED_WITH_LOOKALIKE), r.failReasons)
        }
    }

    @Test
    fun `competitor check does not reject sloppy correct writing`() {
        for ((target, other) in listOf("右" to "左", "土" to "士", "末" to "未", "王" to "玉", "本" to "木")) {
            val t = Fixtures.kanji(target)
            for (seed in 1..8) {
                val sloppy = Simulate.misplace(Simulate.wobble(Fixtures.perfect(t), 4.0, seed), 3.0, 0.1, seed)
                val r = scorer.score(t, sloppy, competitors = listOf(Fixtures.kanji(other)))
                assertTrue(r.passed && r.confusedWith == null, "$target seed $seed: ${r.score} confused=${r.confusedWith}")
            }
        }
    }

    @Test
    fun `empty attempt`() {
        val r = scorer.score(ref, emptyList())
        assertEquals(0.0, r.score)
        assertEquals(ref.strokes.indices.toList(), r.missingRefStrokes)
    }

    @Test
    fun `shaky hand lowers handwriting score but not recall`() {
        val clean = scorer.score(ref, perfect)
        val shaky = scorer.score(ref, Simulate.shake(perfect, 0.8, 3))
        assertTrue(shaky.passed)
        assertTrue(shaky.beauty!!.smoothness < clean.beauty!!.smoothness - 0.3, "${shaky.beauty} vs ${clean.beauty}")
        assertTrue(clean.score - shaky.score < 10, "${clean.score} vs ${shaky.score}")
    }

    @Test
    fun `beauty is tracked separately from the pass decision`() {
        val r = scorer.score(ref, Simulate.misplace(perfect, shift = 6.0, scaleJitter = 0.25, seed = 9))
        assertTrue(r.passed, "score=${r.score}")
        assertNotNull(r.beauty)
        assertTrue(r.needsHandwritingPractice, "beauty=${r.beauty}")
    }
}
