package app.wanki.writing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.wanki.scoring.CharReference
import kotlinx.coroutines.delay
import kotlin.math.min

private val AnimInk = Color(0xFF1A1A1A)
private val StartDot = Color(0xFFD64545)
val HintColor = Color(0xFF1E5BD8)

/** Draws the first [fraction] of reference stroke [index] (by points), with a dot where it starts. */
fun DrawScope.drawPartialStroke(ref: CharReference, index: Int, fraction: Float, cell: Cell, color: Color, width: Float) {
    val pts = ref.strokes[index].points
    if (pts.isEmpty() || fraction <= 0f) return
    val k = cell.side / ref.size.toFloat()
    val n = (pts.size * fraction).toInt().coerceIn(1, pts.size)
    val path = Path()
    pts.take(n).forEachIndexed { i, p ->
        val x = cell.left + p.x.toFloat() * k
        val y = cell.top + p.y.toFloat() * k
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(StartDot, width * 0.6f, Offset(cell.left + pts[0].x.toFloat() * k, cell.top + pts[0].y.toFloat() * k))
}

/** Next-stroke hint: reference stroke [index] in blue with its start dot. */
fun DrawScope.drawStrokeHint(ref: CharReference, index: Int, cell: Cell) {
    if (index !in ref.strokes.indices) return
    drawPartialStroke(ref, index, 1f, cell, HintColor.copy(alpha = 0.55f), 3f * (cell.side / ref.size.toFloat()) * 1.6f)
}

/** Stroke order, animated in a loop; tap to restart. */
@Composable
fun StrokeOrderAnimation(ref: CharReference, modifier: Modifier = Modifier, strokeMs: Int = 550) {
    var restart by remember { mutableIntStateOf(0) }
    val progress = remember(ref, restart) { Animatable(0f) }
    LaunchedEffect(ref, restart) {
        val n = ref.strokes.size.toFloat()
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(n, tween((n * strokeMs).toInt(), easing = LinearEasing))
            delay(1500)
        }
    }
    Canvas(modifier.clickable { restart++ }) {
        val cell = Cell(0f, 0f, min(size.width, size.height))
        drawTianzige(cell)
        drawReference(ref, cell, ReviewColors.reference)
        val width = 3f * (cell.side / ref.size.toFloat()) * 1.6f
        val p = progress.value
        for (i in ref.strokes.indices) drawPartialStroke(ref, i, (p - i).coerceIn(0f, 1f), cell, AnimInk, width)
    }
}
