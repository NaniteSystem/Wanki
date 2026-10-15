package app.wanki.input

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.wanki.ink.BrushKind
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ink.InkTool

/** Conversions between our storage model ([InkStroke]) and androidx.ink [Stroke] for rendering. */
object StrokeConversion {
    private val brushCache = HashMap<InkBrush, Brush>()

    fun brush(b: InkBrush): Brush = synchronized(brushCache) {
        brushCache.getOrPut(b) {
            val family = when (b.kind) {
                BrushKind.PEN -> StockBrushes.pressurePen()
                BrushKind.MARKER -> StockBrushes.marker()
                BrushKind.HIGHLIGHTER -> StockBrushes.highlighter()
            }
            Brush.createWithColorIntArgb(family = family, colorIntArgb = b.color, size = b.size, epsilon = 0.1f)
        }
    }

    fun fromInk(stroke: Stroke, startOffsetMs: Long, brush: InkBrush): InkStroke {
        val input = StrokeInput()
        val batch = stroke.inputs
        val points = ArrayList<InkPoint>(batch.size)
        var tool = InkTool.UNKNOWN
        for (i in 0 until batch.size) {
            batch.populate(i, input)
            if (i == 0) tool = input.toolType.toInkTool()
            points += InkPoint(
                x = input.x,
                y = input.y,
                timeMs = startOffsetMs + input.elapsedTimeMillis,
                pressure = if (input.hasPressure) input.pressure else Float.NaN,
                tiltRadians = if (input.hasTilt) input.tiltRadians else Float.NaN,
                orientationRadians = if (input.hasOrientation) input.orientationRadians else Float.NaN,
            )
        }
        return InkStroke(points, tool, brush)
    }

    /** Rebuilds a renderable stroke from storage; invalid samples (duplicates, time going back) are skipped. */
    fun toInk(s: InkStroke): Stroke? {
        if (s.points.isEmpty()) return null
        val batch = MutableStrokeInputBatch()
        val t0 = s.points.first().timeMs
        val type = s.tool.toInputToolType()
        var lastT = -1L
        val hasPressure = s.points.all { !it.pressure.isNaN() }
        val hasTilt = hasPressure && s.points.all { !it.tiltRadians.isNaN() }
        val hasOrientation = hasTilt && s.points.all { !it.orientationRadians.isNaN() }
        for (p in s.points) {
            val t = maxOf(p.timeMs - t0, lastT.coerceAtLeast(0))
            try {
                batch.add(
                    type = type,
                    x = p.x,
                    y = p.y,
                    elapsedTimeMillis = t,
                    strokeUnitLengthCm = StrokeInput.NO_STROKE_UNIT_LENGTH,
                    pressure = if (hasPressure) p.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
                    tiltRadians = if (hasTilt) p.tiltRadians else StrokeInput.NO_TILT,
                    orientationRadians = if (hasOrientation) p.orientationRadians else StrokeInput.NO_ORIENTATION,
                )
                lastT = t
            } catch (_: IllegalArgumentException) {
                // duplicate or out-of-order sample
            }
        }
        if (batch.size == 0) return null
        return Stroke(brush(s.brush), batch)
    }

    private fun InputToolType.toInkTool() = when (this) {
        InputToolType.STYLUS -> InkTool.STYLUS
        InputToolType.TOUCH -> InkTool.FINGER
        InputToolType.MOUSE -> InkTool.MOUSE
        else -> InkTool.UNKNOWN
    }

    private fun InkTool.toInputToolType() = when (this) {
        InkTool.STYLUS -> InputToolType.STYLUS
        InkTool.FINGER -> InputToolType.TOUCH
        InkTool.MOUSE -> InputToolType.MOUSE
        InkTool.UNKNOWN -> InputToolType.UNKNOWN
    }
}
