package app.wanki.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.wanki.data.PageSize
import app.wanki.data.PageTemplate
import app.wanki.ink.InkRect
import kotlin.math.ceil
import kotlin.math.floor

/** Page geometry in world units (A4 proportions). */
object PageGeometry {
    const val WIDTH = 1000f
    const val HEIGHT = 1414f
    val BOUNDS = InkRect(0f, 0f, WIDTH, HEIGHT)

    /** An endless sheet grows in steps of half a page, so there is always at least this much paper below the lowest ink. */
    const val ENDLESS_STEP = 707f

    /**
     * Portrait 1000 × 1414, landscape 1414 × 1000 (A4 and A5 have the same proportions, so they do not differ). An
     * [PageSize.ENDLESS] sheet is always portrait, 1000 wide, and as tall as [contentBottom] (the lowest painted
     * point of ink and objects) needs: at least [ENDLESS_STEP] below it, in whole steps, never under 1414.
     */
    fun size(landscape: Boolean, pageSize: PageSize = PageSize.A4, contentBottom: Float = 0f): Size = when {
        pageSize == PageSize.ENDLESS ->
            Size(WIDTH, maxOf(HEIGHT, ceil((contentBottom + ENDLESS_STEP) / ENDLESS_STEP) * ENDLESS_STEP))
        landscape -> Size(HEIGHT, WIDTH)
        else -> Size(WIDTH, HEIGHT)
    }

    fun bounds(landscape: Boolean, pageSize: PageSize = PageSize.A4, contentBottom: Float = 0f): InkRect =
        if (pageSize != PageSize.ENDLESS) { if (landscape) InkRect(0f, 0f, HEIGHT, WIDTH) else BOUNDS }
        else size(landscape, pageSize, contentBottom).let { InkRect(0f, 0f, it.width, it.height) }
}

internal val PaperColor = Color(0xFFFFFEFA)
private val DeskColor = Color(0xFFE6E3DC)
private val RuleColor = Color(0xFFB8C7DA)
private val MarginColor = Color(0x66D23C3C)
private val GuideColor = Color(0x55B0503C)
private val BaselineColor = Color(0xFF7F97B8)
private val CornellRuleColor = Color(0xFF8DA0BC)

/**
 * Draws a page (paper + template) in world coordinates, at any canvas scale. [pageSize] and [contentBottom] size an
 * endless sheet ([PageGeometry.size]); [visible] (world units) lets the template skip rows that are off screen.
 */
