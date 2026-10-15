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

data class InkStroke(
    val points: List<InkPoint>,
    val tool: InkTool = InkTool.UNKNOWN,
) {
    val startTimeMs: Long get() = points.firstOrNull()?.timeMs ?: 0
    val endTimeMs: Long get() = points.lastOrNull()?.timeMs ?: 0
}

data class InkDrawing(val strokes: List<InkStroke>) {
    val durationMs: Long
        get() = if (strokes.isEmpty()) 0 else strokes.maxOf { it.endTimeMs } - strokes.minOf { it.startTimeMs }

    companion object {
        val EMPTY = InkDrawing(emptyList())
    }
}
