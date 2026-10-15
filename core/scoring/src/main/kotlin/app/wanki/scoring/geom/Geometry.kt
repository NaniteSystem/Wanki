package app.wanki.scoring.geom

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Point(val x: Double, val y: Double) {
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    operator fun minus(o: Point) = Point(x - o.x, y - o.y)
    operator fun times(k: Double) = Point(x * k, y * k)
    fun dist(o: Point): Double = hypot(x - o.x, y - o.y)
}

typealias Polyline = List<Point>

data class BBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val maxSide get() = max(width, height)
    val center get() = Point((minX + maxX) / 2, (minY + maxY) / 2)

    companion object {
        fun of(points: Iterable<Point>): BBox {
            var x0 = Double.POSITIVE_INFINITY
            var y0 = Double.POSITIVE_INFINITY
            var x1 = Double.NEGATIVE_INFINITY
            var y1 = Double.NEGATIVE_INFINITY
            for (p in points) {
                x0 = min(x0, p.x); y0 = min(y0, p.y)
                x1 = max(x1, p.x); y1 = max(y1, p.y)
            }
            require(x0 <= x1) { "BBox of empty point set" }
            return BBox(x0, y0, x1, y1)
        }
    }
}

/** Uniform scale + translation: p' = p * scale + offset. */
data class Similarity(val scale: Double, val dx: Double, val dy: Double) {
    fun apply(p: Point) = Point(p.x * scale + dx, p.y * scale + dy)
    fun apply(line: Polyline): Polyline = line.map(::apply)
}

fun Polyline.length(): Double {
    var sum = 0.0
    for (i in 1 until size) sum += this[i - 1].dist(this[i])
    return sum
}

fun Polyline.centroid(): Point {
    var sx = 0.0
    var sy = 0.0
    for (p in this) { sx += p.x; sy += p.y }
    return Point(sx / size, sy / size)
}

/** Resamples to [n] points equally spaced along the arc. A zero-length stroke (a tap) becomes n copies. */
fun Polyline.resample(n: Int): Polyline {
    require(isNotEmpty()) { "Cannot resample an empty stroke" }
    require(n >= 2)
    val total = length()
    if (total < 1e-9) return List(n) { first() }
    val step = total / (n - 1)
    val out = ArrayList<Point>(n)
    out += first()
    var acc = 0.0
    var prev = first()
    var i = 1
    while (i < size && out.size < n - 1) {
        val cur = this[i]
        val d = prev.dist(cur)
        if (acc + d >= step && d > 0) {
            val t = (step - acc) / d
            val q = Point(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
            out += q
            prev = q
            acc = 0.0
        } else {
            acc += d
            prev = cur
            i++
        }
    }
    while (out.size < n) out += last()
    return out
}

fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
