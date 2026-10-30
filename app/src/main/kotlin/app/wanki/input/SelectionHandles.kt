package app.wanki.input

import androidx.compose.ui.geometry.Offset
import app.wanki.ink.InkAffine
import app.wanki.ink.InkRect
import kotlin.math.atan2
import kotlin.math.hypot

/** What a touch on a lasso selection does. */
enum class Handle { NONE, MOVE, SCALE, ROTATE }

/**
 * Where the handles of a lasso selection are and what dragging them does (Decision 7: always about the selection
 * centre, uniform scale only). Positions are in world units; on screen the handles keep their size at any zoom,
 * so sizes are given in screen px through [touchPx] (the 48 dp touch target) and divided by the zoom [scale].
 */
object SelectionHandles {
    /** The frame is drawn this far (dp) around the strokes' bounds. */
    const val FRAME_DP = 8f
    /** The rotate handle sits this far (dp) above the top centre of the frame. */
    const val ROTATE_DP = 32f
    /** A touch this close (dp) around the frame still drags the selection. */
    const val MOVE_MARGIN_DP = 16f
    private const val TOUCH_DP = 48f

    private fun dp(v: Float, scale: Float, touchPx: Float) = v * touchPx / TOUCH_DP / scale

    /** The dashed frame around [bounds]. */
    fun frame(bounds: InkRect, scale: Float, touchPx: Float): InkRect {
        val p = dp(FRAME_DP, scale, touchPx)
        return InkRect(bounds.left - p, bounds.top - p, bounds.right + p, bounds.bottom + p)
    }

    /** Centre of the scale handle: the bottom-right corner of the frame. */
    fun scaleHandle(bounds: InkRect, scale: Float, touchPx: Float): Offset =
        frame(bounds, scale, touchPx).let { Offset(it.right, it.bottom) }

    /** Centre of the rotate handle: [ROTATE_DP] above the top centre of the frame. */
    fun rotateHandle(bounds: InkRect, scale: Float, touchPx: Float): Offset =
        frame(bounds, scale, touchPx).let { Offset((it.left + it.right) / 2, it.top - dp(ROTATE_DP, scale, touchPx)) }

    /**
     * Which handle a touch at ([x], [y]) (world) takes. On the strokes themselves it always moves them, so a tiny
     * selection can still be dragged; then the two handles (a [touchPx] circle each), then the frame plus [MOVE_MARGIN_DP].
     */
    fun hit(x: Float, y: Float, bounds: InkRect, scale: Float, touchPx: Float): Handle {
        if (x in bounds.left..bounds.right && y in bounds.top..bounds.bottom) return Handle.MOVE
        val r = touchPx / 2 / scale
        val rot = rotateHandle(bounds, scale, touchPx)
        if (hypot(x - rot.x, y - rot.y) <= r) return Handle.ROTATE
        val sc = scaleHandle(bounds, scale, touchPx)
        if (hypot(x - sc.x, y - sc.y) <= r) return Handle.SCALE
        val f = frame(bounds, scale, touchPx)
        val m = dp(MOVE_MARGIN_DP, scale, touchPx)
        if (x in f.left - m..f.right + m && y in f.top - m..f.bottom + m) return Handle.MOVE
        return Handle.NONE
    }

    /**
     * The map of a drag of [handle] from [start] to [now] (world), about the centre of [bounds]: a move follows the
     * drag, the scale is the ratio of the distances to the centre (0.1–10), the turn is the change of angle.
     */
    fun transform(handle: Handle, bounds: InkRect, start: Offset, now: Offset): InkAffine {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        return when (handle) {
            Handle.NONE -> InkAffine.identity
            Handle.MOVE -> InkAffine.translate(now.x - start.x, now.y - start.y)
            Handle.SCALE -> {
                val from = hypot(start.x - cx, start.y - cy)
                if (from < 1e-3f) InkAffine.identity
                else InkAffine.scaleAbout(cx, cy, (hypot(now.x - cx, now.y - cy) / from).coerceIn(MIN_SCALE, MAX_SCALE))
            }
            Handle.ROTATE -> InkAffine.rotateAbout(cx, cy, atan2(now.y - cy, now.x - cx) - atan2(start.y - cy, start.x - cx))
        }
    }

    const val MIN_SCALE = 0.1f
    const val MAX_SCALE = 10f
}
