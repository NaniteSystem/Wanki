package app.wanki.input

import kotlin.math.hypot

enum class Tap { TWO_FINGER, THREE_FINGER }

/**
 * Recognises quick multi-finger taps (two fingers = undo, three = redo). Fed with finger pointers
 * only; a tap is reported when the last finger lifts, if 2 or 3 fingers were down at once, none of
 * them moved more than [slopPx] and the whole contact lasted at most [maxDurationMs]. A pinch or pan
 * moves beyond the slop, so it never becomes a tap.
 */
class TapGestures(private val maxDurationMs: Long = 250, private val slopPx: Float) {
    private class Down(val x: Float, val y: Float)

    private val down = HashMap<Int, Down>()
    private var startTime = 0L
    private var maxFingers = 0
    private var valid = true

    fun onDown(pointerId: Int, x: Float, y: Float, t: Long) {
        if (down.isEmpty()) {
            startTime = t
            maxFingers = 0
            valid = true
        }
        down[pointerId] = Down(x, y)
        maxFingers = maxOf(maxFingers, down.size)
        if (maxFingers > 3) valid = false
    }

    fun onMove(pointerId: Int, x: Float, y: Float) {
        val d = down[pointerId] ?: return
        if (hypot(x - d.x, y - d.y) > slopPx) valid = false
    }

    fun onUp(pointerId: Int, t: Long): Tap? {
        if (down.remove(pointerId) == null) return null
        if (t - startTime > maxDurationMs) valid = false
        if (down.isNotEmpty()) return null
        if (!valid) return null
        valid = false
        return when (maxFingers) {
            2 -> Tap.TWO_FINGER
            3 -> Tap.THREE_FINGER
            else -> null
        }
    }

    /** Forgets the fingers down now (a stylus came in, or the system cancelled the touch). */
    fun reset() {
        down.clear()
        valid = false
    }
}
