package app.wanki.input

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * The timeline of a page open (logcat tag `WankiPerf`), off unless a speed test turns it on: each named step is
 * logged once, in ms since [begin]. Any thread.
 */
object OpenTrace {
    @Volatile
    @VisibleForTesting
    var enabled = false

    @Volatile private var start = 0L
    private val seen = ConcurrentHashMap<String, Long>()

    /** Starts a new timeline. */
    fun begin() {
        start = SystemClock.uptimeMillis()
        seen.clear()
    }

    /** The first time [step] happens since [begin]. */
    fun mark(step: String) {
        if (!enabled) return
        val t = SystemClock.uptimeMillis() - start
        if (seen.putIfAbsent(step, t) == null) Log.i("WankiPerf", "open +$t ms: $step")
    }

    /** [block], logged with how long it took. */
    inline fun <T> timed(step: String, block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.uptimeMillis()
        return block().also { mark("$step (${SystemClock.uptimeMillis() - t0} ms)") }
    }
}
