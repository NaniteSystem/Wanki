package app.wanki.input

import androidx.compose.ui.geometry.Offset
import app.wanki.ink.InkRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionHandlesTest {
    private val bounds = InkRect(100f, 100f, 300f, 200f)
    private val density = 2.625f
    private val touchPx = 48f * density

    @Test
    fun hitsEachHandleAndInside() {
        for (scale in listOf(0.5f, 1f, 2f)) {
            val dp = density / scale // one dp in world units at this zoom
            val sc = SelectionHandles.scaleHandle(bounds, scale, touchPx)
            assertEquals(300f + 8 * dp, sc.x, 1e-3f)
            assertEquals(Handle.SCALE, SelectionHandles.hit(sc.x, sc.y, bounds, scale, touchPx))
            // 20 dp off the handle is still within the 48 dp touch circle
            assertEquals(Handle.SCALE, SelectionHandles.hit(sc.x + 20 * dp, sc.y, bounds, scale, touchPx))
            val rot = SelectionHandles.rotateHandle(bounds, scale, touchPx)
            assertEquals(100f - 8 * dp - 32 * dp, rot.y, 1e-3f)
            assertEquals(200f, rot.x, 1e-3f)
            assertEquals(Handle.ROTATE, SelectionHandles.hit(rot.x, rot.y, bounds, scale, touchPx))
            assertEquals(Handle.MOVE, SelectionHandles.hit(200f, 150f, bounds, scale, touchPx))
            // the frame plus 16 dp moves, beyond it nothing
            assertEquals(Handle.MOVE, SelectionHandles.hit(100f - 20 * dp, 150f, bounds, scale, touchPx))
            assertEquals(Handle.NONE, SelectionHandles.hit(100f - 30 * dp, 150f, bounds, scale, touchPx))
        }
        // on the strokes it always moves, even under a handle's touch circle (a tiny selection stays draggable)
        val dot = InkRect(10f, 10f, 12f, 12f)
        assertEquals(Handle.MOVE, SelectionHandles.hit(12f, 12f, dot, 1f, touchPx))
    }

    @Test
    fun scaleClamped() {
        val c = Offset(200f, 150f)
        val toCentre = SelectionHandles.transform(Handle.SCALE, bounds, Offset(300f, 200f), c)
        assertEquals(0.1f, toCentre.a, 1e-5f)
        val far = SelectionHandles.transform(Handle.SCALE, bounds, Offset(300f, 200f), Offset(200f + 100f * 50, 150f + 50f * 50))
        assertEquals(10f, far.a, 1e-5f)
        val double = SelectionHandles.transform(Handle.SCALE, bounds, Offset(300f, 200f), Offset(400f, 250f))
        assertEquals(2f, double.a, 1e-4f)
        val (x, y) = double.apply(200f, 150f)
        assertEquals(200f, x, 1e-3f); assertEquals(150f, y, 1e-3f)
    }

    @Test
    fun rotateHalfTurn() {
        val m = SelectionHandles.transform(Handle.ROTATE, bounds, Offset(200f, 50f), Offset(200f, 250f))
        val (x, y) = m.apply(300f, 150f)
        assertTrue(abs(x - 100f) < 1e-2f && abs(y - 150f) < 1e-2f, "($x, $y)")
        assertEquals(-1f, m.a, 1e-4f)
        // a move follows the drag
        val t = SelectionHandles.transform(Handle.MOVE, bounds, Offset(0f, 0f), Offset(5f, -3f))
        assertEquals(5f, t.tx); assertEquals(-3f, t.ty)
        assertTrue(SelectionHandles.transform(Handle.NONE, bounds, Offset.Zero, Offset(9f, 9f)).isIdentity)
        assertEquals(PI.toFloat(), kotlin.math.abs(kotlin.math.atan2(m.b, m.a)), 1e-4f)
    }
}
