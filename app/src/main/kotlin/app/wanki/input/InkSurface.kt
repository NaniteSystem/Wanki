package app.wanki.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

/**
 * A writing surface. Without a [viewport] it is a fixed canvas (screen = world); with one it
 * pans and zooms (boards, notebook pages).
 *
 * [background] and the strokes are drawn in world coordinates, [overlay] in screen coordinates.
 * In-progress strokes are rendered by androidx.ink with low latency, finished ones by Compose: from
 * [InkTiles] when there is a viewport, stroke by stroke otherwise.
 */
@Composable
fun InkSurface(
    layer: InkLayer,
    tool: ToolSelection,
    modifier: Modifier = Modifier,
    viewport: Viewport? = null,
    enabled: Boolean = true,
    /** Pan and zoom still work, drawing, erasing and selecting do not. */
    readOnly: Boolean = false,
    showInk: Boolean = true,
    gestures: Boolean = false,
    eraser: EraserSettings = EraserSettings(),
    onStrokeStarted: () -> Unit = {},
    onStrokeFinished: () -> Unit = {},
    onViewGestureEnd: () -> Unit = {},
    /** A two-finger fling at the page edge: +1 next page, -1 previous. Null where pages do not turn. */
    onPageSwipe: ((Int) -> Unit)? = null,
    background: DrawScope.() -> Unit = {},
    overlay: DrawScope.() -> Unit = {},
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val scope = rememberCoroutineScope()
    // finished ink of pages and boards comes from cached tiles; small fixed canvases draw it directly
    val tiles = if (viewport != null) remember(layer) { InkTiles(layer, scope) } else null
    if (tiles != null) {
        DisposableEffect(tiles) { onDispose { tiles.dispose() } }
        LaunchedEffect(tiles) { snapshotFlow { layer.selection.toList() }.collect { tiles.exclude(it) } }
    }
    val started by rememberUpdatedState(onStrokeStarted)
    val finished by rememberUpdatedState(onStrokeFinished)
    val viewEnded by rememberUpdatedState(onViewGestureEnd)
    var cursor by remember { mutableStateOf<EraserCursor?>(null) }
    var line by remember { mutableStateOf<LinePreview?>(null) }
    val swiped by rememberUpdatedState(onPageSwipe)
    val cursorColor = MaterialTheme.colorScheme.onSurfaceVariant

    // clip: page backgrounds are drawn far beyond the visible area
    Box(modifier.clipToBounds().onSizeChanged { viewport?.onScreenSize(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = viewport
            val scale = vp?.scale ?: 1f
            val ox = vp?.offsetX ?: 0f
            val oy = vp?.offsetY ?: 0f
            val matrix = vp?.worldToScreen() ?: android.graphics.Matrix()
            withTransform({
                translate(ox, oy)
                scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                background()
                if (showInk) {
                    if (tiles != null && vp != null) tiles.draw(this, vp)
                    else {
                        val canvas = drawContext.canvas.nativeCanvas
                        for (s in layer.strokes) layer.renderable(s)?.let { renderer.draw(canvas, it, matrix) }
                    }
                }
                drawSelection(layer, scale)
                line?.let { drawLinePreview(it) }
            }
            overlay()
            cursor?.let { drawCircle(cursorColor, it.radius, androidx.compose.ui.geometry.Offset(it.x, it.y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                InProgressStrokesView(context).also { view ->
                    val handler = InkInputHandler(view)
                    view.tag = handler
                    view.addFinishedStrokesListener(handler)
                    view.setOnTouchListener(handler)
                    view.setOnHoverListener(handler)
                }
            },
            onRelease = { view -> (view.tag as InkInputHandler).release() },
            update = { view ->
                (view.tag as InkInputHandler).also {
                    it.layer = layer
                    it.viewport = viewport
                    it.tool = tool
                    it.enabled = enabled
                    it.readOnly = readOnly
                    it.gestures = gestures
                    it.eraser = eraser
                    it.onEraserCursor = { c -> cursor = c }
                    it.onStrokeStarted = { started() }
                    it.onStrokeFinished = { finished() }
                    it.onViewGestureEnd = { viewEnded() }
                    it.onLinePreview = { p -> line = p }
                    it.onPageSwipe = if (onPageSwipe != null) ({ dir -> swiped?.invoke(dir) }) else null
                }
            },
        )
    }
}

/** The held straight line, in the stroke's own brush (highlighter translucent). */
private fun DrawScope.drawLinePreview(p: LinePreview) {
    val color = androidx.compose.ui.graphics.Color(p.brush.color)
    drawLine(
        if (p.brush.kind == app.wanki.ink.BrushKind.HIGHLIGHTER) color.copy(alpha = color.alpha * 0.4f) else color,
        p.from, p.to, strokeWidth = p.brush.size, cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
}

private val SelectionColor = androidx.compose.ui.graphics.Color(0xFF1E5BD8)

/** Lasso path while drawing it, then a dashed frame around the selection (world coordinates). */
private fun DrawScope.drawSelection(layer: InkLayer, scale: Float) {
    val w = 2f / scale
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f / scale, 8f / scale))
    layer.lasso?.takeIf { it.size > 1 }?.let { pts ->
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
        }
        drawPath(path, SelectionColor, style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = dash))
    }
    layer.selectionBounds()?.let { b ->
        val pad = 8f / scale
        drawRect(
            SelectionColor.copy(alpha = 0.08f),
            androidx.compose.ui.geometry.Offset(b.left - pad, b.top - pad),
            androidx.compose.ui.geometry.Size(b.width + 2 * pad, b.height + 2 * pad),
        )
        drawRect(
            SelectionColor,
            androidx.compose.ui.geometry.Offset(b.left - pad, b.top - pad),
            androidx.compose.ui.geometry.Size(b.width + 2 * pad, b.height + 2 * pad),
            style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = dash),
        )
    }
}
