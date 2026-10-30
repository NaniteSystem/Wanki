package app.wanki.input

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import app.wanki.ink.InkStroke

/**
 * The one path that rasterizes finished ink into a bitmap: tiles and thumbnails. World point (x, y)
 * lands on bitmap pixel ((x − originX) × scale, (y − originY) × scale).
 */
object InkRaster {
    private val identity = Matrix()

    /**
     * Draws [strokes] in order. The canvas is scaled and translated here, because the stroke renderer
     * ignores its own transform on bitmap canvases. [minPixelWidth] keeps hairlines visible at small
     * scales (thumbnails). [renderer] and [ink] let a caller reuse its renderer and converted strokes.
     */
    fun draw(
        canvas: Canvas,
        strokes: Iterable<InkStroke>,
        scale: Float,
        originX: Float,
        originY: Float,
        minPixelWidth: Float = 0f,
        renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create(),
        ink: (InkStroke) -> Stroke? = StrokeConversion::toInk,
    ) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-originX, -originY)
        val minWidth = minPixelWidth / scale
        for (s in strokes) {
            val r = if (s.brush.size >= minWidth) ink(s) else StrokeConversion.toInk(s.copy(brush = s.brush.copy(size = minWidth)))
            r?.let { renderer.draw(canvas, it, identity) }
        }
        canvas.restore()
    }
}
