package app.wanki.writing

import androidx.compose.ui.geometry.Offset
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
import kotlin.math.min

object ReviewColors {
    val correct = Color(0xFF2E9E5B)
    val orderOrDirection = Color(0xFFE0A100)
    val wrong = Color(0xFFD64545)
    val reference = Color(0x33808080)
    val grid = Color(0x40B0503C)
}

/** Reference square (e.g. 109×109) → canvas: the largest centered square. */
private fun DrawScope.refScale(ref: CharReference): Pair<Float, Offset> {
    val side = min(size.width, size.height)
    return side / ref.size.toFloat() to Offset((size.width - side) / 2, (size.height - side) / 2)
}

private fun path(points: List<Offset>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
}

private fun DrawScope.strokePath(points: List<Offset>, color: Color, width: Float, dashed: Boolean = false) {
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
fun DrawScope.drawTianzige() {
    val w = px(1.5f)
    drawRect(ReviewColors.grid, style = Stroke(w))
    val dash = PathEffect.dashPathEffect(floatArrayOf(px(6f), px(5f)))
    drawLine(ReviewColors.grid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, pathEffect = dash)
    drawLine(ReviewColors.grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, pathEffect = dash)
}

private fun DrawScope.px(dp: Float) = dp * density

/** Translucent reference strokes, for the "ghost" hint and as the review backdrop. */
fun DrawScope.drawReference(ref: CharReference, color: Color = ReviewColors.reference) {
    val (k, o) = refScale(ref)
    val width = 3f * k * 1.6f // KanjiVG stroke width is 3 units
    for (s in ref.strokes) strokePath(s.points.map { Offset(o.x + it.x.toFloat() * k, o.y + it.y.toFloat() * k) }, color, width)
}

/**
 * Attempt review: the attempt mapped onto the reference, each stroke colored by its status,
 * reference strokes that were not written shown dashed in red.
 */
fun DrawScope.drawReview(ref: CharReference, result: ScoreResult, strokes: List<InkStroke>) {
    drawReference(ref)
    val (k, o) = refScale(ref)
    val t = result.toReference ?: return
    val width = px(4f)
    for (s in result.missingRefStrokes) {
        strokePath(ref.strokes[s].points.map { Offset(o.x + it.x.toFloat() * k, o.y + it.y.toFloat() * k) }, ReviewColors.wrong, width, dashed = true)
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
            Offset(o.x + p.x.toFloat() * k, o.y + p.y.toFloat() * k)
        }
        strokePath(pts, color, width)
        // start marker shows direction
        pts.firstOrNull()?.let { drawCircle(color, width, it) }
    }
}
