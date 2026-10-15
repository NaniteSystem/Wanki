package app.wanki.ink

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * Writing surface: low-latency in-progress strokes come from [InProgressStrokesView]
 * (front-buffered where supported), finished strokes are drawn by Compose between
 * [underlay] (grid, ghost) and [overlay] (attempt review).
 */
@Composable
fun InkCanvas(
    state: InkCanvasState,
    brush: Brush,
    modifier: Modifier = Modifier,
    fingerMode: FingerMode = FingerMode.AUTO,
    enabled: Boolean = true,
    showInk: Boolean = true,
    underlay: DrawScope.() -> Unit = {},
    overlay: DrawScope.() -> Unit = {},
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val identity = remember { Matrix() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            underlay()
            if (showInk) {
                val canvas = drawContext.canvas.nativeCanvas
                for (stroke in state.renderStrokes) renderer.draw(canvas, stroke, identity)
            }
            overlay()
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                InProgressStrokesView(context).also { view ->
                    view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                            state.onStrokesFinished(strokes)
                            view.removeFinishedStrokes(strokes.keys)
                        }
                    })
                    val handler = StylusTouchHandler(view, state)
                    view.tag = handler
                    view.setOnTouchListener(handler)
                }
            },
            update = { view ->
                (view.tag as StylusTouchHandler).also {
                    it.brush = brush
                    it.fingerMode = fingerMode
                    it.enabled = enabled
                }
            },
        )
    }
}
