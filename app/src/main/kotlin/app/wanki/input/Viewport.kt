package app.wanki.input

import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import app.wanki.ink.InkRect

/**
 * Pan/zoom of a canvas: screen = world × scale + offset.
 * [bounds] (world units) keeps a page from being dragged away; null = infinite board.
 */
class Viewport(
    val minScale: Float = 0.2f,
    val maxScale: Float = 8f,
    var bounds: InkRect? = null,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var screenW = 0f
    private var screenH = 0f

    fun worldToScreen(): Matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(offsetX, offsetY)
    }

    fun screenToWorld(): Matrix = Matrix().also { worldToScreen().invert(it) }

    fun toWorldX(x: Float) = (x - offsetX) / scale
    fun toWorldY(y: Float) = (y - offsetY) / scale

    /** World rectangle currently on screen. */
    fun visibleWorld(): InkRect = InkRect(toWorldX(0f), toWorldY(0f), toWorldX(screenW), toWorldY(screenH))

    fun onScreenSize(w: Float, h: Float) {
        val first = screenW == 0f
        screenW = w
        screenH = h
        if (first) fitWidth()
    }

    /** Fit the page width (or reset an infinite board to 1:1). */
    fun fitWidth() {
        val b = bounds
        if (b == null || screenW == 0f) {
            set(1f, 0f, 0f)
            return
        }
        val s = (screenW / b.width).coerceIn(minScale, maxScale)
        set(s, (screenW - b.width * s) / 2 - b.left * s, -b.top * s + 8f)
    }

    /** Applies a pan by (dx, dy) screen pixels and a zoom by [zoom] around (fx, fy). */
    fun transform(dx: Float, dy: Float, zoom: Float, fx: Float, fy: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        val k = newScale / scale
        val nx = fx - (fx - offsetX) * k + dx
        val ny = fy - (fy - offsetY) * k + dy
        set(newScale, nx, ny)
    }

    private fun set(s: Float, x: Float, y: Float) {
        scale = s
        var ox = x
        var oy = y
        bounds?.let { b ->
            // keep at least a third of the screen covered by the page
            val margin = 0.33f
            val pageL = b.left * s + ox
            val pageR = b.right * s + ox
            val pageT = b.top * s + oy
            val pageB = b.bottom * s + oy
            if (pageR < screenW * margin) ox += screenW * margin - pageR
            if (pageL > screenW * (1 - margin)) ox -= pageL - screenW * (1 - margin)
            if (pageB < screenH * margin) oy += screenH * margin - pageB
            if (pageT > screenH * (1 - margin)) oy -= pageT - screenH * (1 - margin)
        }
        offsetX = ox
        offsetY = oy
    }
}
