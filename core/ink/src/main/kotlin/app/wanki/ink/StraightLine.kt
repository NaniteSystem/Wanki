package app.wanki.ink

import kotlin.math.ceil
import kotlin.math.hypot

/** Holding the pen still at the end of a stroke straightens it into a line from the stroke's start to the pen. */
object StraightLine {
    const val HOLD_MS = 500L

    /** Distance between the points of a generated line, world units. */
    const val STEP = 8f

    /**
     * True when the stroke is at least [minLength] long and the pen has stayed within [slop] of its last point
     * for [HOLD_MS] by [nowMs] (same clock as the points' times). A still pen sends no points, so the stillness
     * counts from the first point of the trailing run near the last one.
     */
    fun isHeld(points: List<InkPoint>, nowMs: Long, slop: Float, minLength: Float): Boolean {
        val last = points.lastOrNull() ?: return false
        var length = 0f
        for (i in 1 until points.size) length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        if (length < minLength) return false
        var start = points.lastIndex
        while (start > 0 && hypot(points[start - 1].x - last.x, points[start - 1].y - last.y) <= slop) start--
        return nowMs - points[start].timeMs >= HOLD_MS
    }

    /** The straight replacement of [stroke], ending at [end]: a point every [STEP] units, time interpolated, mean pressure. */
    fun line(stroke: InkStroke, end: InkPoint): InkStroke {
        val first = stroke.points.first()
        val pressures = stroke.points.map { it.pressure }.filter { !it.isNaN() }
        val pressure = if (pressures.isEmpty()) Float.NaN else pressures.average().toFloat()
        val n = maxOf(1, ceil(hypot(end.x - first.x, end.y - first.y) / STEP).toInt())
        val endTime = maxOf(end.timeMs, first.timeMs)
        val pts = List(n + 1) { i ->
            val f = i.toFloat() / n
            InkPoint(
                first.x + (end.x - first.x) * f, first.y + (end.y - first.y) * f,
                first.timeMs + ((endTime - first.timeMs) * f).toLong(), pressure,
            )
        }
        return InkStroke(pts, stroke.tool, stroke.brush)
    }
}
