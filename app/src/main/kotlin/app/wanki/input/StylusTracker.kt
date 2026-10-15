package app.wanki.input

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Decides, without any setting, whether a finger should draw: it does unless a stylus touched or
 * hovered over a canvas in the last [WINDOW_MS]. So with a pen the palm and fingers rest freely
 * (and a finger pans), without a pen fingers draw right away.
 */
object StylusTracker {
    private const val WINDOW_MS = 5_000L

    @Volatile
    private var lastStylusUptime = Long.MIN_VALUE / 2

    /** Whether a stylus has been used at all in this app session (for UI hints only). */
    var stylusSeen by mutableStateOf(false)
        private set

    fun onStylusEvent(uptimeMs: Long = SystemClock.uptimeMillis()) {
        lastStylusUptime = uptimeMs
        if (!stylusSeen) stylusSeen = true
    }

    fun isStylusActive(uptimeMs: Long = SystemClock.uptimeMillis()): Boolean = uptimeMs - lastStylusUptime < WINDOW_MS
}
