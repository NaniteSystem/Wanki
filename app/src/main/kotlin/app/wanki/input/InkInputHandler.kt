package app.wanki.input

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import app.wanki.ink.Gestures
import app.wanki.ink.InkBrush
import app.wanki.ink.InkPoint
import app.wanki.ink.InkStroke
import app.wanki.ink.InkTool
import app.wanki.ink.StraightLine
import kotlin.math.abs
import kotlin.math.hypot

/** A straight line held with the pen, in world coordinates, drawn instead of the stroke while the pen is still down. */
internal data class LinePreview(val from: androidx.compose.ui.geometry.Offset, val to: androidx.compose.ui.geometry.Offset, val brush: InkBrush)

/**
 * All canvas input in one place, no mode buttons:
 * - stylus tip draws (or erases with the eraser tool), the pen's eraser end and side button erase;
 * - on canvases (a [viewport] or pen [gestures]) a finger draws only while no stylus was seen on this
 *   screen ([StylusTracker.fingerDraws]) and the touch is neither a palm ([PalmFilter]) nor cancelled
 *   by the system; otherwise it pans. Review answer cells keep the 5-second stylus window;
 * - two fingers always pan and zoom (when the canvas has a [viewport]);
 * - with a [viewport], a quick two-finger tap undoes and a three-finger tap redoes ([TapGestures]);
 * - one eraser drag is one undo step ([InkLayer.beginErase]);
 * - holding the pen still for [StraightLine.HOLD_MS] at the end of a stroke straightens it into a line (canvases with a
 *   [viewport]); the line follows the pen until it lifts;
 * - a two-finger fling past 30 % of the width, with the page edge in view, calls [onPageSwipe] (pages of notebooks).
 */
