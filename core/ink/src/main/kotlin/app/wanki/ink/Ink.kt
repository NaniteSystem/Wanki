package app.wanki.ink

enum class InkTool { UNKNOWN, STYLUS, FINGER, MOUSE }

/**
 * One input sample. Coordinates are in the canvas' own units (usually px).
 * Optional channels use NaN when the device does not report them.
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    /** Milliseconds since the start of the drawing (not of the stroke). */
    val timeMs: Long,
    /** 0..1 */
    val pressure: Float = Float.NaN,
    /** Radians from perpendicular, 0..π/2. */
    val tiltRadians: Float = Float.NaN,
    /** Radians, 0..2π. */
    val orientationRadians: Float = Float.NaN,
)

enum class BrushKind { PEN, MARKER, HIGHLIGHTER }

data class InkBrush(
    val kind: BrushKind = BrushKind.PEN,
    /** ARGB */
    val color: Int = 0xFF1A1A1A.toInt(),
    /** Width in canvas units. */
    val size: Float = 4f,
) {
    companion object {
        val DEFAULT = InkBrush()
    }
}

data class InkStroke(
    val points: List<InkPoint>,
    val tool: InkTool = InkTool.UNKNOWN,
    val brush: InkBrush = InkBrush.DEFAULT,
) {
    val startTimeMs: Long get() = points.firstOrNull()?.timeMs ?: 0
    val endTimeMs: Long get() = points.lastOrNull()?.timeMs ?: 0

    fun bounds(): InkRect? {
        if (points.isEmpty()) return null
        var x0 = Float.POSITIVE_INFINITY; var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < x0) x0 = p.x; if (p.x > x1) x1 = p.x
            if (p.y < y0) y0 = p.y; if (p.y > y1) y1 = p.y
        }
        return InkRect(x0, y0, x1, y1)
    }
}

data class InkRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
    fun union(o: InkRect) = InkRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun intersects(o: InkRect) = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom
}

data class InkDrawing(val strokes: List<InkStroke>) {
    val durationMs: Long
        get() = if (strokes.isEmpty()) 0 else strokes.maxOf { it.endTimeMs } - strokes.minOf { it.startTimeMs }

    companion object {
        val EMPTY = InkDrawing(emptyList())
    }
}
