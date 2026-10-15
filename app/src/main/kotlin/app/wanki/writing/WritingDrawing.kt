package app.wanki.writing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.wanki.ink.InkStroke
import app.wanki.scoring.CharReference
import app.wanki.scoring.ScoreResult
import app.wanki.scoring.StrokeStatus
import app.wanki.scoring.geom.Point

object ReviewColors {
    val correct = Color(0xFF2E9E5B)
    val orderOrDirection = Color(0xFFE0A100)
    val wrong = Color(0xFFD64545)
    val reference = Color(0x33808080)
    val grid = Color(0x40B0503C)
}

/** A square cell of the writing area, in canvas pixels. */
data class Cell(val left: Float, val top: Float, val side: Float)

private fun DrawScope.px(dp: Float) = dp * density

private fun path(points: List<Offset>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
}

private fun DrawScope.strokePath(points: List<Offset>, color: Color, width: Float, dashed: Boolean = false) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, width / 2, points[0])
        return
    }
    drawPath(
        path(points),
        color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 1.5f, width * 1.5f)) else null,
        ),
    )
}

/** 田字格: frame plus dashed center cross. */
fun DrawScope.drawTianzige(cell: Cell) {
    val w = px(1.5f)
    val (l, t, s) = cell
    drawRect(ReviewColors.grid, Offset(l, t), Size(s, s), style = Stroke(w))
    val dash = PathEffect.dashPathEffect(floatArrayOf(px(6f), px(5f)))
    drawLine(ReviewColors.grid, Offset(l + s / 2, t), Offset(l + s / 2, t + s), w, pathEffect = dash)
    drawLine(ReviewColors.grid, Offset(l, t + s / 2), Offset(l + s, t + s / 2), w, pathEffect = dash)
}

fun DrawScope.drawTianzige() = drawTianzige(Cell(0f, 0f, minOf(size.width, size.height)))

private fun refToCell(ref: CharReference, cell: Cell): (Double, Double) -> Offset {
    val k = cell.side / ref.size.toFloat()
    return { x, y -> Offset(cell.left + x.toFloat() * k, cell.top + y.toFloat() * k) }
}

/** Translucent reference strokes, for the "ghost" hint and as the review backdrop. */
fun DrawScope.drawReference(ref: CharReference, cell: Cell, color: Color = ReviewColors.reference) {
    val map = refToCell(ref, cell)
    val width = 3f * (cell.side / ref.size.toFloat()) * 1.6f // KanjiVG stroke width is 3 units
    for (s in ref.strokes) strokePath(s.points.map { map(it.x, it.y) }, color, width)
}

fun DrawScope.drawReference(ref: CharReference, color: Color = ReviewColors.reference) =
    drawReference(ref, Cell(0f, 0f, minOf(size.width, size.height)), color)

/**
 * Attempt review: the attempt mapped onto the reference, each stroke colored by its status,
 * reference strokes that were not written shown dashed in red, a dot marking each stroke's start.
 * [strokes] are in the coordinates that were passed to the scorer.
 */
fun DrawScope.drawReview(ref: CharReference, result: ScoreResult, strokes: List<InkStroke>, cell: Cell) {
    drawReference(ref, cell)
    val map = refToCell(ref, cell)
    val t = result.toReference ?: return
    val width = px(4f)
    for (s in result.missingRefStrokes) {
        strokePath(ref.strokes[s].points.map { map(it.x, it.y) }, ReviewColors.wrong, width, dashed = true)
    }
    for (fb in result.strokes) {
        val stroke = strokes.getOrNull(fb.userIndex) ?: continue
        val color = when (fb.status) {
            StrokeStatus.CORRECT -> ReviewColors.correct
            StrokeStatus.ORDER_OR_DIRECTION -> ReviewColors.orderOrDirection
            StrokeStatus.EXTRA -> ReviewColors.wrong
        }
        val pts = stroke.points.map {
            val p = t.apply(Point(it.x.toDouble(), it.y.toDouble()))
            map(p.x, p.y)
        }
        strokePath(pts, color, width)
        pts.firstOrNull()?.let { drawCircle(color, width, it) }
    }
}

fun DrawScope.drawReview(ref: CharReference, result: ScoreResult, strokes: List<InkStroke>) =
    drawReview(ref, result, strokes, Cell(0f, 0f, minOf(size.width, size.height)))
