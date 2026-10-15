package app.wanki.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pen gestures on handwritten pages (as in Nebo / MyScript Notes): scribbling back and forth
 * over strokes erases them, so there is no need to switch to the eraser.
 */
object Gestures {
    /**
     * A scratch-out is a zigzag: the pen reverses direction along its main axis many times and
     * travels far more than the size of the area it covers.
     */
    fun isScratchOut(stroke: InkStroke): Boolean {
        val pts = stroke.points
        if (pts.size < 12) return false
        val b = stroke.bounds() ?: return false
        val extent = max(b.width, b.height)
        if (extent < 8f) return false
        val horizontal = b.width >= b.height
        var length = 0f
        var reversals = 0
        var lastSign = 0
        var acc = 0f
        val minMove = extent * 0.15f // ignore jitter: count a reversal only after a real movement
        for (i in 1 until pts.size) {
            val dx = pts[i].x - pts[i - 1].x
            val dy = pts[i].y - pts[i - 1].y
            length += hypot(dx, dy)
            val d = if (horizontal) dx else dy
            acc += d
            if (abs(acc) >= minMove) {
                val sign = if (acc > 0) 1 else -1
                if (lastSign != 0 && sign != lastSign) reversals++
                lastSign = sign
                acc = 0f
            }
        }
        val diag = hypot(b.width, b.height)
        return reversals >= 4 && length >= diag * 3.2f
    }

    /** Strokes mostly covered by the scratch-out's area (indices into [strokes]). */
    fun scratchedStrokes(scratch: InkStroke, strokes: List<InkStroke>): List<Int> {
        val area = scratch.bounds() ?: return emptyList()
        val pad = min(area.width, area.height) * 0.15f
        val zone = InkRect(area.left - pad, area.top - pad, area.right + pad, area.bottom + pad)
        return strokes.indices.filter { i ->
            val s = strokes[i]
            if (s === scratch || s.points.isEmpty()) return@filter false
            val inside = s.points.count { it.x in zone.left..zone.right && it.y in zone.top..zone.bottom }
            inside >= s.points.size * 0.6
        }
    }
}