fun DrawScope.drawPage(
    template: PageTemplate, landscape: Boolean = false,
    pageSize: PageSize = PageSize.A4, contentBottom: Float = 0f, visible: InkRect? = null,
) {
    val (w, h) = PageGeometry.size(landscape, pageSize, contentBottom).let { it.width to it.height }
    drawRect(DeskColor, Offset(-5000f, -5000f), Size(w + 10000f, h + 10000f))
    drawRect(Color(0x22000000), Offset(6f, 8f), Size(w, h))
    drawRect(PaperColor, Offset.Zero, Size(w, h))
    drawTemplate(template, InkRect(0f, 0f, w, h), visible)
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

/** Indices of the rows (origin + i × pitch) of [count] that touch the visible part; all of them when [v] is null. */
private fun rowSpan(origin: Float, pitch: Float, count: Int, v: InkRect?): IntRange {
    if (v == null) return 0 until count
    val a = floor((v.top - origin) / pitch).toInt().coerceAtLeast(0)
    val b = floor((v.bottom - origin) / pitch).toInt().coerceAtMost(count - 1)
    return a..b
}

private fun rowCount(origin: Float, pitch: Float, end: Float): Int = ceil((end - origin) / pitch).toInt().coerceAtLeast(0)

/** Draws [template] over [area]; [visible] (world units) limits the rows that are drawn, null = all. */
fun DrawScope.drawTemplate(template: PageTemplate, area: InkRect, visible: InkRect? = null) {
    val line = 1.5f
    when (template) {
        PageTemplate.BLANK -> Unit
        PageTemplate.LINED, PageTemplate.LINED_RTL -> {
            val top = area.top + 120f
            for (i in rowSpan(top, 56f, rowCount(top, 56f, area.bottom - 60f), visible)) {
                val y = top + i * 56f
                drawLine(RuleColor, Offset(area.left + 40f, y), Offset(area.right - 40f, y), line)
            }
            val mx = if (template == PageTemplate.LINED) area.left + 90f else area.right - 90f
            drawLine(MarginColor, Offset(mx, area.top + 40f), Offset(mx, area.bottom - 40f), line)
        }
        PageTemplate.GRID -> grid(area, 50f, line, visible)
        PageTemplate.DOTS -> {
            val top = area.top + 50f
            for (i in rowSpan(top, 50f, rowCount(top, 50f, area.bottom), visible)) {
                var x = area.left + 50f
                while (x < area.right) { drawCircle(RuleColor, 2f, Offset(x, top + i * 50f)); x += 50f }
            }
        }
        PageTemplate.GENKOU, PageTemplate.WONGOJI -> {
            // 20 × 20 squares, rows separated by a narrow gap (horizontal 原稿用紙 / 원고지)
            val cols = 20
            val cell = (area.width - 100f) / cols
            val gap = cell * 0.3f
            val rows = floor((area.height - 100f) / (cell + gap)).toInt()
            val left = area.left + 50f
            val top = area.top + 50f
            for (r in rowSpan(top, cell + gap, rows, visible)) {
                for (c in 0 until cols) {
                    drawRect(MarginColor, Offset(left + c * cell, top + r * (cell + gap)), Size(cell, cell), style = Stroke(line))
                }
            }
        }
        PageTemplate.GENKOU_VERTICAL -> {
            // 20 columns of 20 squares, the first column at the right edge, a 0.3-cell gap between columns; the block repeats down the sheet
            val cols = 20
            val perColumn = 20
            val cell = (area.width - 100f) / (cols + (cols - 1) * 0.3f)
            val gap = cell * 0.3f
            val right = area.right - 50f
            val top = area.top + 50f
            val block = perColumn * cell + gap
            val blocks = floor((area.height - 100f + gap) / block).toInt()
            for (b in rowSpan(top, block, blocks, visible)) for (c in 0 until cols) {
                val x = right - c * (cell + gap) - cell
                for (r in 0 until perColumn) {
                    drawRect(MarginColor, Offset(x, top + b * block + r * cell), Size(cell, cell), style = Stroke(line))
                }
            }
        }
        PageTemplate.TIANZIGE, PageTemplate.MIZIGE -> {
            val cols = 8
            val cell = (area.width - 80f) / cols
            val rows = floor((area.height - 80f) / cell).toInt()
            val left = area.left + 40f
            val top = area.top + 40f
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            for (r in rowSpan(top, cell, rows, visible)) for (c in 0 until cols) {
                val x = left + c * cell
                val y = top + r * cell
                drawRect(GuideColor, Offset(x, y), Size(cell, cell), style = Stroke(line))
                drawLine(GuideColor, Offset(x + cell / 2, y), Offset(x + cell / 2, y + cell), 1f, pathEffect = dash)
                drawLine(GuideColor, Offset(x, y + cell / 2), Offset(x + cell, y + cell / 2), 1f, pathEffect = dash)
                if (template == PageTemplate.MIZIGE) {
                    drawLine(GuideColor, Offset(x, y), Offset(x + cell, y + cell), 1f, pathEffect = dash)
                    drawLine(GuideColor, Offset(x + cell, y), Offset(x, y + cell), 1f, pathEffect = dash)
                }
            }
        }
        PageTemplate.ARABIC -> {
            // a strong baseline every 84 units with a faint guide 28 above it; the margin is on the right
            val top = area.top + 120f
            val faint = RuleColor.copy(alpha = 0.45f)
            for (i in rowSpan(top, 84f, rowCount(top, 84f, area.bottom - 60f), visible)) {
                val y = top + i * 84f
                drawLine(faint, Offset(area.left + 40f, y - 28f), Offset(area.right - 40f, y - 28f), 1f)
                drawLine(BaselineColor, Offset(area.left + 40f, y), Offset(area.right - 40f, y), 2.2f)
            }
            drawLine(MarginColor, Offset(area.right - 90f, area.top + 40f), Offset(area.right - 90f, area.bottom - 40f), line)
        }
        PageTemplate.CORNELL -> {
            // a cue column on the left, a summary area at the bottom, ruled notes between
            val cueX = area.left + 280f
            val summaryY = area.bottom - 260f
            val top = area.top + 120f
            for (i in rowSpan(top, 56f, rowCount(top, 56f, summaryY - 20f), visible)) {
                val y = top + i * 56f
                drawLine(RuleColor, Offset(cueX + 16f, y), Offset(area.right - 40f, y), line)
            }
            drawLine(CornellRuleColor, Offset(cueX, area.top), Offset(cueX, summaryY), 2.2f)
            drawLine(CornellRuleColor, Offset(area.left, summaryY), Offset(area.right, summaryY), 2.2f)
        }
    }
}

private fun DrawScope.grid(area: InkRect, step: Float, width: Float, visible: InkRect?) {
    var x = area.left + step
    while (x < area.right) { drawLine(RuleColor, Offset(x, area.top), Offset(x, area.bottom), width); x += step }
    val top = area.top + step
    for (i in rowSpan(top, step, rowCount(top, step, area.bottom), visible)) {
        val y = top + i * step
        drawLine(RuleColor, Offset(area.left, y), Offset(area.right, y), width)
    }
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
    PageTemplate.MIZIGE -> "米字格"
    PageTemplate.GENKOU_VERTICAL -> "原稿用紙 (вертикальная)"
    PageTemplate.ARABIC -> "Арабская (базовая линия)"
    PageTemplate.CORNELL -> "Корнелл"
}

fun PageSize.label(): String = when (this) {
    PageSize.A4 -> "A4"
    PageSize.A5 -> "A5"
    PageSize.ENDLESS -> "Бесконечная"
}
