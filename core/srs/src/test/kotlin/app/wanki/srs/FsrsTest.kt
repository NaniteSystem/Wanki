package app.wanki.srs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsTest {
    private val exact = FsrsScheduler(enableFuzzing = false)

    private data class Row(
        val case: Int, val rating: Int, val t: Long, val state: Int, val step: Int,
        val s: Double, val d: Double, val due: Long,
    )

    private fun reference(): List<List<Row>> =
        javaClass.getResource("/fsrs_reference.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l ->
                val p = l.split(" ")
                Row(p[0].toInt(), p[1].toInt(), p[2].toLong(), p[3].toInt(), p[4].toInt(), p[5].toDouble(), p[6].toDouble(), p[7].toLong())
            }
            .groupBy { it.case }.values.toList()

    @Test
    fun `matches py-fsrs on 200 random review histories`() {
        var checked = 0
        for (case in reference()) {
            var s = SrsState()
            for (row in case) {
                s = exact.review(s, Rating.entries[row.rating - 1], row.t)
                val ctx = "case ${row.case} after review at ${row.t}"
                assertEquals(CardState.entries[row.state - 1], s.state, ctx)
                assertEquals(row.step.takeIf { it >= 0 }, s.step, ctx)
                assertTrue(abs(s.stability!! - row.s) <= 1e-9 * maxOf(1.0, row.s), "$ctx: S ${s.stability} vs ${row.s}")
                assertTrue(abs(s.difficulty!! - row.d) <= 1e-9 * maxOf(1.0, row.d), "$ctx: D ${s.difficulty} vs ${row.d}")
                assertEquals(row.due, s.due, ctx)
                checked++
            }
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun `replaying the log reproduces the state, fuzz included`() {
        val fuzzy = FsrsScheduler()
        var s = SrsState()
        val log = ArrayList<Review>()
        var now = 1_767_254_400_000L
        repeat(12) { i ->
            val r = if (i % 5 == 4) Rating.AGAIN else Rating.GOOD
            s = fuzzy.review(s, r, now, cardSeed = 77)
            log += Review(r, now)
            now = s.due + 3_600_000
        }
        assertEquals(s, fuzzy.replay(log.shuffled(), cardSeed = 77))
    }

    @Test
    fun `new card goes through learning steps`() {
        val t = 0L
        val a = exact.review(SrsState(), Rating.GOOD, t)
        assertEquals(CardState.LEARNING, a.state)
        assertEquals(1, a.step)
        assertEquals(10 * FsrsScheduler.MINUTE, a.due - t)
        val b = exact.review(a, Rating.GOOD, a.due)
        assertEquals(CardState.REVIEW, b.state)
        assertTrue(b.due - a.due >= FsrsScheduler.DAY)
        val easy = exact.review(SrsState(), Rating.EASY, t)
        assertEquals(CardState.REVIEW, easy.state)
    }

    @Test
    fun `lapse moves a review card to relearning`() {
        var s = exact.review(SrsState(), Rating.EASY, 0)
        s = exact.review(s, Rating.AGAIN, s.due)
        assertEquals(CardState.RELEARNING, s.state)
        assertEquals(1, s.lapses)
    }

    @Test
    fun `preview orders intervals by rating`() {
        var s = exact.review(SrsState(), Rating.EASY, 0)
        s = exact.review(s, Rating.GOOD, s.due)
        val p = exact.preview(s, s.due)
        assertTrue(p[Rating.AGAIN]!! < p[Rating.HARD]!! && p[Rating.HARD]!! <= p[Rating.GOOD]!! && p[Rating.GOOD]!! <= p[Rating.EASY]!!, "$p")
    }
}
