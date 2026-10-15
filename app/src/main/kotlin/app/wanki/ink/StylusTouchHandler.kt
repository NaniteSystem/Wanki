package app.wanki.ink

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush

/**
 * Feeds touch events into [InProgressStrokesView] with a stylus-first policy:
 * one stroke at a time, fingers filtered by [fingerMode], palm touches cancelled
 * (ACTION_CANCEL / FLAG_CANCELED), and the pen's eraser end removing strokes.
 */
internal class StylusTouchHandler(
    private val view: InProgressStrokesView,
    private val state: InkCanvasState,
) : View.OnTouchListener {
    var brush: Brush? = null
    var fingerMode: FingerMode = FingerMode.AUTO
    var enabled: Boolean = true
    var eraserRadiusPx: Float = 24f

    private var activePointerId = -1
    private var activeStroke: InProgressStrokeId? = null
    private var erasing = false

    private fun accepts(toolType: Int): Boolean = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> true
        MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_MOUSE, MotionEvent.TOOL_TYPE_UNKNOWN -> when (fingerMode) {
            FingerMode.ANY -> true
            FingerMode.STYLUS_ONLY -> false
            FingerMode.AUTO -> !state.stylusSeen
        }
        else -> false
    }

    private fun isCanceled(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Always consume: returning false on DOWN would drop the rest of the gesture, including a pen
        // that lands while the palm is already resting on the screen.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> onDown(v, event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelActive(event)
        }
        return true
    }

    private fun onDown(v: View, event: MotionEvent) {
        val idx = event.actionIndex
        val tool = event.getToolType(idx)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS) state.stylusSeen = true
        if (!enabled) return
        if (tool == MotionEvent.TOOL_TYPE_ERASER) {
            erasing = true
            activePointerId = event.getPointerId(idx)
            state.eraseAt(event.getX(idx), event.getY(idx), eraserRadiusPx)
            return
        }
        // A stylus landing during a finger stroke wins: the finger stroke was probably the palm.
        if (activeStroke != null) {
            if (tool == MotionEvent.TOOL_TYPE_STYLUS) cancelActive(event) else return
        }
        if (!accepts(tool)) return
        val b = brush ?: return
        v.requestUnbufferedDispatch(event)
        activePointerId = event.getPointerId(idx)
        val id = view.startStroke(event, activePointerId, b)
        activeStroke = id
        state.onStrokeStarted(id, event.eventTime)
    }

    private fun onMove(event: MotionEvent) {
        if (erasing) {
            val i = event.findPointerIndex(activePointerId)
            if (i >= 0) state.eraseAt(event.getX(i), event.getY(i), eraserRadiusPx)
            return
        }
        val id = activeStroke ?: return
        if (event.findPointerIndex(activePointerId) < 0) return
        view.addToStroke(event, activePointerId, id)
    }

    private fun onUp(event: MotionEvent) {
        if (event.getPointerId(event.actionIndex) != activePointerId) return
        if (erasing) {
            erasing = false
            activePointerId = -1
            return
        }
        val id = activeStroke ?: return
        if (isCanceled(event)) {
            view.cancelStroke(id, event)
            state.onStrokeCancelled(id)
        } else {
            view.finishStroke(event, activePointerId, id)
        }
        activeStroke = null
        activePointerId = -1
    }

    private fun cancelActive(event: MotionEvent) {
        erasing = false
        activeStroke?.let {
            view.cancelStroke(it, event)
            state.onStrokeCancelled(it)
        }
        activeStroke = null
        activePointerId = -1
    }
}
