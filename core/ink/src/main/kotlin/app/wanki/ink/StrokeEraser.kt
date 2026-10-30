package app.wanki.ink

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Partial erasing: the eraser is a capsule (the segment a–b swept by a disc of radius r), widened by half the stroke's
 * brush. [cut] removes the parts of a stroke inside it and returns what is left; a fast drag leaves no gaps because
 * the capsule spans the whole step.
 */
object StrokeEraser {
    /** True when the capsule a–b (radius [r]) reaches [s]: the stroke's line comes within [r] plus half its brush. */
    fun touches(s: InkStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean {
        val pts = s.points
        if (pts.isEmpty()) return false
        val rr = effective(s, r)
        val capsule = Capsule(ax, ay, bx, by, rr)
        val out = DoubleArray(2)
        for (i in pts.indices) {
            if (capsule.contains(pts[i].x, pts[i].y)) return true
            if (i + 1 < pts.size && capsule.clip(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, out)) return true
        }
        return false
    }

    /**
     * The stroke without the part under the capsule: null when nothing is touched, an empty list when all of it goes,
     * otherwise the pieces (same tool and brush; cut points have interpolated time and pressure). Pieces shorter than
     * `max(brush size, 2)` are dropped. The effective radius is `r + brush.size / 2`.
     */
    fun cut(s: InkStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<InkStroke>? {
        val pts = s.points
        if (pts.isEmpty()) return null
        val capsule = Capsule(ax, ay, bx, by, effective(s, r))
        val inside = BooleanArray(pts.size) { capsule.contains(pts[it].x, pts[it].y) }
        val out = DoubleArray(2)
        var touched = false
        val pieces = ArrayList<List<InkPoint>>()
        var cur = ArrayList<InkPoint>()
        if (!inside[0]) cur.add(pts[0])
        else touched = true
        for (i in 0 until pts.size - 1) {
            val p = pts[i]
            val q = pts[i + 1]
            val hit = capsule.clip(p.x, p.y, q.x, q.y, out)
            when {
                !inside[i] && !inside[i + 1] -> {
                    if (hit && out[1] > out[0]) {
                        // passes through the capsule between two outside points
                        touched = true
                        cur.add(lerp(p, q, out[0]))
                        pieces.add(cur)
                        cur = arrayListOf(lerp(p, q, out[1]))
                    }
                    cur.add(q)
                }
                !inside[i] && inside[i + 1] -> {
                    touched = true
                    if (hit) cur.add(lerp(p, q, out[0]))
                    pieces.add(cur)
                    cur = ArrayList()
                }
                inside[i] && !inside[i + 1] -> {
                    cur = ArrayList()
                    cur.add(if (hit) lerp(p, q, out[1]) else p)
                    cur.add(q)
                }
                // both inside: nothing of this segment is left
            }
        }
        if (!touched) return null
        pieces.add(cur)
        val minLen = max(s.brush.size, 2f)
        return pieces.filter { it.size >= 2 && length(it) >= minLen }.map { InkStroke(it, s.tool, s.brush) }
    }

    private fun effective(s: InkStroke, r: Float) = r + s.brush.size / 2

    private fun length(p: List<InkPoint>): Float {
        var l = 0.0
        for (i in 1 until p.size) {
            val dx = (p[i].x - p[i - 1].x).toDouble()
            val dy = (p[i].y - p[i - 1].y).toDouble()
            l += sqrt(dx * dx + dy * dy)
        }
        return l.toFloat()
    }

    private fun lerp(p: InkPoint, q: InkPoint, t: Double): InkPoint {
        val f = t.toFloat()
        fun mix(a: Float, b: Float) = a + (b - a) * f
        return InkPoint(
            mix(p.x, q.x), mix(p.y, q.y),
            p.timeMs + Math.round((q.timeMs - p.timeMs) * t),
            mix(p.pressure, q.pressure), mix(p.tiltRadians, q.tiltRadians), mix(p.orientationRadians, q.orientationRadians),
        )
    }

    /** The disc-swept segment a–b; a == b is a plain disc. */
    private class Capsule(ax: Float, ay: Float, bx: Float, by: Float, r: Float) {
        private val ax = ax.toDouble()
        private val ay = ay.toDouble()
        private val bx = bx.toDouble()
        private val by = by.toDouble()
        private val r = r.toDouble()
        private val dx = this.bx - this.ax
        private val dy = this.by - this.ay
        private val len2 = dx * dx + dy * dy
        private val len = sqrt(len2)

        fun contains(x: Float, y: Float): Boolean {
            val px = x - ax
            val py = y - ay
            val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
            val ex = px - t * dx
            val ey = py - t * dy
            return ex * ex + ey * ey <= r * r
        }

        /**
         * The capsule is convex, so the part of the segment p–q inside it is one interval of t in 0..1: stored in
         * [out] (start, end) when there is one. It is the hull of the intervals in the two end discs and the rectangle.
         */
        fun clip(px: Float, py: Float, qx: Float, qy: Float, out: DoubleArray): Boolean {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            fun take(a: Double, b: Double) {
                if (a <= b) { lo = min(lo, a); hi = max(hi, b) }
            }
            val sx = (qx - px).toDouble()
            val sy = (qy - py).toDouble()
            disc(ax, ay, px, py, sx, sy, ::take)
            if (len2 > 0.0) {
                disc(bx, by, px, py, sx, sy, ::take)
                // rectangle along a–b: u (along) in 0..len, v (across) in -r..r, both linear in t
                val ux = dx / len
                val uy = dy / len
                val u0 = (px - ax) * ux + (py - ay) * uy
                val u1 = sx * ux + sy * uy
                val v0 = (px - ax) * uy - (py - ay) * ux
                val v1 = sx * uy - sy * ux
                var a = 0.0
                var b = 1.0
                fun slab(w0: Double, w1: Double, wl: Double, wh: Double): Boolean {
                    if (w1 == 0.0) return w0 in wl..wh
                    var t0 = (wl - w0) / w1
                    var t1 = (wh - w0) / w1
                    if (t0 > t1) { val tmp = t0; t0 = t1; t1 = tmp }
                    a = max(a, t0); b = min(b, t1)
                    return a <= b
                }
                if (slab(u0, u1, 0.0, len) && slab(v0, v1, -r, r)) take(a, b)
            }
            if (lo > hi) return false
            out[0] = lo.coerceIn(0.0, 1.0)
            out[1] = hi.coerceIn(0.0, 1.0)
            return true
        }

        /** The t range of p + t·s inside the disc around (cx, cy). */
        private fun disc(cx: Double, cy: Double, px: Float, py: Float, sx: Double, sy: Double, take: (Double, Double) -> Unit) {
            val fx = px - cx
            val fy = py - cy
            val a = sx * sx + sy * sy
            val c = fx * fx + fy * fy - r * r
            if (a == 0.0) { if (c <= 0.0) take(0.0, 1.0); return }
            val b = 2 * (fx * sx + fy * sy)
            val disc = b * b - 4 * a * c
            if (disc < 0) return
            val root = sqrt(disc)
            val t0 = (-b - root) / (2 * a)
            val t1 = (-b + root) / (2 * a)
            if (t1 < 0 || t0 > 1) return
            take(max(t0, 0.0), min(t1, 1.0))
        }
    }
}
