package app.wanki.input

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import app.wanki.ink.InkBrush
import kotlin.math.hypot

/**
 * All canvas input in one place, no mode buttons:
 * - stylus tip draws (or erases with the eraser tool), the pen's eraser end and side button erase;
 * - a finger draws only while no stylus is active ([StylusTracker]), otherwise it pans;
 * - two fingers always pan and zoom (when the canvas has a [viewport]);
 * - palm touches reported as cancelled are dropped.
 */
internal class InkInputHandler(
    private val view: InProgressStrokesView,
) : View.OnTouchListener, View.OnHoverListener, InProgressStrokesFinishedListener {
    var layer: InkLayer? = null
    var viewport: Viewport? = null
    var tool: ToolSelection = ToolSelection()
    var enabled: Boolean = true
    var eraserRadiusPx: Float = 22f
    var onStrokeStarted: () -> Unit = {}
    var onStrokeFinished: () -> Unit = {}

    private class Pending(val startUptime: Long, val brush: InkBrush)

    private val pending = HashMap<InProgressStrokeId, Pending>()

    private var drawPointer = -1
    private var drawStroke: InProgressStrokeId? = null
    private var erasePointer = -1

    // finger navigation
    private var panPointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var pinching = false
    private var lastSpan = 0f
    private var lastFx = 0f
    private var lastFy = 0f

    override fun onHover(v: View, event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) StylusTracker.onStylusEvent(event.eventTime)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Always consume so a pen landing while the palm already rests on the screen still arrives.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDown(v, event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelAll(event)
        }
        return true
    }

    private fun isFinger(type: Int) = type == MotionEvent.TOOL_TYPE_FINGER || type == MotionEvent.TOOL_TYPE_UNKNOWN
    private fun isCanceled(e: MotionEvent) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (e.flags and MotionEvent.FLAG_CANCELED) != 0

    private fun fingerCount(e: MotionEvent) = (0 until e.pointerCount).count { isFinger(e.getToolType(it)) }

    private fun onDown(v: View, e: MotionEvent) {
        val i = e.actionIndex
        val type = e.getToolType(i)
        val id = e.getPointerId(i)
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) StylusTracker.onStylusEvent(e.eventTime)
        if (!enabled) return

        val stylusLike = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER || type == MotionEvent.TOOL_TYPE_MOUSE
        if (stylusLike) {
            // the pen wins over a finger stroke or gesture that was probably the palm
            cancelDraw(e)
            endNavigation()
            val erase = type == MotionEvent.TOOL_TYPE_ERASER || tool.isEraser ||
                (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            if (erase) startErase(id, e.getX(i), e.getY(i)) else startDraw(v, e, id)
            return
        }
        if (!isFinger(type)) return

        val nav = viewport != null
        if (nav && fingerCount(e) >= 2) {
            // second finger: this is a pinch/pan, not writing
            if (drawStroke != null && isFingerPointer(e, drawPointer)) cancelDraw(e)
            startPinch(e)
            return
        }
        if (drawStroke != null || erasePointer >= 0 || pinching) return
        if (StylusTracker.isStylusActive(e.eventTime)) {
            if (nav) startPan(id, e.getX(i), e.getY(i))
            return
        }
        if (tool.isEraser) startErase(id, e.getX(i), e.getY(i)) else startDraw(v, e, id)
    }

    private fun isFingerPointer(e: MotionEvent, pointerId: Int): Boolean {
        val idx = e.findPointerIndex(pointerId)
        return idx >= 0 && isFinger(e.getToolType(idx))
    }

    private fun onMove(e: MotionEvent) {
        if (erasePointer >= 0) {
            val i = e.findPointerIndex(erasePointer)
            if (i >= 0) erase(e.getX(i), e.getY(i))
        }
        drawStroke?.let { s ->
            if (e.findPointerIndex(drawPointer) >= 0) view.addToStroke(e, drawPointer, s)
        }
        val vp = viewport ?: return
        if (pinching && e.pointerCount >= 2) {
            val (fx, fy, span) = pinchGeometry(e)
            val zoom = if (lastSpan > 1f) span / lastSpan else 1f
            vp.transform(fx - lastFx, fy - lastFy, zoom, fx, fy)
            lastFx = fx; lastFy = fy; lastSpan = span
        } else if (panPointer >= 0) {
            val i = e.findPointerIndex(panPointer)
            if (i >= 0) {
                vp.transform(e.getX(i) - lastX, e.getY(i) - lastY, 1f, 0f, 0f)
                lastX = e.getX(i); lastY = e.getY(i)
            }
        }
    }

    private fun onUp(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        if (id == erasePointer) erasePointer = -1
        if (id == drawPointer) {
            drawStroke?.let { s ->
                if (isCanceled(e)) {
                    view.cancelStroke(s, e)
                    pending.remove(s)
                } else {
                    view.finishStroke(e, drawPointer, s)
                }
            }
            drawStroke = null
            drawPointer = -1
        }
        if (id == panPointer) panPointer = -1
        if (pinching && (e.actionMasked == MotionEvent.ACTION_UP || e.pointerCount - 1 < 2)) endNavigation()
    }

    private fun cancelAll(e: MotionEvent) {
        cancelDraw(e)
        erasePointer = -1
        endNavigation()
    }

    private fun startDraw(v: View, e: MotionEvent, pointerId: Int) {
        val l = layer ?: return
        v.requestUnbufferedDispatch(e)
        val brush = tool.brush()
        val toWorld: Matrix = viewport?.screenToWorld() ?: Matrix()
        val strokeId = view.startStroke(e, pointerId, StrokeConversion.brush(brush), toWorld)
        if (l.timeBaseUptime < 0) l.timeBaseUptime = e.eventTime
        pending[strokeId] = Pending(e.eventTime, brush)
        drawStroke = strokeId
        drawPointer = pointerId
        onStrokeStarted()
    }

    private fun cancelDraw(e: MotionEvent) {
        drawStroke?.let {
            view.cancelStroke(it, e)
            pending.remove(it)
        }
        drawStroke = null
        drawPointer = -1
    }

    private fun startErase(pointerId: Int, x: Float, y: Float) {
        erasePointer = pointerId
        onStrokeStarted()
        erase(x, y)
    }

    private fun erase(sx: Float, sy: Float) {
        val l = layer ?: return
        val vp = viewport
        val changed = if (vp == null) l.eraseAt(sx, sy, eraserRadiusPx)
        else l.eraseAt(vp.toWorldX(sx), vp.toWorldY(sy), eraserRadiusPx / vp.scale)
        if (changed) onStrokeFinished()
    }

    private fun startPan(pointerId: Int, x: Float, y: Float) {
        panPointer = pointerId
        lastX = x; lastY = y
    }

    private fun startPinch(e: MotionEvent) {
        panPointer = -1
        pinching = true
        val (fx, fy, span) = pinchGeometry(e)
        lastFx = fx; lastFy = fy; lastSpan = span
    }

    private fun endNavigation() {
        pinching = false
        panPointer = -1
    }

    private fun pinchGeometry(e: MotionEvent): Triple<Float, Float, Float> {
        val a = 0
        val b = 1
        val fx = (e.getX(a) + e.getX(b)) / 2
        val fy = (e.getY(a) + e.getY(b)) / 2
        return Triple(fx, fy, hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)))
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val l = layer
        strokes.entries
            .sortedBy { pending[it.key]?.startUptime ?: Long.MAX_VALUE }
            .forEach { (id, stroke) ->
                val p = pending.remove(id) ?: return@forEach
                if (l != null) {
                    val offset = (p.startUptime - l.timeBaseUptime).coerceAtLeast(0)
                    l.addFinished(StrokeConversion.fromInk(stroke, offset, p.brush), stroke)
                }
            }
        view.removeFinishedStrokes(strokes.keys)
        onStrokeFinished()
    }
}
