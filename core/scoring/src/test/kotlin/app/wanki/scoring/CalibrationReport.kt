package app.wanki.scoring

import kotlin.test.Test

/** Not an assertion test: prints a score table for typical mistakes, to tune weights and tolerances. */
class CalibrationReport {
    private val scorer = HandwritingScorer()

    @Test
    fun report() {
        val chars = listOf("右", "日", "本", "永", "語", "書", "王", "あ")
        val wrong = mapOf("右" to "左", "日" to "口", "本" to "木", "王" to "玉", "土" to "士", "末" to "未")
        println("char | case                          | score | shape  pos  count order dir | beauty")
        for (c in chars + listOf("土", "末")) {
            val ref = Fixtures.kanji(c)
            val p = Fixtures.perfect(ref)
            val cases = buildList {
                add("perfect" to p)
                add("wobble 3" to Simulate.wobble(p, 3.0, 1))
                add("wobble 6" to Simulate.wobble(p, 6.0, 1))
                add("misplace 4/.15" to Simulate.misplace(p, 4.0, 0.15, 2))
                add("misplace 8/.3" to Simulate.misplace(p, 8.0, 0.3, 2))
                add("shake .8" to Simulate.shake(p, 0.8, 3))
                if (p.size > 1) add("swap 0,1" to Simulate.swap(p, 0, 1))
                add("reverse 0" to Simulate.reverse(p, 0))
                if (p.size > 1) add("drop last" to Simulate.drop(p, p.lastIndex))
                wrong[c]?.let { add("wrote $it" to Fixtures.perfect(Fixtures.kanji(it))) }
            }
            for ((name, strokes) in cases) {
                val r = scorer.score(ref, strokes)
                val m = r.metrics
                println(
                    "%s    | %-29s | %5.1f | %.2f  %.2f  %.2f  %.2f  %.2f | %s".format(
                        c, name, r.score, m.shape, m.position, m.count, m.order, m.direction,
                        r.beauty?.let { "%.1f (sh %.2f pos %.2f prop %.2f sm %.2f)".format(it.score, it.shape, it.position, it.proportions, it.smoothness) } ?: "-",
                    ),
                )
            }
            println()
        }
    }
}
