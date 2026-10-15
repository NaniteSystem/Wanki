package app.wanki.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.wanki.data.PageTemplate
import app.wanki.ink.InkRect
import kotlin.math.floor

/** Page geometry in world units (A4 proportions). */
object PageGeometry {
    const val WIDTH = 1000f
    const val HEIGHT = 1414f
    val BOUNDS = InkRect(0f, 0f, WIDTH, HEIGHT)
}

private val PaperColor = Color(0xFFFFFEFA)
private val DeskColor = Color(0xFFE6E3DC)
private val RuleColor = Color(0xFFB8C7DA)
private val MarginColor = Color(0x66D23C3C)
private val GuideColor = Color(0x55B0503C)

/** Draws a page (paper + template) in world coordinates. */
fun DrawScope.drawPage(template: PageTemplate) {
    val w = PageGeometry.WIDTH
    val h = PageGeometry.HEIGHT
    drawRect(DeskColor, Offset(-5000f, -5000f), Size(w + 10000f, h + 10000f))
    drawRect(Color(0x22000000), Offset(6f, 8f), Size(w, h))
    drawRect(PaperColor, Offset.Zero, Size(w, h))
    drawTemplate(template, InkRect(0f, 0f, w, h))
}

/** Infinite board: plain paper with a dot grid over the visible area. */
fun DrawScope.drawBoard(visible: InkRect, scale: Float) {
    drawRect(PaperColor, Offset(visible.left, visible.top), Size(visible.width, visible.height))
    if (scale < 0.35f) return
    val step = 50f
    val r = 1.6f / scale.coerceAtMost(1.5f)
    var y = floor(visible.top / step) * step
    while (y <= visible.bottom) {
        var x = floor(visible.left / step) * step
        while (x <= visible.right) {
            drawCircle(RuleColor, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

fun DrawScope.drawTemplate(template: PageTemplate, area: InkRect) {
    val line = 1.5f
    when (template) {
        PageTemplate.BLANK -> Unit
        PageTemplate.LINED, PageTemplate.LINED_RTL -> {
            val top = area.top + 120f
            var y = top
            while (y < area.bottom - 60f) {
                drawLine(RuleColor, Offset(area.left + 40f, y), Offset(area.right - 40f, y), line)
                y += 56f
            }
            val mx = if (template == PageTemplate.LINED) area.left + 90f else area.right - 90f
            drawLine(MarginColor, Offset(mx, area.top + 40f), Offset(mx, area.bottom - 40f), line)
        }
        PageTemplate.GRID -> grid(area, 50f, line)
        PageTemplate.DOTS -> {
            var y = area.top + 50f
            while (y < area.bottom) {
                var x = area.left + 50f
                while (x < area.right) { drawCircle(RuleColor, 2f, Offset(x, y)); x += 50f }
                y += 50f
            }
        }
        PageTemplate.GENKOU, PageTemplate.WONGOJI -> {
            // 20 × 20 squares, rows separated by a narrow gap (horizontal 原稿用紙 / 원고지)
            val cols = 20
            val cell = (area.width - 100f) / cols
            val gap = cell * 0.3f
            val rows = floor((area.height - 100f) / (cell + gap)).toInt()
            val left = area.left + 50f
            var top = area.top + 50f
            repeat(rows) {
                for (c in 0 until cols) {
                    drawRect(MarginColor, Offset(left + c * cell, top), Size(cell, cell), style = Stroke(line))
                }
                top += cell + gap
            }
        }
        PageTemplate.TIANZIGE -> {
            val cols = 8
            val cell = (area.width - 80f) / cols
            val rows = floor((area.height - 80f) / cell).toInt()
            val left = area.left + 40f
            val top = area.top + 40f
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            for (r in 0 until rows) for (c in 0 until cols) {
                val x = left + c * cell
                val y = top + r * cell
                drawRect(GuideColor, Offset(x, y), Size(cell, cell), style = Stroke(line))
                drawLine(GuideColor, Offset(x + cell / 2, y), Offset(x + cell / 2, y + cell), 1f, pathEffect = dash)
                drawLine(GuideColor, Offset(x, y + cell / 2), Offset(x + cell, y + cell / 2), 1f, pathEffect = dash)
            }
        }
    }
}

private fun DrawScope.grid(area: InkRect, step: Float, width: Float) {
    var x = area.left + step
    while (x < area.right) { drawLine(RuleColor, Offset(x, area.top), Offset(x, area.bottom), width); x += step }
    var y = area.top + step
    while (y < area.bottom) { drawLine(RuleColor, Offset(area.left, y), Offset(area.right, y), width); y += step }
}

fun PageTemplate.label(): String = when (this) {
    PageTemplate.BLANK -> "Чистый"
    PageTemplate.LINED -> "Линейка"
    PageTemplate.LINED_RTL -> "Линейка RTL"
    PageTemplate.GRID -> "Клетка"
    PageTemplate.DOTS -> "Точки"
    PageTemplate.GENKOU -> "原稿用紙"
    PageTemplate.WONGOJI -> "원고지"
    PageTemplate.TIANZIGE -> "田字格"
}
