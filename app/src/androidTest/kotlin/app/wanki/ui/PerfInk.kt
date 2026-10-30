package app.wanki.ui

import app.wanki.ink.BrushKind
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ink.InkTool
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic handwriting for the canvas speed tests: a page full of glyph-sized pen strokes. */
object PerfInk {
    private const val ROWS = 40
    private const val PER_ROW = 75
    private const val POINTS = 24

    /**
     * [strokes] strokes (40 rows of 75 on an A4 page, 1000 × 1414 units), 24 points each, with stylus
     * pressure, tilt and orientation, pen brush 4. The same [seed] gives the same page.
     */
    fun page(strokes: Int = ROWS * PER_ROW, seed: Int = 1): List<InkStroke> {
        val rnd = Random(seed)
        val cellW = 960f / PER_ROW
        val cellH = 1360f / ROWS
        val brush = InkBrush(BrushKind.PEN, 0xFF1A1A1A.toInt(), 4f)
        var t = 0L
        return (0 until strokes).map { i ->
            val row = (i / PER_ROW) % ROWS
            val col = i % PER_ROW
            val x0 = 20f + col * cellW
            val y0 = 30f + row * cellH
            // a hook or loop inside the cell: a wobbly arc
            val start = rnd.nextFloat() * 2 * PI.toFloat()
            val sweep = (1f + rnd.nextFloat() * 3f) * if (rnd.nextBoolean()) 1 else -1
            val rx = cellW * (0.25f + rnd.nextFloat() * 0.2f)
            val ry = cellH * (0.25f + rnd.nextFloat() * 0.2f)
            val points = (0 until POINTS).map { k ->
                val a = start + sweep * k / (POINTS - 1)
                t += 8
                InkPoint(
                    x = x0 + cellW / 2 + rx * cos(a) + rnd.nextFloat() * 0.6f,
                    y = y0 + cellH / 2 + ry * sin(a) + rnd.nextFloat() * 0.6f,
                    timeMs = t,
                    pressure = 0.4f + 0.4f * sin(PI.toFloat() * k / (POINTS - 1)),
                    tiltRadians = 0.5f + rnd.nextFloat() * 0.1f,
                    orientationRadians = 1.2f + rnd.nextFloat() * 0.1f,
                )
            }
            t += 120
            InkStroke(points, InkTool.STYLUS, brush)
        }
    }
}
