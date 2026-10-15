package app.wanki.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer

/**
 * A writing surface. Without a [viewport] it is a fixed canvas (screen = world); with one it
 * pans and zooms (boards, notebook pages).
 *
 * [background] and the strokes are drawn in world coordinates, [overlay] in screen coordinates.
 * In-progress strokes are rendered by androidx.ink with low latency, finished ones by Compose.
 */
@Composable
fun InkSurface(
    layer: InkLayer,
    tool: ToolSelection,
    modifier: Modifier = Modifier,
    viewport: Viewport? = null,
    enabled: Boolean = true,
    showInk: Boolean = true,
    onStrokeStarted: () -> Unit = {},
    onStrokeFinished: () -> Unit = {},
    background: DrawScope.() -> Unit = {},
    overlay: DrawScope.() -> Unit = {},
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val started by rememberUpdatedState(onStrokeStarted)
    val finished by rememberUpdatedState(onStrokeFinished)

    Box(modifier.onSizeChanged { viewport?.onScreenSize(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = viewport
            val scale = vp?.scale ?: 1f
            val ox = vp?.offsetX ?: 0f
            val oy = vp?.offsetY ?: 0f
            val matrix = vp?.worldToScreen() ?: android.graphics.Matrix()
            val visible = vp?.visibleWorld()
            withTransform({
                translate(ox, oy)
                scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                background()
                if (showInk) {
                    val canvas = drawContext.canvas.nativeCanvas
                    for (s in layer.strokes) {
                        if (visible != null) {
                            val b = layer.bounds(s) ?: continue
                            if (!b.intersects(visible)) continue
                        }
                        layer.renderable(s)?.let { renderer.draw(canvas, it, matrix) }
                    }
                }
            }
            overlay()
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
            update = { view ->
                (view.tag as InkInputHandler).also {
                    it.layer = layer
                    it.viewport = viewport
                    it.tool = tool
                    it.enabled = enabled
                    it.onStrokeStarted = { started() }
                    it.onStrokeFinished = { finished() }
                }
            },
        )
    }
}
