package app.wanki.ink

import kotlin.math.abs
import kotlin.math.max

/**
 * Groups the strokes of a handwritten page into horizontal text lines, so each line can be sent
 * to the recognizer on its own (a whole page in one request recognizes poorly).
 *
 * Strokes are clustered by their vertical center. The scale is the typical character size
 * (upper quartile of stroke extents), so thin strokes like 一 and small marks like 、 or dots
 * join the line they sit on. Big strokes are placed first and define the lines.
 * Lines come out top to bottom, strokes inside a line in writing order.
 */
object LineSegmenter {
    fun segment(strokes: List<InkStroke>): List<List<Int>> {
        val boxes = strokes.map { it.bounds() }
        val idx = strokes.indices.filter { boxes[it] != null }
        if (idx.isEmpty()) return emptyList()

        fun extent(i: Int) = boxes[i]!!.let { max(it.width, it.height) }
        val extents = idx.map(::extent).sorted()
        val charSize = max(extents[extents.size * 3 / 4], 1f)

        class Line(var center: Float, var weight: Float) {
            val members = ArrayList<Int>()
        }
        val lines = ArrayList<Line>()
        for (i in idx.sortedByDescending(::extent)) {
            val c = boxes[i]!!.centerY
            val w = max(extent(i), charSize * 0.1f)
            val nearest = lines.minByOrNull { abs(it.center - c) }
            val small = extent(i) < charSize * 0.35f
            val reach = charSize * if (small) 0.9f else 0.6f
            if (nearest != null && abs(nearest.center - c) < reach) {
                nearest.members += i
                if (!small) {
                    nearest.center = (nearest.center * nearest.weight + c * w) / (nearest.weight + w)
                    nearest.weight += w
                }
            } else {
                lines += Line(c, w).also { it.members += i }
            }
        }
        // Merge lines that ended up too close (e.g. started by two halves of one character).
        lines.sortBy { it.center }
        val merged = ArrayList<Line>()
        for (l in lines) {
            val prev = merged.lastOrNull()
            if (prev != null && l.center - prev.center < charSize * 0.5f) {
                prev.members += l.members
                prev.center = (prev.center * prev.weight + l.center * l.weight) / (prev.weight + l.weight)
                prev.weight += l.weight
            } else merged += l
        }
        return merged.map { it.members.sorted() }
    }
}
