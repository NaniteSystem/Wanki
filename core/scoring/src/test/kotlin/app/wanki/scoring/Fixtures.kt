package app.wanki.scoring

import app.wanki.scoring.geom.Point
import app.wanki.scoring.geom.Polyline
import app.wanki.scoring.geom.resample
import app.wanki.scoring.kanjivg.KanjiVg
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object Fixtures {
    fun kanji(char: String): CharReference {
        val hex = "%05x".format(char.codePointAt(0))
        val text = Fixtures::class.java.getResource("/kanjivg/$hex.svg")?.readText()
            ?: error("No KanjiVG fixture for $char ($hex)")
        return KanjiVg.parse(text)
    }

    /** The reference strokes as a "perfect" attempt, densely sampled like a real pen. */
    fun perfect(ref: CharReference): List<Polyline> = ref.strokes.map { it.points.resample(40) }
}

/** Builders that turn a stroke list into a plausible imperfect attempt. */
object Simulate {
    fun transform(strokes: List<Polyline>, scale: Double, dx: Double, dy: Double) =
        strokes.map { s -> s.map { Point(it.x * scale + dx, it.y * scale + dy) } }

    /** Smooth, consistent distortion of the whole character — "sloppy but legible". */
    fun wobble(strokes: List<Polyline>, amplitude: Double, seed: Int): List<Polyline> {
        val r = Random(seed)
        val ph = DoubleArray(4) { r.nextDouble(2 * PI) }
        val f = DoubleArray(4) { 1.0 + r.nextDouble() * 1.5 }
        return strokes.map { s ->
            s.map {
                Point(
                    it.x + amplitude * sin(2 * PI * f[0] * it.y / 109 + ph[0]) + amplitude * 0.5 * sin(2 * PI * f[1] * it.x / 109 + ph[1]),
                    it.y + amplitude * sin(2 * PI * f[2] * it.x / 109 + ph[2]) + amplitude * 0.5 * sin(2 * PI * f[3] * it.y / 109 + ph[3]),
                )
            }
        }
    }

    /** Each stroke independently shifted and scaled — imprecise placement. */
    fun misplace(strokes: List<Polyline>, shift: Double, scaleJitter: Double, seed: Int): List<Polyline> {
        val r = Random(seed)
        return strokes.map { s ->
            val dx = (r.nextDouble() * 2 - 1) * shift
            val dy = (r.nextDouble() * 2 - 1) * shift
            val k = 1 + (r.nextDouble() * 2 - 1) * scaleJitter
            val cx = s.sumOf { it.x } / s.size
            val cy = s.sumOf { it.y } / s.size
            s.map { Point(cx + (it.x - cx) * k + dx, cy + (it.y - cy) * k + dy) }
        }
    }

    /** Per-point noise — a shaky hand. */
    fun shake(strokes: List<Polyline>, sigma: Double, seed: Int): List<Polyline> {
        val r = Random(seed)
        return strokes.map { s -> s.map { Point(it.x + r.nextGaussian() * sigma, it.y + r.nextGaussian() * sigma) } }
    }

    fun reverse(strokes: List<Polyline>, vararg idx: Int) = strokes.mapIndexed { i, s -> if (i in idx) s.reversed() else s }
    fun swap(strokes: List<Polyline>, a: Int, b: Int) = strokes.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
    fun drop(strokes: List<Polyline>, vararg idx: Int) = strokes.filterIndexed { i, _ -> i !in idx }
    fun add(strokes: List<Polyline>, extra: Polyline) = strokes + listOf(extra)

    private fun Random.nextGaussian(): Double {
        var u: Double
        do u = nextDouble() while (u == 0.0)
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * nextDouble())
    }
}
