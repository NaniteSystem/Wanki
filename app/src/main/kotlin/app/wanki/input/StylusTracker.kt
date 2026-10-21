package app.wanki.input

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Decides, without any setting, whether a finger should draw.
 *
 * Canvases ([fingerDraws]): once a stylus touched or hovered over this screen, fingers only pan and
 * zoom until the user leaves the screen ([onScreenChanged], called by the navigation shell).
 * Without a pen fingers draw right away.
 *
 * Review answer cells keep the older rule, [isStylusActive]: a stylus seen in the last [WINDOW_MS].
 */
object StylusTracker {
    private const val WINDOW_MS = 5_000L

    @Volatile
    private var lastStylusUptime = Long.MIN_VALUE / 2

    @Volatile
    private var stylusOnScreen = false

    /** Whether a stylus has been used at all in this app session (for UI hints only). */
    var stylusSeen by mutableStateOf(false)
        private set

    fun onStylusEvent(uptimeMs: Long = SystemClock.uptimeMillis()) {
        lastStylusUptime = uptimeMs
        stylusOnScreen = true
        if (!stylusSeen) stylusSeen = true
    }

    /** A new screen: until a stylus shows up there, fingers draw again. */
    fun onScreenChanged() {
        stylusOnScreen = false
    }

    /** True when no stylus has been seen since the last screen change. */
    fun fingerDraws(): Boolean = !stylusOnScreen

    fun isStylusActive(uptimeMs: Long = SystemClock.uptimeMillis()): Boolean = uptimeMs - lastStylusUptime < WINDOW_MS
}

/** A contact wider than [MAX_FINGER_DP] is a resting palm, not a fingertip. */
object PalmFilter {
    private const val MAX_FINGER_DP = 100f

    fun isPalm(touchMajorPx: Float, density: Float): Boolean = touchMajorPx / density > MAX_FINGER_DP
}