internal class InkInputHandler(
    private val view: InProgressStrokesView,
) : View.OnTouchListener, View.OnHoverListener, InProgressStrokesFinishedListener {
    var layer: InkLayer? = null
    var viewport: Viewport? = null
    var tool: ToolSelection = ToolSelection()
    var enabled: Boolean = true

    /** Navigation (pan, pinch) only: no drawing, erasing, selecting or tap undo/redo. */
    var readOnly: Boolean = false
    /** Pen gestures (scratch-out to erase). Off where every stroke is an answer (review cells). */
    var gestures: Boolean = false
    /** Mode and size of the eraser; the radius is [EraserSize.radiusDp] on screen at any zoom. */
    var eraser: EraserSettings = EraserSettings()
    /** The eraser circle under the pen (screen px, radius px), null when not erasing. */
    var onEraserCursor: (EraserCursor?) -> Unit = {}
    var onStrokeStarted: () -> Unit = {}
    var onStrokeFinished: () -> Unit = {}
    /** A pan or pinch ended (the viewport changed). */
    var onViewGestureEnd: () -> Unit = {}
    /** A two-finger fling at the page edge: +1 next page, -1 previous. Null where pages do not turn. */
    var onPageSwipe: ((Int) -> Unit)? = null
    /** The straight line being held (world coordinates and brush), null when no hold is active. */
    var onLinePreview: (LinePreview?) -> Unit = {}

    /**
     * Points are captured here, straight from the touch events, so the stroke model does not
     * depend on androidx.ink's render-thread callback (which may come late or, on some devices
     * and emulators, not at all). androidx.ink is only used for low-latency drawing.
     */
    private class Capture(val brush: InkBrush, val tool: InkTool) {
        val points = ArrayList<InkPoint>()
    }

    private var capture: Capture? = null

    // straight line: after a hold the in-progress stroke is cancelled and this line follows the pen
    private var lineEnd: InkPoint? = null
    private val density get() = view.resources.displayMetrics.density
    private val holdCheck = Runnable { checkHold() }
    // a pen held still still sends moves (pressure and tilt noise): only a move out of the slop restarts the timer
    private var anchorX = 0f
    private var anchorY = 0f

    private var drawPointer = -1
    private var drawStroke: InProgressStrokeId? = null
    private var erasePointer = -1
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private var lassoPointer = -1
    private var movePointer = -1
    private var lastWorld = androidx.compose.ui.geometry.Offset.Zero

    // finger navigation
    private var panPointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var pinching = false
    private var lastSpan = 0f
    private var lastFx = 0f
    private var lastFy = 0f

    // page fling: where the two-finger gesture started
    private var startFx = 0f
    private var startFy = 0f
    private var startSpan = 0f
    private var startScale = 1f
    private var startOx = 0f
    private var startOy = 0f
    private var startEdgeNext = false
    private var startEdgePrev = false

    private val taps by lazy { TapGestures(slopPx = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()) }

    /** Canvases (pages, boards, notes) follow the per-screen pen rule; review cells keep the time window. */
    private val canvasRules: Boolean get() = viewport != null || gestures

    override fun onHover(v: View, event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) StylusTracker.onStylusEvent(event.eventTime)
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Always consume so a pen landing while the palm already rests on the screen still arrives.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { trackTapDown(event); onDown(v, event) }
            MotionEvent.ACTION_MOVE -> { trackTapMove(event); onMove(event) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val tap = trackTapUp(event)
                onUp(event)
                // a pen stroke, eraser drag or move still open: undo now would race it, drop the tap
                val busy = drawStroke != null || lineEnd != null || erasePointer >= 0 || movePointer >= 0
                if (tap != null && enabled && !readOnly && !busy) layer?.let { if (tap == Tap.TWO_FINGER) it.undo() else it.redo() }
            }
            MotionEvent.ACTION_CANCEL -> { taps.reset(); cancelAll(event) }
        }
        return true
    }

    /** A pen (or mouse) pointer anywhere in the event: fingers next to it are a resting hand, not a tap. */
    private fun hasNonFinger(e: MotionEvent) = (0 until e.pointerCount).any { !isFinger(e.getToolType(it)) }

    private fun trackTapDown(e: MotionEvent) {
        if (viewport == null) return
        val i = e.actionIndex
        if (isFinger(e.getToolType(i)) && !isPalm(e, i) && !hasNonFinger(e)) taps.onDown(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
        else taps.reset() // a pen or a palm in the gesture: not a tap
    }

    private fun trackTapMove(e: MotionEvent) {
        if (viewport == null) return
        if (hasNonFinger(e)) { taps.reset(); return }
        for (i in 0 until e.pointerCount) if (isFinger(e.getToolType(i))) taps.onMove(e.getPointerId(i), e.getX(i), e.getY(i))
    }

    private fun trackTapUp(e: MotionEvent): Tap? {
        if (viewport == null) return null
        if (isCanceled(e) || hasNonFinger(e)) { taps.reset(); return null }
        return taps.onUp(e.getPointerId(e.actionIndex), e.eventTime)
    }

    private fun isPalm(e: MotionEvent, i: Int) = PalmFilter.isPalm(e.getTouchMajor(i), view.resources.displayMetrics.density)

    private fun isFinger(type: Int) = type == MotionEvent.TOOL_TYPE_FINGER || type == MotionEvent.TOOL_TYPE_UNKNOWN
    private fun isCanceled(e: MotionEvent) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (e.flags and MotionEvent.FLAG_CANCELED) != 0

    private fun fingerCount(e: MotionEvent) = (0 until e.pointerCount).count { isFinger(e.getToolType(it)) }

    private fun onDown(v: View, e: MotionEvent) {
        val i = e.actionIndex
        val type = e.getToolType(i)
        val id = e.getPointerId(i)
        if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) StylusTracker.onStylusEvent(e.eventTime)
        Log.d(TAG, "down type=$type enabled=$enabled layer=${layer != null} stylusActive=${StylusTracker.isStylusActive(e.eventTime)}")
        if (!enabled) return

        val stylusLike = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER || type == MotionEvent.TOOL_TYPE_MOUSE
        if (stylusLike) {
            if (readOnly) return
            // the pen wins over a finger stroke or gesture that was probably the palm
            cancelDraw(e)
            cancelFingerErase(e)
            cancelFingerSelect(e)
            endNavigation()
            val erase = type == MotionEvent.TOOL_TYPE_ERASER || tool.isEraser ||
                (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            when {
                erase -> startErase(id, e.getX(i), e.getY(i))
                tool.isLasso -> startLasso(id, e.getX(i), e.getY(i))
                else -> startDraw(v, e, id)
            }
            return
        }
        if (!isFinger(type)) return

        val nav = viewport != null
        if (nav && fingerCount(e) >= 2) {
            // second finger: this is a pinch/pan, not writing
            if ((drawStroke != null || lineEnd != null) && isFingerPointer(e, drawPointer)) cancelDraw(e)
            cancelFingerErase(e)
            cancelFingerSelect(e)
            startPinch(e)
            return
        }
        if (drawStroke != null || lineEnd != null || erasePointer >= 0 || pinching) return
        val fingerDraws = !readOnly && if (canvasRules) StylusTracker.fingerDraws() && !isPalm(e, i) && !isCanceled(e)
        else !StylusTracker.isStylusActive(e.eventTime)
        if (!fingerDraws) {
            if (nav) startPan(id, e.getX(i), e.getY(i))
            return
        }
        when {
            tool.isEraser -> startErase(id, e.getX(i), e.getY(i))
            tool.isLasso -> startLasso(id, e.getX(i), e.getY(i))
            else -> startDraw(v, e, id)
        }
    }

    private fun isFingerPointer(e: MotionEvent, pointerId: Int): Boolean {
        val idx = e.findPointerIndex(pointerId)
        return idx >= 0 && isFinger(e.getToolType(idx))
    }

    private fun onMove(e: MotionEvent) {
        if (movePointer >= 0) {
            val i = e.findPointerIndex(movePointer)
            if (i >= 0) {
                val w = world(e.getX(i), e.getY(i))
                layer?.moveSelectionBy(w.x - lastWorld.x, w.y - lastWorld.y)
                lastWorld = w
            }
            return
        }
        if (lassoPointer >= 0) {
            val i = e.findPointerIndex(lassoPointer)
            if (i >= 0) layer?.let { l -> l.lasso = l.lasso.orEmpty() + world(e.getX(i), e.getY(i)) }
            return
        }
        if (erasePointer >= 0) {
            val i = e.findPointerIndex(erasePointer)
            if (i >= 0) erase(e.getX(i), e.getY(i))
        }
        lineEnd?.let {
            val di = e.findPointerIndex(drawPointer)
            if (di >= 0) moveLine(e, di)
        }
        drawStroke?.let { s ->
            val di = e.findPointerIndex(drawPointer)
            if (canvasRules && di >= 0 && isFinger(e.getToolType(di)) && isPalm(e, di)) {
                // the "finger" spread into a palm: drop the stroke
                cancelDraw(e)
                return@let
            }
            if (di >= 0) {
                capture?.let { addPoints(it, e, drawPointer, historical = true) }
                view.addToStroke(e, drawPointer, s)
                capture?.points?.lastOrNull()?.let { p ->
                    val slop = SLOP_DP * density / (viewport?.scale ?: 1f)
                    if (hypot(p.x - anchorX, p.y - anchorY) > slop) scheduleHold()
                }
            }
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
        if (id == movePointer) {
            movePointer = -1
            layer?.endMove()
            onStrokeFinished()
        }
        if (id == lassoPointer) {
            lassoPointer = -1
            layer?.let { l ->
                l.selectInside(l.lasso.orEmpty())
                l.lasso = null
            }
        }
        if (id == erasePointer) finishErase()
        if (id == drawPointer && lineEnd != null) {
            if (isCanceled(e)) clearLine() else commitLine(e)
            drawPointer = -1
        }
        if (id == drawPointer) {
            view.removeCallbacks(holdCheck)
            drawStroke?.let { s ->
                if (isCanceled(e)) {
                    view.cancelStroke(s, e)
                    capture = null
                } else {
                    capture?.let { addPoints(it, e, drawPointer, historical = true) }
                    view.finishStroke(e, drawPointer, s)
                    commitCapture()
                }
            }
            drawStroke = null
            drawPointer = -1
        }
        if (id == panPointer) { panPointer = -1; onViewGestureEnd() }
        if (pinching && (e.actionMasked == MotionEvent.ACTION_UP || e.pointerCount - 1 < 2)) {
            val swipe = if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else pageSwipe()
            endNavigation(keepView = swipe != 0)
            if (swipe != 0) onPageSwipe?.invoke(swipe)
        }
    }

    private fun cancelAll(e: MotionEvent) {
        cancelDraw(e)
        drawPointer = -1
        if (movePointer >= 0) { movePointer = -1; layer?.endMove() }
        lassoPointer = -1
        layer?.lasso = null
        finishErase()
        endNavigation()
    }

    private fun world(x: Float, y: Float): androidx.compose.ui.geometry.Offset {
        val vp = viewport
        return androidx.compose.ui.geometry.Offset(vp?.toWorldX(x) ?: x, vp?.toWorldY(y) ?: y)
    }

    private fun startLasso(pointerId: Int, x: Float, y: Float) {
        val l = layer ?: return
        val w = world(x, y)
        val b = l.selectionBounds()
        val pad = 16f / (viewport?.scale ?: 1f)
        if (b != null && w.x in b.left - pad..b.right + pad && w.y in b.top - pad..b.bottom + pad) {
            // touching the selection drags it
            movePointer = pointerId
            lastWorld = w
            l.beginMove()
            return
        }
        lassoPointer = pointerId
        layer?.let { it.clearSelection(); it.lasso = listOf(world(x, y)) }
    }

    private fun startDraw(v: View, e: MotionEvent, pointerId: Int) {
        val l = layer ?: return
        // inside a scrolling list (note blocks) the stroke must not turn into a scroll
        v.parent?.requestDisallowInterceptTouchEvent(true)
        l.clearSelection()
        v.requestUnbufferedDispatch(e)
        val brush = tool.brush()
        val toWorld: Matrix = viewport?.screenToWorld() ?: Matrix()
        val strokeId = view.startStroke(e, pointerId, StrokeConversion.brush(brush), toWorld)
        if (l.timeBaseUptime < 0) l.timeBaseUptime = e.eventTime
        capture = Capture(brush, toolOf(e.getToolType(e.findPointerIndex(pointerId)))).also { addPoints(it, e, pointerId, historical = false) }
        Log.d(TAG, "stroke start tool=${e.getToolType(e.findPointerIndex(pointerId))}")
        drawStroke = strokeId
        drawPointer = pointerId
        onStrokeStarted()
        scheduleHold()
    }

    /** A still pen sends no moves, so the hold is checked by a timer that every move pushes back. */
    private fun scheduleHold() {
        if (viewport == null || readOnly) return
        capture?.points?.lastOrNull()?.let { anchorX = it.x; anchorY = it.y }
        view.removeCallbacks(holdCheck)
        view.postDelayed(holdCheck, StraightLine.HOLD_MS)
    }

    private fun checkHold() {
        val s = drawStroke ?: return
        val c = capture ?: return
        val vp = viewport ?: return
        val base = layer?.timeBaseUptime?.takeIf { it >= 0 } ?: return
        val scale = vp.scale
        val now = SystemClock.uptimeMillis() - base
        if (!StraightLine.isHeld(c.points, now, SLOP_DP * density / scale, MIN_LENGTH_DP * density / scale)) {
            scheduleHold() // too short or drifted: look again later
            return
        }
        view.cancelStroke(s)
        drawStroke = null
        lineEnd = c.points.last()
        showLine()
    }

    private fun moveLine(e: MotionEvent, i: Int) {
        val c = capture ?: return
        val base = layer?.timeBaseUptime?.takeIf { it >= 0 } ?: e.eventTime
        val w = world(e.getX(i), e.getY(i))
        lineEnd = InkPoint(w.x, w.y, (e.eventTime - base).coerceAtLeast(c.points.last().timeMs))
        showLine()
    }

    private fun showLine() {
        val c = capture ?: return
        val end = lineEnd ?: return
        val a = c.points.first()
        onLinePreview(LinePreview(androidx.compose.ui.geometry.Offset(a.x, a.y), androidx.compose.ui.geometry.Offset(end.x, end.y), c.brush))
    }

    private fun clearLine() {
        if (lineEnd == null) return
        lineEnd = null
        onLinePreview(null)
    }

    /** The pen lifted after a hold: the line replaces the stroke, as one undo step. */
    private fun commitLine(e: MotionEvent) {
        val c = capture
        val end = lineEnd
        val l = layer
        val i = e.findPointerIndex(drawPointer)
        if (i >= 0) moveLine(e, i)
        val last = lineEnd ?: end
        clearLine()
        capture = null
        if (c == null || last == null || l == null || c.points.isEmpty()) return
        l.add(StraightLine.line(InkStroke(c.points.toList(), c.tool, c.brush), last))
        onStrokeFinished()
    }

    private fun cancelDraw(e: MotionEvent) {
        drawStroke?.let { view.cancelStroke(it, e) }
        clearLine()
        view.removeCallbacks(holdCheck)
        capture = null
        drawStroke = null
        drawPointer = -1
    }

    private fun startErase(pointerId: Int, x: Float, y: Float) {
        if (erasePointer >= 0) finishErase()
        erasePointer = pointerId
        layer?.beginErase()
        onStrokeStarted()
        erase(x, y, first = true)
    }

    /** Erases from the previous sample to this one (a capsule, so a fast drag leaves no gaps). */
    private fun erase(sx: Float, sy: Float, first: Boolean = false) {
        val l = layer ?: return
        val vp = viewport
        val px = eraser.size.radiusDp * view.resources.displayMetrics.density
        val (fromX, fromY) = if (first) sx to sy else eraseLastX to eraseLastY
        if (vp == null) l.eraseAlong(fromX, fromY, sx, sy, px, eraser.mode)
        else l.eraseAlong(vp.toWorldX(fromX), vp.toWorldY(fromY), vp.toWorldX(sx), vp.toWorldY(sy), px / vp.scale, eraser.mode)
        eraseLastX = sx
        eraseLastY = sy
        onEraserCursor(EraserCursor(sx, sy, px))
    }

    /** The eraser lifted: the whole drag becomes one undo step. */
    private fun finishErase() {
        if (erasePointer < 0) return
        erasePointer = -1
        onEraserCursor(null)
        if (layer?.endErase() == true) onStrokeFinished()
    }

    /** A finger lasso or selection drag that turned out to be a pinch or a tap: drop it without an undo step. */
    private fun cancelFingerSelect(e: MotionEvent) {
        if (movePointer >= 0 && isFingerPointer(e, movePointer)) {
            movePointer = -1
            layer?.cancelMove()
        }
        if (lassoPointer >= 0 && isFingerPointer(e, lassoPointer)) {
            lassoPointer = -1
            layer?.lasso = null
        }
    }

    /** A finger eraser drag that turned out to be a pinch, a tap or a palm under the pen: undo it silently. */
    private fun cancelFingerErase(e: MotionEvent) {
        if (erasePointer < 0 || !isFingerPointer(e, erasePointer)) return
        erasePointer = -1
        onEraserCursor(null)
        layer?.cancelErase()
    }

    private fun startPan(pointerId: Int, x: Float, y: Float) {
        panPointer = pointerId
        lastX = x; lastY = y
    }

    private fun startPinch(e: MotionEvent) {
        panPointer = -1
        pinching = true
        viewport?.pinching = true
        val (fx, fy, span) = pinchGeometry(e)
        lastFx = fx; lastFy = fy; lastSpan = span
        startFx = fx; startFy = fy; startSpan = span
        viewport?.let {
            startScale = it.scale; startOx = it.offsetX; startOy = it.offsetY
            startEdgeNext = it.edgeVisible(1); startEdgePrev = it.edgeVisible(-1)
        }
    }

    /**
     * +1 / -1 when the finished two-finger gesture was a horizontal fling over 30 % of the width without
     * zooming and with the page edge in that direction in view (the page fits); the view is put back then.
     */
    private fun pageSwipe(): Int {
        if (onPageSwipe == null || !enabled) return 0
        val vp = viewport ?: return 0
        val dx = lastFx - startFx
        val dy = lastFy - startFy
        val zoomed = startSpan < 1f || lastSpan / startSpan !in 0.8f..1.25f
        if (zoomed || abs(dx) < 0.3f * view.width || abs(dx) < 2 * abs(dy)) return 0
        val dir = if (dx < 0) 1 else -1
        if (!(if (dir > 0) startEdgeNext else startEdgePrev)) return 0
        vp.restore(startScale, startOx, startOy)
        return dir
    }

    private fun endNavigation(keepView: Boolean = false) {
        val moved = pinching || panPointer >= 0
        pinching = false
        viewport?.pinching = false
        panPointer = -1
        if (moved && !keepView) onViewGestureEnd()
    }

    private fun pinchGeometry(e: MotionEvent): Triple<Float, Float, Float> {
        val a = 0
        val b = 1
        val fx = (e.getX(a) + e.getX(b)) / 2
        val fy = (e.getY(a) + e.getY(b)) / 2
        return Triple(fx, fy, hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)))
    }

    private fun commitCapture() {
        val c = capture ?: return
        capture = null
        val l = layer ?: return
        if (c.points.isEmpty()) return
        val stroke = InkStroke(c.points.toList(), c.tool, c.brush)
        if (gestures && Gestures.isScratchOut(stroke)) {
            val hit = Gestures.scratchedStrokes(stroke, l.strokes)
            if (hit.isNotEmpty()) {
                l.removeAt(hit)
                Log.d(TAG, "scratch-out erased ${hit.size} strokes")
                onStrokeFinished()
                return
            }
        }
        l.add(stroke)
        Log.d(TAG, "stroke committed: ${c.points.size} points, layer=${l.strokes.size}")
        onStrokeFinished()
    }

    private fun addPoints(c: Capture, e: MotionEvent, pointerId: Int, historical: Boolean) {
        val i = e.findPointerIndex(pointerId)
        if (i < 0) return
        val base = layer?.timeBaseUptime?.takeIf { it >= 0 } ?: e.eventTime
        val vp = viewport
        fun add(x: Float, y: Float, t: Long, pressure: Float, tilt: Float, orientation: Float) {
            val wx = vp?.toWorldX(x) ?: x
            val wy = vp?.toWorldY(y) ?: y
            val last = c.points.lastOrNull()
            if (last != null && last.x == wx && last.y == wy) return
            c.points += InkPoint(wx, wy, (t - base).coerceAtLeast(last?.timeMs ?: 0), pressure, tilt, orientation)
        }
        val stylus = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
        if (historical) {
            for (h in 0 until e.historySize) {
                add(
                    e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h),
                    if (stylus) e.getHistoricalPressure(i, h).coerceIn(0f, 1f) else Float.NaN,
                    if (stylus) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h) else Float.NaN,
                    if (stylus) normalizeOrientation(e.getHistoricalOrientation(i, h)) else Float.NaN,
                )
            }
        }
        add(
            e.getX(i), e.getY(i), e.eventTime,
            if (stylus) e.getPressure(i).coerceIn(0f, 1f) else Float.NaN,
            if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT, i) else Float.NaN,
            if (stylus) normalizeOrientation(e.getOrientation(i)) else Float.NaN,
        )
    }

    /** MotionEvent orientation is −π..π; storage uses 0..2π. */
    private fun normalizeOrientation(o: Float): Float = if (o < 0) o + (2 * Math.PI).toFloat() else o

    private fun toolOf(type: Int) = when (type) {
        MotionEvent.TOOL_TYPE_STYLUS -> InkTool.STYLUS
        MotionEvent.TOOL_TYPE_FINGER -> InkTool.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> InkTool.MOUSE
        else -> InkTool.UNKNOWN
    }

    /** The view goes away: no timer may outlive it. */
    fun release() {
        view.removeCallbacks(holdCheck)
    }

    /** androidx.ink finished rendering: our layer already has the stroke, drop the in-progress copy. */
    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        view.removeFinishedStrokes(strokes.keys)
    }

    private companion object {
        const val TAG = "WankiInk"
        const val SLOP_DP = 6f
        const val MIN_LENGTH_DP = 24f
    }
}
