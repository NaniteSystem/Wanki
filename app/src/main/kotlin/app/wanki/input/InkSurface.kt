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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import app.wanki.R
import app.wanki.ink.InkAffine
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

/**
 * A writing surface. Without a [viewport] it is a fixed canvas (screen = world); with one it
 * pans and zooms (boards, notebook pages).
 *
 * [background], the layer's page objects (under the ink) and the strokes are drawn in world coordinates, [overlay]
 * in screen coordinates.
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
    /** Tape masks drawn open (outline only); the others are closed. */
    openMasks: Set<String> = emptySet(),
    /** A finished «Скотч» gesture; null where there is no tape. */
    onMask: ((List<androidx.compose.ui.geometry.Offset>, MaskGesture) -> Unit)? = null,
    overlay: DrawScope.() -> Unit = {},
) {
    // made at first use: canvases with tiles never draw strokes one by one
    val renderer = remember { lazy { CanvasStrokeRenderer.create() } }
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
    val masked by rememberUpdatedState(onMask)
    val cursorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scaleIcon = painterResource(R.drawable.ic_open_in_full)
    val rotateIcon = painterResource(R.drawable.ic_rotate_right)
    val touchPx = with(LocalDensity.current) { 48.dp.toPx() }
    val media = (androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.wanki.WankiApp)?.graph?.media
    val objectPainter = remember(media) { PageObjectPainter(media, scope) }

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
                if (layer.objects.isNotEmpty()) drawObjects(layer, objectPainter, scale, masks = false, openMasks)
                if (showInk) {
                    if (tiles != null && vp != null) tiles.draw(this, vp)
                    else drawStrokesDirect(layer, renderer.value, matrix)
                }
                if (layer.objects.isNotEmpty()) drawObjects(layer, objectPainter, scale, masks = true, openMasks)
                drawSelection(layer, scale, touchPx)
                line?.let { drawLinePreview(it) }
            }
            if (!readOnly) drawHandles(layer, scale, ox, oy, touchPx, scaleIcon, rotateIcon)
            overlay()
            cursor?.let { drawCircle(cursorColor, it.radius, androidx.compose.ui.geometry.Offset(it.x, it.y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())) }
        }
        // the low-latency stroke view (a SurfaceView with a native renderer) is made once input is first enabled, so a
        // page that is still loading shows its ink without waiting for it; nothing can be drawn before that anyway
        var inputMade by remember { mutableStateOf(enabled) }
        if (enabled) inputMade = true
        if (inputMade) AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                OpenTrace.mark("InProgressStrokesView factory start")
                InProgressStrokesView(context).also { view ->
                    val handler = InkInputHandler(view)
                    view.tag = handler
                    view.addFinishedStrokesListener(handler)
                    view.setOnTouchListener(handler)
                    view.setOnHoverListener(handler)
                    OpenTrace.mark("InProgressStrokesView factory end")
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
                    it.onMask = if (onMask != null) ({ pts, g -> masked?.invoke(pts, g) }) else null
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

/** The same map as an android.graphics.Matrix, for drawing. */
internal fun InkAffine.toMatrix(): android.graphics.Matrix =
    android.graphics.Matrix().apply { setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)) }

/** Every stroke, one by one (canvases without tiles); a selection being dragged is drawn where the drag puts it. */
private fun DrawScope.drawStrokesDirect(layer: InkLayer, renderer: CanvasStrokeRenderer, matrix: android.graphics.Matrix) {
    val canvas = drawContext.canvas.nativeCanvas
    val live = layer.liveTransform?.takeIf { !it.isIdentity }
    val moving = if (live == null) emptySet() else java.util.Collections.newSetFromMap(java.util.IdentityHashMap<app.wanki.ink.InkStroke, Boolean>()).apply { addAll(layer.selection) }
    val m = live?.toMatrix()
    val full = m?.let { android.graphics.Matrix(matrix).apply { preConcat(it) } }
    for (s in layer.strokes) {
        val ink = layer.renderable(s) ?: continue
        if (m == null || s !in moving) renderer.draw(canvas, ink, matrix)
        else {
            canvas.save()
            canvas.concat(m)
            renderer.draw(canvas, ink, full!!)
            canvas.restore()
        }
    }
}

/**
 * Text blocks and pictures (under the ink), or tape masks ([masks], over it), in draw order; objects being dragged are
 * drawn where the drag puts them.
 */
private fun DrawScope.drawObjects(layer: InkLayer, painter: PageObjectPainter, scale: Float, masks: Boolean, openMasks: Set<String>) {
    painter.rev // a picture that arrives draws again
    val canvas = drawContext.canvas.nativeCanvas
    val live = layer.liveTransform?.takeIf { !it.isIdentity }
    for (o in layer.objects) {
        if ((o.kind == app.wanki.data.PageObjectKind.MASK) != masks) continue
        painter.draw(canvas, if (live != null && layer.isTransforming(o.id)) o.transformed(live) else o, scale, open = o.id in openMasks)
    }
}

/** Lasso path while drawing it, then a dashed frame around the selection (world coordinates), turned with a running drag. */
private fun DrawScope.drawSelection(layer: InkLayer, scale: Float, touchPx: Float) {
    val w = 2f / scale
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f / scale, 8f / scale))
    layer.lasso?.takeIf { it.size > 1 }?.let { pts ->
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
        }
        drawPath(path, SelectionColor, style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = dash))
    }
    val b = layer.selectionBounds() ?: return
    val f = SelectionHandles.frame(b, scale, touchPx)
    val live = layer.liveTransform ?: InkAffine.identity
    val corners = listOf(f.left to f.top, f.right to f.top, f.right to f.bottom, f.left to f.bottom).map { (x, y) -> live.apply(x, y) }
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(corners[0].first, corners[0].second)
        for (c in corners.drop(1)) lineTo(c.first, c.second)
        close()
    }
    drawPath(path, SelectionColor.copy(alpha = 0.08f))
    drawPath(path, SelectionColor, style = androidx.compose.ui.graphics.drawscope.Stroke(w, pathEffect = dash))
}

/**
 * The scale handle (bottom-right corner) and the rotate handle (above the top centre), in screen coordinates so they
 * keep their size at any zoom; they follow a running drag.
 */
private fun DrawScope.drawHandles(
    layer: InkLayer, scale: Float, ox: Float, oy: Float, touchPx: Float, scaleIcon: Painter, rotateIcon: Painter,
) {
    if (layer.lasso != null) return
    val b = layer.selectionBounds() ?: return
    val live = layer.liveTransform ?: InkAffine.identity
    fun screen(p: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset =
        live.apply(p.x, p.y).let { (x, y) -> androidx.compose.ui.geometry.Offset(x * scale + ox, y * scale + oy) }
    val f = SelectionHandles.frame(b, scale, touchPx)
    val topCentre = screen(androidx.compose.ui.geometry.Offset((f.left + f.right) / 2, f.top))
    val rot = screen(SelectionHandles.rotateHandle(b, scale, touchPx))
    val sc = screen(SelectionHandles.scaleHandle(b, scale, touchPx))
    drawLine(SelectionColor, topCentre, rot, strokeWidth = 2.dp.toPx())
    val r = 14.dp.toPx()
    for ((c, icon) in listOf(sc to scaleIcon, rot to rotateIcon)) {
        drawCircle(androidx.compose.ui.graphics.Color.White, r, c)
        drawCircle(SelectionColor, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        val s = 18.dp.toPx()
        translate(c.x - s / 2, c.y - s / 2) {
            with(icon) { draw(androidx.compose.ui.geometry.Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(SelectionColor)) }
        }
    }
}
