package app.wanki.ink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.hypot

/** Which tools may draw. Stylus always can; the eraser end of a pen always erases. */
enum class FingerMode {
    /** Fingers draw until a stylus is seen, then only the stylus does (palm and fingers rest freely). */
    AUTO,
    STYLUS_ONLY,
    ANY,
}

/**
 * Finished strokes of one drawing, kept both as androidx.ink [Stroke]s (for rendering) and as
 * [InkStroke]s (for scoring, recognition and storage). Times are relative to the first stroke.
 */
class InkCanvasState {
    val renderStrokes = mutableStateListOf<Stroke>()
    val strokes = mutableStateListOf<InkStroke>()

    /** Set once a stylus touches the canvas; [FingerMode.AUTO] then stops accepting fingers. */
    var stylusSeen by mutableStateOf(false)
        internal set

    private var drawingStartUptime = -1L
    private val strokeStarts = HashMap<InProgressStrokeId, Long>()

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun drawing() = InkDrawing(strokes.toList())

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        renderStrokes.removeAt(renderStrokes.lastIndex)
    }

    fun clear() {
        strokes.clear()
        renderStrokes.clear()
        drawingStartUptime = -1L
    }

    /** Removes the most recent stroke passing within [radius] of (x, y). */
    fun eraseAt(x: Float, y: Float, radius: Float): Boolean {
        val i = strokes.indexOfLast { s -> s.points.any { hypot(it.x - x, it.y - y) <= radius } }
        if (i < 0) return false
        strokes.removeAt(i)
        renderStrokes.removeAt(i)
        return true
    }

    internal fun onStrokeStarted(id: InProgressStrokeId, eventUptimeMs: Long) {
        if (drawingStartUptime < 0) drawingStartUptime = eventUptimeMs
        strokeStarts[id] = eventUptimeMs
    }

    internal fun onStrokeCancelled(id: InProgressStrokeId) {
        strokeStarts.remove(id)
    }

    internal fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
        finished.entries
            .sortedBy { strokeStarts[it.key] ?: Long.MAX_VALUE }
            .forEach { (id, stroke) ->
                val start = strokeStarts.remove(id) ?: drawingStartUptime
                renderStrokes += stroke
                strokes += stroke.toInkStroke(start - drawingStartUptime.coerceAtLeast(0))
            }
    }

    private fun Stroke.toInkStroke(offsetMs: Long): InkStroke {
        val input = StrokeInput()
        val points = ArrayList<InkPoint>(inputs.size)
        var tool = InkTool.UNKNOWN
        for (i in 0 until inputs.size) {
            inputs.populate(i, input)
            if (i == 0) tool = input.toolType.toInkTool()
            points += InkPoint(
                x = input.x,
                y = input.y,
                timeMs = offsetMs + input.elapsedTimeMillis,
                pressure = if (input.hasPressure) input.pressure else Float.NaN,
                tiltRadians = if (input.hasTilt) input.tiltRadians else Float.NaN,
                orientationRadians = if (input.hasOrientation) input.orientationRadians else Float.NaN,
            )
        }
        return InkStroke(points, tool)
    }

    private fun InputToolType.toInkTool() = when (this) {
        InputToolType.STYLUS -> InkTool.STYLUS
        InputToolType.TOUCH -> InkTool.FINGER
        InputToolType.MOUSE -> InkTool.MOUSE
        else -> InkTool.UNKNOWN
    }
}
